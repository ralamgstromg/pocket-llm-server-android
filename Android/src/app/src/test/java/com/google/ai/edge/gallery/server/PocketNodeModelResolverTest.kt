package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import org.junit.Assert.assertEquals
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
    fun pocketNodeInference_prefersGpuAndAppliesFastSpanishDefaults() {
        val model = Model(
            name = "Gemma-4-E2B-it",
            llmMaxToken = 4000,
            accelerators = listOf(Accelerator.CPU, Accelerator.GPU),
        )

        assertEquals(Accelerator.GPU, PocketNodeInference.preferredAccelerator(model))

        val cfg = PocketNodeInference.applyFastServingConfig(model)
        assertEquals(Accelerator.GPU.label, cfg[ConfigKeys.ACCELERATOR.label])
        assertEquals(true, cfg[ConfigKeys.USE_GPU.label])
        assertEquals(false, cfg[ConfigKeys.ENABLE_THINKING.label])
        assertEquals(PocketNodeInference.FAST_MAX_TOKENS, cfg[ConfigKeys.MAX_TOKENS.label])
        assertEquals(PocketNodeInference.FAST_TOP_K, cfg[ConfigKeys.TOPK.label])
        assertEquals("es", PocketNodeInference.DEFAULT_LANGUAGE)
        assertTrue(PocketNodeInference.DEFAULT_SPANISH_SYSTEM_PROMPT.contains("español"))
    }
}
