package com.google.ai.edge.gallery.server.whisper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTokenizerTest {

    // Reads the bundled asset directly; Gradle runs unit tests with the module as working directory.
    private val tokenizer = WhisperTokenizer(
        File("src/main/assets/whisper/tokenizer.json").readText()
    )

    @Test
    fun specialTokenIds_matchLargeV3Vocabulary() {
        assertEquals(50360, tokenizer.idOf("<|transcribe|>"))
        assertEquals(50364, tokenizer.idOf("<|notimestamps|>"))
        assertEquals(50259, tokenizer.idOf("<|en|>"))
        assertEquals(50262, tokenizer.idOf("<|es|>"))
        assertEquals(50258, WhisperTokenizer.SOT)
        assertEquals(50257, WhisperTokenizer.EOT)
    }

    @Test
    fun languageIds_coverAllLanguagesOnly() {
        assertEquals(50259..50358, tokenizer.languageIds)
    }

    @Test
    fun decode_joinsByteLevelTokensAndSkipsSpecials() {
        val ids = intArrayOf(
            286, 362, 257, 3055, 300, 472, 786, 341, 4790, 486,
            6272, 493, 293, 1621, 484, 264, 2074, 3620, 295, 1080,
            tokenizer.idOf("<|transcribe|>"), WhisperTokenizer.EOT,
        )
        assertEquals(
            " I have a dream that one day this nation will rise up and live out the true meaning of its",
            tokenizer.decode(ids),
        )
    }
}
