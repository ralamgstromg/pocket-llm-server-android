package com.google.ai.edge.gallery.server

import androidx.compose.runtime.mutableStateMapOf

/** What a served model is used for. Each role has its own configurable slot. */
enum class ModelRole(val key: String) {
  /** `/v1/chat/completions` and `/v1/responses`. */
  CHAT("chat"),
  /** Legacy `/v1/completions`. Falls back to [CHAT] when empty. */
  COMPLETIONS("completions"),
  /** `/v1/audio/transcriptions` and `/v1/audio/translations`. Needs an audio-capable model. */
  STT("stt"),
  /** Chat requests that carry images. Falls back to [CHAT] when empty. */
  VISION("vision"),
  /** Small model that picks the model for a request when the client sends `model: "auto"`. */
  ROUTER("router"),
}

/** Minimal key/value storage so the slot logic runs in JVM tests. */
interface SlotStore {
  fun get(key: String): String?

  fun put(key: String, value: String)
}

/** Which model name is configured for each [ModelRole], plus client-name aliases. */
class ModelSlots(private val store: SlotStore) {
  val values = mutableStateMapOf<ModelRole, String>()
  private var aliasText = ""

  init {
    reload()
  }

  fun reload() {
    ModelRole.entries.forEach { role -> values[role] = store.get(prefKey(role)).orEmpty() }
    // Migrate the two pre-slot preferences once.
    if (values[ModelRole.CHAT].isNullOrEmpty()) store.get(LEGACY_CHAT)?.takeIf { it.isNotEmpty() }?.let { set(ModelRole.CHAT, it) }
    if (values[ModelRole.STT].isNullOrEmpty()) store.get(LEGACY_AUDIO)?.takeIf { it.isNotEmpty() }?.let { set(ModelRole.STT, it) }
    aliasText = store.get(KEY_ALIASES).orEmpty()
  }

  /** Configured model name for [role], or "" when unset. No implicit fallback. */
  fun get(role: ModelRole): String = values[role].orEmpty()

  /** Name to use for [role]: its own slot, else the documented fallback role, else "". */
  fun effective(role: ModelRole): String =
    get(role).ifEmpty {
      when (role) {
        ModelRole.COMPLETIONS,
        ModelRole.VISION -> get(ModelRole.CHAT)
        else -> ""
      }
    }

  fun set(role: ModelRole, modelName: String) {
    values[role] = modelName
    store.put(prefKey(role), modelName)
  }

  /** Aliases as editable text, one `alias=model` per line. */
  fun aliasesText(): String = aliasText

  fun setAliasesText(text: String) {
    aliasText = text
    store.put(KEY_ALIASES, text)
  }

  fun aliases(): Map<String, String> = parseAliases(aliasText)

  companion object {
    private const val LEGACY_CHAT = "preferred_chat_model"
    private const val LEGACY_AUDIO = "preferred_audio_model"
    private const val KEY_ALIASES = "model_aliases"

    private fun prefKey(role: ModelRole) = "slot_${role.key}"

    fun parseAliases(text: String): Map<String, String> =
      text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { line -> line.substringBefore('=').trim().lowercase() to line.substringAfter('=').trim() }
        .filterKeys { it.isNotEmpty() }
  }
}

/** Matches the `model` field of a request against the known models. */
object ModelMatching {
  private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

  /**
   * Exact name or file name (case-insensitive), then a configured alias, then the same ignoring
   * punctuation ("qwen3.5-4b" == "Qwen3.5 4B"). Never matches by substring, so one model name can
   * not hijack requests meant for another.
   */
  fun <T> find(items: List<T>, query: String, aliases: Map<String, String>, name: (T) -> String, fileName: (T) -> String): T? {
    val q = query.trim()
    if (q.isEmpty()) return null
    fun exact(target: String) = items.find { name(it).equals(target, true) || fileName(it).equals(target, true) }
    exact(q)?.let { return it }
    aliases[q.lowercase()]?.let { target -> exact(target)?.let { return it } }
    val nq = normalize(q)
    return if (nq.isEmpty()) null else items.find { normalize(name(it)) == nq || normalize(fileName(it)) == nq }
  }
}

/** Builds the prompt for the router model and reads its answer. */
object ModelRouterPrompt {
  const val AUTO = "auto"

  fun build(candidates: List<String>, userMessage: String): String =
    buildString {
      appendLine("Pick the best model for the request below. Answer with exactly one name from the list and nothing else.")
      appendLine("Models:")
      candidates.forEach { appendLine("- $it") }
      appendLine()
      appendLine("Request:")
      append(userMessage.take(1_500))
    }

  /** The candidate named in [answer], or null when the router did not name one. */
  fun parse(answer: String, candidates: List<String>): String? {
    val cleaned = answer.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    return candidates.find { cleaned.equals(it, true) }
      ?: candidates.filter { cleaned.contains(it, true) }.maxByOrNull { it.length }
  }
}
