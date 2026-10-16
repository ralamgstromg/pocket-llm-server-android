package com.google.ai.edge.gallery.server.whisper

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decode-only byte-level BPE tokenizer for Whisper, read from the `tokenizer.json` of
 * openai/whisper-large-v3-turbo (the LiteRT repo does not ship one).
 */
class WhisperTokenizer(json: String) {
    private val tokensById: Array<String?>
    private val idsByToken: Map<String, Int>

    /** Ids of the language tokens (<|en|>, <|es|>, ...). */
    val languageIds: IntRange

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        val vocab = root.getValue("model").jsonObject.getValue("vocab").jsonObject
        val added = root.getValue("added_tokens").jsonArray

        val entries = HashMap<String, Int>()
        for ((token, id) in vocab) entries[token] = id.jsonPrimitive.int
        for (token in added) {
            val obj = token.jsonObject
            entries[obj.getValue("content").jsonPrimitive.content] = obj.getValue("id").jsonPrimitive.int
        }

        idsByToken = entries
        tokensById = arrayOfNulls(entries.values.max() + 1)
        for ((token, id) in entries) tokensById[id] = token

        languageIds = (SOT + 1) until idOf("<|translate|>")
    }

    fun idOf(token: String): Int =
        idsByToken[token] ?: throw IllegalArgumentException("Token de Whisper no encontrado: $token")

    /** Decodes ids to text, skipping special tokens (ids >= [EOT]). */
    fun decode(ids: IntArray): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (id >= EOT) continue
            val token = tokensById.getOrNull(id) ?: continue
            for (ch in token) {
                bytes.write(UNICODE_TO_BYTE[ch] ?: continue)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258

        /** GPT-2 byte-to-unicode table inverted: each printable char maps back to one byte. */
        private val UNICODE_TO_BYTE: Map<Char, Int> = run {
            val direct = (33..126) + (161..172) + (174..255)
            val map = HashMap<Char, Int>()
            for (b in direct) map[b.toChar()] = b
            var extra = 0
            for (b in 0..255) {
                if (b !in direct) {
                    map[(256 + extra).toChar()] = b
                    extra++
                }
            }
            map
        }
    }
}
