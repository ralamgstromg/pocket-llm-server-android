package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lenient JSON reading for text a model produced: trailing commas and single quotes are fixed. */
object JsonRepair {
  private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

  fun parse(text: String): JsonElement? {
    val t = text.trim()
    if (t.isEmpty()) return null
    fun attempt(s: String): JsonElement? = try { OpenAiJson.parseToJsonElement(s) } catch (e: Exception) { null }
    attempt(t)?.let { return it }
    val noCommas = TRAILING_COMMA.replace(t, "$1")
    attempt(noCommas)?.let { return it }
    if ('\'' in noCommas && '"' !in noCommas) return attempt(noCommas.replace('\'', '"'))
    return null
  }

  /** The first balanced `{...}` or `[...]` in [s] starting at or after [from], or null. */
  fun balanced(s: String, from: Int = 0): Pair<IntRange, String>? {
    var start = -1
    for (i in from until s.length) if (s[i] == '{' || s[i] == '[') { start = i; break }
    if (start < 0) return null
    var depth = 0
    var inString = false
    var quote = '"'
    var i = start
    while (i < s.length) {
      val c = s[i]
      if (inString) {
        if (c == '\\') i++ else if (c == quote) inString = false
      } else if (c == '"' || c == '\'') {
        inString = true
        quote = c
      } else if (c == '{' || c == '[') depth++
      else if (c == '}' || c == ']') {
        depth--
        if (depth == 0) return Pair(start..i, s.substring(start, i + 1))
      }
      i++
    }
    return null
  }
}

/** Reads `name(arg=value, ...)` calls written the Python way, as Liquid LFM models do. */
object PythonicCallParser {
  private class Cursor(val s: String) {
    var i = 0

    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    fun peek(): Char? = s.getOrNull(i)

    fun eat(c: Char): Boolean { ws(); return if (peek() == c) { i++; true } else false }
  }

  /** Calls found in [text] (optionally wrapped in `[...]`), or null when it is not call syntax. */
  fun parse(text: String): List<Pair<String, JsonObject>>? {
    val c = Cursor(text.trim())
    val bracketed = c.eat('[')
    val calls = mutableListOf<Pair<String, JsonObject>>()
    try {
      while (true) {
        c.ws()
        if (bracketed && c.peek() == ']') { c.i++; break }
        if (c.peek() == null) break
        val name = ident(c) ?: return null
        if (!c.eat('(')) return null
        val args = LinkedHashMap<String, JsonElement>()
        while (true) {
          c.ws()
          if (c.eat(')')) break
          val key = ident(c) ?: return null
          if (!c.eat('=')) return null
          args[key] = value(c) ?: return null
          c.ws()
          if (!c.eat(',') && c.peek() != ')') return null
        }
        calls.add(Pair(name, JsonObject(args)))
        c.ws()
        c.eat(',')
      }
    } catch (e: IndexOutOfBoundsException) {
      return null
    }
    c.ws()
    return if (c.peek() == null && calls.isNotEmpty()) calls else null
  }

