package com.google.ai.edge.gallery.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.customtasks.agentchat.ChatWebViewClient
import com.google.ai.edge.gallery.customtasks.agentchat.ChatWebViewJavascriptInterface
import com.google.ai.edge.gallery.customtasks.agentchat.getSkillSecretKey
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.server.openai.FunctionDefinition
import com.google.ai.edge.gallery.server.openai.OpenAiJson
import com.google.ai.edge.gallery.server.openai.ToolDto
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "PocketNodeSkillExecutor"

/** Outcome of running one skill tool. */
data class SkillToolOutcome(val resultJson: String, val isError: Boolean = false)

/** Runs the two skill tools (`load_skill` and `run_skill_js`) on behalf of the server. */
interface SkillExecutor {
  fun loadSkill(name: String): SkillToolOutcome

  suspend fun runJs(name: String, scriptName: String, data: String): SkillToolOutcome
}

/** Describes the skills currently selected and builds the tools injected into the request. */
class SkillCatalog(private val context: Context) {
  private val pbFile = File(context.filesDir, "datastore/skills.pb")

  fun all(): List<Skill> {
    if (!pbFile.exists()) return emptyList()
    return try {
      pbFile.inputStream().use { com.google.ai.edge.gallery.proto.Skills.parseFrom(it) }.skillList
    } catch (e: Exception) {
      Log.w(TAG, "Failed reading skills: ${e.message}")
      emptyList()
    }
  }

  fun selected(): List<Skill> = all().filter { it.selected }

  fun find(name: String): Skill? = all().find { it.name.equals(name, ignoreCase = true) }

  /** Toggles the selection state in the DataStore pb file so it reflects across the app. */
  fun setSelected(name: String, selected: Boolean): Boolean {
    if (!pbFile.exists()) return false
    return try {
      val skills = pbFile.inputStream().use { com.google.ai.edge.gallery.proto.Skills.parseFrom(it) }
      var found = false
      val updated =
        skills.skillList.map { s ->
          if (s.name.equals(name, ignoreCase = true)) {
            found = true
            s.toBuilder().setSelected(selected).build()
          } else s
        }
      if (!found) return false
      val tmp = File(pbFile.parentFile, "skills.pb.tmp")
      tmp.outputStream().use { com.google.ai.edge.gallery.proto.Skills.newBuilder().addAllSkill(updated).build().writeTo(it) }
      tmp.renameTo(pbFile)
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed setting skill selected: ${e.message}")
      false
    }
  }

  fun promptSnippet(skills: List<Skill>): String =
    "You have access to the following skills:\n" +
      skills.joinToString("\n") { "- ${it.name}: ${it.description}" } +
      "\nTo inspect a skill before using it, call 'load_skill' with its name. " +
      "To run one, call 'run_skill_js' with skill name, script name (usually 'index.html') and JSON input data."

  fun tools(): List<ToolDto> = listOf(LOAD_SKILL_TOOL, RUN_JS_TOOL)

  companion object {
    val LOAD_SKILL_TOOL =
      ToolDto(
        type = "function",
        function =
          FunctionDefinition(
            name = "load_skill",
            description = "Loads instructions and documentation for a skill.",
            parameters =
              buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                  putJsonObject("skill_name") {
                    put("type", "string")
                    put("description", "Name of the skill to load.")
                  }
                }
                put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("skill_name")) })
              },
          ),
      )

    val RUN_JS_TOOL =
      ToolDto(
        type = "function",
        function =
          FunctionDefinition(
            name = "run_skill_js",
            description = "Runs the JavaScript script of a skill and returns its output.",
            parameters =
              buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                  putJsonObject("skill_name") { put("type", "string"); put("description", "Name of the skill.") }
                  putJsonObject("script_name") { put("type", "string"); put("description", "Script file name, usually 'index.html'.") }
                  putJsonObject("data") { put("type", "string"); put("description", "JSON string of input arguments for the skill.") }
                }
                put(
                  "required",
                  kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("skill_name"))
                    add(kotlinx.serialization.json.JsonPrimitive("script_name"))
                  },
                )
              },
          ),
      )
  }
}

/** Android implementation: runs headless on the main thread inside a temporary WebView. */
class AndroidSkillExecutor(private val context: Context, private val catalog: SkillCatalog) : SkillExecutor {
  override fun loadSkill(name: String): SkillToolOutcome {
    val skill = catalog.find(name) ?: return SkillToolOutcome("""{"error":"Skill '$name' not found."}""", isError = true)
    val body = "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.instructions}"
    val json = kotlinx.serialization.json.buildJsonObject {
      put("skill_name", skill.name)
      put("instructions", body)
    }
    return SkillToolOutcome(json.toString())
  }

  override suspend fun runJs(name: String, scriptName: String, data: String): SkillToolOutcome {
    val skill = catalog.find(name) ?: return SkillToolOutcome("""{"error":"Skill '$name' not found."}""", isError = true)
    if (skill.requireSecret) {
      val secret = readSecret(skill.name)
      if (secret.isNullOrEmpty()) {
        return SkillToolOutcome("""{"error":"Skill '${skill.name}' requires an API key/secret. Enter it in the Gallery app first."}""", isError = true)
      }
    }
    val url = scriptUrl(skill, scriptName) ?: return SkillToolOutcome("""{"error":"Could not resolve URL for script '$scriptName' in skill '$name'."}""", isError = true)
    val secret = readSecret(skill.name).orEmpty()
    val cleanData = data.trim().ifEmpty { "{}" }

    return withTimeoutOrNull(60_000L) {
      withContext(Dispatchers.Main) {
        val webView = WebView(context)
        val jsInterface = ChatWebViewJavascriptInterface()
        val client = ChatWebViewClient(context)
        webView.settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          allowFileAccess = true
        }
        webView.addJavascriptInterface(jsInterface, "AiEdgeGallery")
        webView.webViewClient = client

        try {
          suspendCoroutine { cont ->
            client.setPageLoadListener {
              client.setPageLoadListener(null)
              cont.resume(Unit)
            }
            webView.loadUrl(url)
          }

          val result =
            suspendCancellableCoroutine<String> { cont ->
              jsInterface.onResultListener = { out -> if (cont.isActive) cont.resume(out) }
              val script =
                """
                (async function() {
                  var startTs = Date.now();
                  while (typeof ai_edge_gallery_get_result !== 'function') {
                    await new Promise(r => setTimeout(r, 100));
                    if (Date.now() - startTs > 10000) break;
                  }
                  var res = await ai_edge_gallery_get_result(`$cleanData`, `$secret`);
                  AiEdgeGallery.onResultReady(typeof res === 'string' ? res : JSON.stringify(res));
                })()
                """.trimIndent()
              webView.evaluateJavascript(script, null)
            }
          SkillToolOutcome(result)
        } finally {
          runCatching { webView.destroy() }
        }
      }
    } ?: SkillToolOutcome("""{"error":"Skill execution timed out after 60s."}""", isError = true)
  }

  private fun readSecret(skillName: String): String? =
    try {
      context.getSharedPreferences("app_settings_data_store", Context.MODE_PRIVATE).getString(getSkillSecretKey(skillName), null)
    } catch (e: Exception) {
      null
    }

  private fun scriptUrl(skill: Skill, scriptName: String): String? =
    when {
      skill.importDirName.isNotEmpty() -> "$LOCAL_URL_BASE/${skill.importDirName}/scripts/$scriptName"
      skill.skillUrl.isNotEmpty() -> "${skill.skillUrl}/scripts/$scriptName"
      else -> null
    }
}
