package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.ai.edge.gallery.data.Model

/** One handled HTTP request, shown in the server's activity tab. */
data class RequestLogEntry(
  val id: String = "",
  val timeMillis: Long,
  val method: String,
  val path: String,
  val status: Int,
  val durationMs: Long,
  val model: String? = null,
  val clientIp: String? = null,
  val error: String? = null,
)

object PocketNodeState {
  private const val TAG = "PocketNodeState"
  private const val PREFS_NAME = "pocket_node_prefs"
  private const val SECRET_PREFS_NAME = "pocket_node_secret_prefs"
  private const val KEY_API_KEY = "api_key"
  private const val KEY_CORS_ENABLED = "cors_enabled"
  private const val KEY_PORT = "port"
  private const val KEY_TTS_LANGUAGE = "tts_language"
  private const val KEY_TTS_RATE = "tts_rate"
  private const val KEY_CHAT_MODEL = "preferred_chat_model"
  private const val KEY_AUDIO_MODEL = "preferred_audio_model"
  private const val MAX_LOG_ENTRIES = 100

  const val DEFAULT_PORT = 8080

  /** Models loaded by the in-app chat screens; used only as a last resort when no slot is set. */
  var activeChatModel: Model? = null
  var activeAudioModel: Model? = null

  // Backward compatibility for older code that still reads `activeModel`.
  var activeModel: Model?
    get() = activeChatModel ?: activeAudioModel
    set(value) {
      activeChatModel = value
    }

  // Observable Compose state for reactive UI updates.
  var isServerRunning by mutableStateOf(false)
  var lastStartupError by mutableStateOf<String?>(null)

  var port by mutableStateOf(DEFAULT_PORT)
    private set

  /** Bearer token required by the HTTP API. Empty disables authentication. */
  var apiKey by mutableStateOf("")
    private set

  /** Whether browsers on other origins may call the API (CORS). */
  var corsEnabled by mutableStateOf(false)
    private set

  /** BCP-47 tag for `/v1/audio/speech`; empty uses the device language. */
  var ttsLanguage by mutableStateOf("")
    private set

  /** Speech rate multiplier for `/v1/audio/speech` (1.0 = normal). */
  var ttsRate by mutableStateOf(1.0f)
    private set

  var slots: ModelSlots? = null
    private set

  /** Newest first. Written from server threads, read by Compose. */
  val requestLog = mutableStateListOf<RequestLogEntry>()

  fun logRequest(entry: RequestLogEntry) {
    synchronized(requestLog) {
      requestLog.add(0, entry)
      while (requestLog.size > MAX_LOG_ENTRIES) requestLog.removeAt(requestLog.size - 1)
    }
  }

  fun clearLog() = synchronized(requestLog) { requestLog.clear() }

  private fun plainPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** API key storage: encrypted with the Android Keystore; plain prefs only if the keystore fails. */
  private fun securePrefs(context: Context): SharedPreferences =
    try {
      EncryptedSharedPreferences.create(
        context,
        SECRET_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Keystore unavailable; API key falls back to unencrypted preferences", e)
      plainPrefs(context)
    }

  fun loadPreferences(context: Context) {
    val prefs = plainPrefs(context)
    val secure = securePrefs(context)

    // Move key saved by an older version into the encrypted store.
    if (secure !== prefs && prefs.contains(KEY_API_KEY)) {
      secure.edit()
        .putString(KEY_API_KEY, prefs.getString(KEY_API_KEY, "").orEmpty())
        .apply()
      prefs.edit().remove(KEY_API_KEY).apply()
    }

    apiKey = secure.getString(KEY_API_KEY, "").orEmpty()
    corsEnabled = prefs.getBoolean(KEY_CORS_ENABLED, false)
    port = prefs.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    ttsLanguage = prefs.getString(KEY_TTS_LANGUAGE, "").orEmpty()
    ttsRate = prefs.getFloat(KEY_TTS_RATE, 1.0f).coerceIn(0.25f, 4.0f)

    val existing = slots
    if (existing != null) {
      existing.reload()
    } else {
      slots =
        ModelSlots(
          object : SlotStore {
            override fun get(key: String): String? = prefs.getString(key, null)

            override fun put(key: String, value: String) =
              prefs.edit().putString(key, value).apply()
          },
        )
    }

    val chatName = slots?.get(ModelRole.CHAT).orEmpty().ifEmpty { prefs.getString(KEY_CHAT_MODEL, "").orEmpty() }
    val audioName = slots?.get(ModelRole.STT).orEmpty().ifEmpty { prefs.getString(KEY_AUDIO_MODEL, "").orEmpty() }
    val all = PocketNodeModelResolver.getAllModels(context)
    if (chatName.isNotEmpty()) {
      activeChatModel = all.find { it.name.equals(chatName, ignoreCase = true) }
    }
    if (audioName.isNotEmpty()) {
      activeAudioModel = all.find { it.name.equals(audioName, ignoreCase = true) }
    }
  }

  fun setRoleModel(context: Context, role: ModelRole, modelName: String) {
    if (slots == null) loadPreferences(context)
    slots?.set(role, modelName)
    val all = PocketNodeModelResolver.getAllModels(context)
    val target = all.find { it.name.equals(modelName, ignoreCase = true) }
    if (role == ModelRole.CHAT) {
      activeChatModel = target
      plainPrefs(context).edit().putString(KEY_CHAT_MODEL, modelName).apply()
    } else if (role == ModelRole.STT) {
      activeAudioModel = target
      plainPrefs(context).edit().putString(KEY_AUDIO_MODEL, modelName).apply()
    }
  }

  fun saveSecurityPreferences(context: Context, newApiKey: String, newCorsEnabled: Boolean) {
    apiKey = newApiKey.trim()
    corsEnabled = newCorsEnabled
    securePrefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
    plainPrefs(context).edit().putBoolean(KEY_CORS_ENABLED, corsEnabled).apply()
  }

  fun savePort(context: Context, newPort: Int) {
    port = newPort.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    plainPrefs(context).edit().putInt(KEY_PORT, port).apply()
  }

  fun saveTtsPreferences(context: Context, language: String, rate: Float) {
    ttsLanguage = language.trim()
    ttsRate = rate.coerceIn(0.25f, 4.0f)
    plainPrefs(context).edit().putString(KEY_TTS_LANGUAGE, ttsLanguage).putFloat(KEY_TTS_RATE, ttsRate).apply()
  }

  /** Backward-compatibility: older server code expected this. */
  fun syncSharedModels() {
    // No-op: slot-based selection no longer aliases chat/audio.
    // We keep the method so older PocketNodeServer versions still compile.
  }
}
