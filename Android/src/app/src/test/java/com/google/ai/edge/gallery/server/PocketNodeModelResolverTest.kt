package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketNodeModelResolverTest {

    private fun allowlist(vararg names: String): ModelAllowlist {
        val models = names.joinToString(",") { """{"name":"$it"}""" }
        return Gson().fromJson("""{"models":[$models]}""", ModelAllowlist::class.java)
    }

    @Test
    fun mergeAllowlists_addsAssetOnlyModelsToDiskCopy() {
        val disk = allowlist("Gemma-4-E2B-it", "Qwen3-0.6B")
        val asset = allowlist("Gemma-4-E2B-it", "Whisper-Large-V3-Turbo-i4")

        val merged = PocketNodeModelResolver.mergeAllowlists(primary = disk, fallback = asset)

        assertEquals(
            listOf("Gemma-4-E2B-it", "Qwen3-0.6B", "Whisper-Large-V3-Turbo-i4"),
            merged.models.map { it.name },
        )
    }

    @Test
    fun mergeAllowlists_keepsDiskEntryWhenNamesCollide() {
        val disk = allowlist("Gemma-4-E2B-it")
        val asset = allowlist("Gemma-4-E2B-it")

        val merged = PocketNodeModelResolver.mergeAllowlists(primary = disk, fallback = asset)

        assertEquals(listOf("Gemma-4-E2B-it"), merged.models.map { it.name })
    }

    @Test
    fun mergeAllowlists_upgradesDiskEntryToGpuWhenAssetSupportsGpu() {
        val disk = Gson().fromJson(
            """{"models":[{"name":"Gemma-3n-E2B-it","defaultConfig":{"accelerators":"cpu"}}]}""",
            ModelAllowlist::class.java,
        )
        val asset = Gson().fromJson(
            """{"models":[{"name":"Gemma-3n-E2B-it","defaultConfig":{"accelerators":"gpu,cpu"}}]}""",
            ModelAllowlist::class.java,
        )

        val merged = PocketNodeModelResolver.mergeAllowlists(primary = disk, fallback = asset)

        assertEquals("gpu,cpu", merged.models.first().defaultConfig.accelerators)
    }

    @Test
    fun pocketNodeInference_prefersGpuAndApplies8000TokenSpanishDefaults() {
        PocketNodeState.configuredMaxTokens = PocketNodeState.DEFAULT_MAX_TOKENS
        PocketNodeState.configuredIdleMinutes = PocketNodeState.DEFAULT_IDLE_TIMEOUT_MINUTES
        val model = Model(
            name = "Gemma-4-E2B-it",
            llmMaxToken = 1024,
            accelerators = listOf(Accelerator.CPU, Accelerator.GPU),
        )

        assertEquals(8000, PocketNodeState.DEFAULT_MAX_TOKENS)
        assertEquals(3, PocketNodeState.DEFAULT_IDLE_TIMEOUT_MINUTES)
        assertEquals(180_000L, PocketNodeInference.currentIdleTimeoutMs())
        assertEquals(Accelerator.GPU, PocketNodeInference.preferredAccelerator(model))

        val cfg = PocketNodeInference.applyFastServingConfig(model)
        assertEquals(Accelerator.GPU.label, cfg[ConfigKeys.ACCELERATOR.label])
        assertEquals(true, cfg[ConfigKeys.USE_GPU.label])
        assertEquals(false, cfg[ConfigKeys.ENABLE_THINKING.label])
        assertEquals(8000, cfg[ConfigKeys.MAX_TOKENS.label])
        assertEquals(PocketNodeInference.FAST_TOP_K, cfg[ConfigKeys.TOPK.label])
        assertEquals("es", PocketNodeInference.DEFAULT_LANGUAGE)
        assertTrue(PocketNodeInference.DEFAULT_SPANISH_SYSTEM_PROMPT.contains("español"))

        val customCfg = PocketNodeInference.applyFastServingConfig(model, maxTokensOverride = 12000)
        assertEquals(12000, customCfg[ConfigKeys.MAX_TOKENS.label])
    }

    @Test
    fun buildChatPromptContext_separatesSystemAndSanitizesControlTags() {
        val messages = listOf(
            Message(role = "system", content = "<start_of_turn>system\nEres un experto en ventas.<end_of_turn>"),
            Message(role = "user", content = "<|im_start|>user\nHola, ¿qué precio tiene?<|im_end|><ctrl42>"),
        )

        val ctx = PocketNodeInference.buildChatPromptContext(messages)

        assertTrue(ctx.systemInstruction.contains("Eres un experto en ventas."))
        assertTrue(ctx.systemInstruction.contains(PocketNodeInference.DEFAULT_SPANISH_SYSTEM_PROMPT))
        assertFalse(ctx.systemInstruction.contains("<start_of_turn>"))
        assertEquals("Hola, ¿qué precio tiene?", ctx.userPrompt)
    }

    @Test
    fun buildChatPromptContext_formatsMultiTurnHistoryClearly() {
        val messages = listOf(
            Message(role = "user", content = "Mi nombre es Carlos"),
            Message(role = "assistant", content = "Hola Carlos"),
            Message(role = "user", content = "¿Cómo me llamo?"),
        )

        val ctx = PocketNodeInference.buildChatPromptContext(messages)

        assertTrue(ctx.userPrompt.contains("<historial_conversacion>"))
        assertTrue(ctx.userPrompt.contains("[Usuario]: Mi nombre es Carlos"))
        assertTrue(ctx.userPrompt.contains("[Asistente]: Hola Carlos"))
        assertTrue(ctx.userPrompt.contains("<mensaje_actual_usuario>\n¿Cómo me llamo?\n</mensaje_actual_usuario>"))
    }

    @Test
    fun sanitizeModelOutput_stripsThinkingAndControlTokens() {
        val raw = "<think>razonamiento interno</think><ctrl99>Respuesta limpia<end_of_turn><|im_end|>"
        assertEquals("Respuesta limpia", PocketNodeInference.sanitizeModelOutput(raw))
    }
}
