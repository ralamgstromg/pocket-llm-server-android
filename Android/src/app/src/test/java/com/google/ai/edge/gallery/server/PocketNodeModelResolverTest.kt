package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import org.junit.Assert.assertEquals
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
}