  private fun ident(c: Cursor): String? {
    c.ws()
    val start = c.i
    while (c.peek()?.let { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' } == true) c.i++
    return if (c.i > start) c.s.substring(start, c.i) else null
  }

  private fun value(c: Cursor): JsonElement? {
    c.ws()
    return when (val ch = c.peek()) {
      '"', '\'' -> JsonPrimitive(string(c, ch))
      '[' -> {
        c.i++
        val items = mutableListOf<JsonElement>()
        while (true) {
          c.ws()
          if (c.eat(']')) break
          items.add(value(c) ?: return null)
          c.ws()
          if (!c.eat(',') && c.peek() != ']') return null
        }
        JsonArray(items)
      }
      '{' -> {
        c.i++
        val map = LinkedHashMap<String, JsonElement>()
        while (true) {
          c.ws()
          if (c.eat('}')) break
          val k = value(c) as? JsonPrimitive ?: return null
          if (!c.eat(':')) return null
          map[k.content] = value(c) ?: return null
          c.ws()
          if (!c.eat(',') && c.peek() != '}') return null
        }
        JsonObject(map)
      }
      else -> {
        val start = c.i
        while (c.peek()?.let { it.isLetterOrDigit() || it in "+-._" } == true) c.i++
        val word = c.s.substring(start, c.i)
        when {
          word.isEmpty() -> null
          word == "True" || word == "true" -> JsonPrimitive(true)
          word == "False" || word == "false" -> JsonPrimitive(false)
          word == "None" || word == "null" -> JsonNull
          word.toLongOrNull() != null -> JsonPrimitive(word.toLong())
          word.toDoubleOrNull() != null -> JsonPrimitive(word.toDouble())
          else -> null
        }
      }
    }
  }

  private fun string(c: Cursor, quote: Char): String {
    c.i++
    val sb = StringBuilder()
    while (c.i < c.s.length) {
      val ch = c.s[c.i++]
      when {
        ch == '\\' -> {
          val n = c.s[c.i++]
          sb.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
        }
        ch == quote -> return sb.toString()
        else -> sb.append(ch)
      }
    }
    throw IndexOutOfBoundsException("unterminated string")
  }
}

/**
 * Extracts tool calls a model wrote as plain text and returns them in one shape: a name plus
 * `arguments` as a JSON object string. Formats understood:
 *
 * - Hermes/Qwen/Granite: `<tool_call>{"name":…,"arguments":{…}}</tool_call>`
 * - `<function_call>{…}</function_call>` and `<|tool_call|>[{…}]`
 * - Mistral/Ministral: `[TOOL_CALLS][{…}]` or `[TOOL_CALLS]name[ARGS]{…}`
 * - Liquid LFM: `<|tool_call_start|>[fn(a=1)]<|tool_call_end|>`
 * - A ```json fenced block, or the whole answer being one JSON call, when [allowed] names are given
 *
 * Calls inside a malformed block stay in the text.
 */
object ToolCallTextParser {
  const val MISTRAL = "[TOOL_CALLS]"

  /** Start markers; [ToolCallStreamFilter] holds text back when one may be starting. */
  val MARKERS = listOf("<tool_call>", "<function_call>", "<|tool_call_start|>", "<|tool_call|>", MISTRAL)

  private val TAGGED =
    listOf(
      Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL),
      Regex("<function_call>(.*?)</function_call>", RegexOption.DOT_MATCHES_ALL),
      Regex("<\\|tool_call_start\\|>(.*?)<\\|tool_call_end\\|>", RegexOption.DOT_MATCHES_ALL),
    )
  private val FENCE = Regex("```(?:json|JSON)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
  private val MISTRAL_NAMED = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*\\[ARGS]")

  /** Returns the remaining text and the parsed calls. [allowed] enables the untagged JSON forms. */
  fun parse(text: String, newId: () -> String, allowed: Set<String>? = null): Pair<String, List<ParsedToolCall>> {
    val calls = mutableListOf<ParsedToolCall>()
    var rest = text

    fun take(body: String): Boolean {
      val found = callsIn(body, newId)
      calls.addAll(found)
      return found.isNotEmpty()
    }

    for (regex in TAGGED) rest = regex.replace(rest) { m -> if (take(m.groupValues[1])) "" else m.value }
    rest = extractMistral(rest, ::take)
    rest = extractUnclosedGranite(rest, ::take)

    if (calls.isEmpty() && allowed != null) {
      rest = FENCE.replace(rest) { m -> if (take(m.groupValues[1].takeIf { callsNamed(it, allowed) } ?: "")) "" else m.value }
      if (calls.isEmpty() && (rest.trim().startsWith("{") || rest.trim().startsWith("["))) {
        val body = rest.trim()
        if (callsNamed(body, allowed) && take(body)) rest = ""
      }
    }
    return Pair(if (calls.isEmpty()) text else rest.trim(), calls)
  }

  private fun callsNamed(body: String, allowed: Set<String>): Boolean = callsIn(body) { "x" }.let { it.isNotEmpty() && it.all { c -> c.name in allowed } }

  /** `[TOOL_CALLS]` followed by a JSON array/object, or `name[ARGS]{json}`; may repeat. */
  private fun extractMistral(text: String, take: (String) -> Boolean): String {
    var out = text
    var from = 0
    while (true) {
      val at = out.indexOf(MISTRAL, from)
      if (at < 0) return out
      val bodyStart = at + MISTRAL.length
      val tail = out.substring(bodyStart)
      val named = MISTRAL_NAMED.find(tail)
      val (consumedEnd, body) =
        if (named != null) {
          val json = JsonRepair.balanced(tail, named.range.last + 1) ?: return out
          Pair(bodyStart + json.first.last + 1, """{"name":"${named.groupValues[1]}","arguments":${json.second}}""")
        } else {
          val json = JsonRepair.balanced(tail) ?: return out
          Pair(bodyStart + json.first.last + 1, json.second)
        }
      if (take(body)) {
        out = out.substring(0, at) + out.substring(consumedEnd)
        from = at
      } else {
        from = bodyStart
      }
    }
  }

  /** Older Granite output: `<|tool_call|>` then a JSON array, with no closing tag. */
  private fun extractUnclosedGranite(text: String, take: (String) -> Boolean): String {
    val marker = "<|tool_call|>"
    val at = text.indexOf(marker)
    if (at < 0) return text
    val json = JsonRepair.balanced(text, at + marker.length) ?: return text
    return if (take(json.second)) text.substring(0, at) + text.substring(json.first.last + 1) else text
  }

  /** Calls in one block body: JSON object, JSON array of objects, or Pythonic `fn(a=1)`. */
  fun callsIn(body: String, newId: () -> String): List<ParsedToolCall> {
    val t = body.trim()
    val json = JsonRepair.parse(t)
    if (json != null) {
      val items = if (json is JsonArray) json.toList() else listOf(json)
      val calls = items.mapNotNull { (it as? JsonObject)?.let { o -> fromObject(o, newId) } }
      return if (calls.size == items.size) calls else emptyList()
    }
    return PythonicCallParser.parse(t)?.map { (name, args) -> ParsedToolCall(newId(), name, args.toString()) }.orEmpty()
  }

  private fun fromObject(obj: JsonObject, newId: () -> String): ParsedToolCall? {
    val fn = obj["function"] as? JsonObject
    val name =
      (fn?.get("name") ?: obj["name"] ?: obj["function"] ?: obj["tool_name"] ?: obj["tool"]).asText()?.takeIf { it.isNotBlank() } ?: return null
    val args = fn?.get("arguments") ?: obj["arguments"] ?: obj["parameters"] ?: obj["args"] ?: obj["input"]
    val json = normalizeArguments(args) ?: return null
    return ParsedToolCall(newId(), name, json)
  }

  private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  /**
   * [args] as a JSON object string. Strings holding JSON are unwrapped and repaired; a missing
   * value becomes `{}`; anything that is not an object returns null.
   */
  fun normalizeArguments(args: JsonElement?): String? =
    when (args) {
      null, JsonNull -> "{}"
      is JsonObject -> args.toString()
      is JsonPrimitive -> if (args.isString) normalizeArguments(args.content) else null
      else -> null
    }

  /** Same as above for text coming from a runtime that already split name and arguments. */
  fun normalizeArguments(text: String): String? {
    if (text.isBlank()) return "{}"
    return (JsonRepair.parse(text) as? JsonObject)?.toString()
  }
}
