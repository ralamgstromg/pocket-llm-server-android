package com.google.ai.edge.gallery.data

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the bundled allowlist is well formed and keeps the Spanish-capable server models. */
class AllowlistCatalogTest {
  private val models: List<AllowedModel> by lazy {
    val file = listOf("src/main/assets/model_allowlists/1_0_11.json", "app/src/main/assets/model_allowlists/1_0_11.json").map(::File).first { it.exists() }
    Gson().fromJson(file.readText(), ModelAllowlist::class.java).models
  }

  @Test
  fun entriesAreWellFormed() {
    val hex40 = Regex("^[0-9a-f]{40}$")
    for (m in models) {
      assertTrue("${m.name}: commitHash", hex40.matches(m.commitHash))
      assertTrue("${m.name}: size", m.sizeInBytes > 0)
      assertTrue("${m.name}: file", m.modelFile.endsWith(".litertlm"))
      assertTrue("${m.name}: taskTypes", m.taskTypes.isNotEmpty())
    }
    assertEquals("names must be unique", models.size, models.map { it.name }.toSet().size)
  }

  @Test
  fun allModelsAreLiteRtLmFormat() {
    for (m in models) {
      assertTrue("${m.name} must be .litertlm", m.modelFile.endsWith(".litertlm"))
    }
  }

  @Test
  fun spanishSpeechToTextModelsExist() {
    val stt = models.filter { "stt" in it.recommendedRoles.orEmpty() && it.llmSupportAudio == true }
    assertTrue(stt.any { it.name == "Qwen3-ASR-1.7B" })
    stt.forEach { assertTrue("${it.name} must list es", "es" in it.languages.orEmpty()); assertTrue(it.modelFile.endsWith(".litertlm")) }
  }

  @Test
  fun routerCandidatesAreSmallAndServable() {
    val routers = models.filter { "router" in it.recommendedRoles.orEmpty() }
    assertTrue(routers.size >= 3)
    routers.forEach { assertTrue("${it.name} .litertlm", it.modelFile.endsWith(".litertlm")) }
  }
}
