package com.google.ai.edge.gallery.server

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Full-screen control panel of the Pocket Node server: status, models, security and activity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketNodeServerScreen(modelManagerViewModel: ModelManagerViewModel, onDismissRequest: () -> Unit) {
  val context = LocalContext.current
  // Makes the model list follow downloads made while this screen is open.
  modelManagerViewModel.uiState.collectAsState().value
  LaunchedEffect(Unit) { PocketNodeState.loadPreferences(context) }
  var tab by remember { mutableIntStateOf(0) }
  val titles =
    listOf(R.string.pocket_node_tab_status, R.string.pocket_node_tab_models, R.string.pocket_node_tab_security, R.string.pocket_node_tab_activity)

  Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        Column {
          TopAppBar(
            title = { Text(stringResource(R.string.pocket_node_title)) },
            actions = {
              IconButton(onClick = onDismissRequest) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.pocket_node_close))
              }
            },
          )
          PrimaryTabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
          }
        }
      },
    ) { padding ->
      Box(Modifier.padding(padding).fillMaxSize()) {
        when (tab) {
          0 -> StatusTab()
          1 -> ModelsTab(modelManagerViewModel)
          2 -> SecurityTab()
          else -> ActivityTab()
        }
      }
    }
  }
}

@Composable
private fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      content()
    }
  }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun copyToClipboard(): (String) -> Unit {
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val done = stringResource(R.string.pocket_node_copied)
  return { text ->
    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("pocket-node", text))) }
    Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
  }
}

// ---------------------------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatusTab() {
  val context = LocalContext.current
  val running = PocketNodeState.isServerRunning
  val copy = copyToClipboard()
  var starting by remember { mutableStateOf(false) }
  var portText by remember(PocketNodeState.port) { mutableStateOf(PocketNodeState.port.toString()) }
  var portError by remember { mutableStateOf(false) }
  val ip = remember(running) { NetworkAddress.lanIp() }
  val baseUrl = "http://${ip ?: "localhost"}:${PocketNodeState.port}/v1"
  val allModels = remember(running) { PocketNodeModelResolver.getAllModels(context) }
  val loaded = allModels.filter { it.instance != null }
  val freeRam = remember(running, loaded.size) { Formatter.formatShortFileSize(context, availableMemory(context)) }
  LaunchedEffect(running) { if (running) starting = false }
  LaunchedEffect(PocketNodeState.lastStartupError) { if (PocketNodeState.lastStartupError != null) starting = false }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SectionCard(stringResource(if (running) R.string.pocket_node_running else R.string.pocket_node_stopped)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (running) R.string.pocket_node_running else R.string.pocket_node_stopped))
      }
      PocketNodeState.lastStartupError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      Button(
        enabled = !starting,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        onClick = {
          val intent = Intent(context, PocketNodeService::class.java)
          if (running) {
            context.stopService(intent)
          } else {
            starting = true
            PocketNodeState.lastStartupError = null
            try {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
              starting = false
              PocketNodeState.lastStartupError = e.message
            }
          }
        },
      ) {
        if (starting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(stringResource(if (running) R.string.pocket_node_stop_server else R.string.pocket_node_start))
      }
      if (!running) Hint(stringResource(R.string.pocket_node_start_hint))
    }

    SectionCard(stringResource(R.string.pocket_node_address)) {
      SelectionContainer { Text(baseUrl, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
      OutlinedButton(onClick = { copy(baseUrl) }) { Text(stringResource(R.string.pocket_node_copy)) }
      if (ip == null) Hint(stringResource(R.string.pocket_node_no_lan))
      OutlinedTextField(
        value = portText,
        onValueChange = {
          portText = it.filter(Char::isDigit).take(5)
          val value = portText.toIntOrNull()
          portError = value == null || value !in 1024..65535
          if (!portError && value != null) PocketNodeState.savePort(context, value)
        },
        label = { Text(stringResource(R.string.pocket_node_port)) },
        isError = portError,
        supportingText = { Text(stringResource(if (portError) R.string.pocket_node_port_invalid else R.string.pocket_node_port_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    SectionCard(stringResource(R.string.pocket_node_loaded_models)) {
      if (loaded.isEmpty()) Text(stringResource(R.string.pocket_node_none)) else loaded.forEach { Text("• ${it.name}") }
      Hint(stringResource(R.string.pocket_node_free_ram, freeRam))
    }

    val curl = quickTestCommand(baseUrl, PocketNodeState.apiKey, PocketNodeState.slots?.effective(ModelRole.CHAT).orEmpty())
    SectionCard(stringResource(R.string.pocket_node_quick_test)) {
      Hint(stringResource(R.string.pocket_node_quick_test_hint))
      SelectionContainer { Text(curl, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
      OutlinedButton(onClick = { copy(curl) }) { Text(stringResource(R.string.pocket_node_copy)) }
    }
  }
}

private fun quickTestCommand(baseUrl: String, apiKey: String, model: String): String {
  val auth = if (apiKey.isEmpty()) "" else " \\\n  -H \"Authorization: Bearer $apiKey\""
  val name = model.ifEmpty { "auto" }
  return "curl $baseUrl/chat/completions \\\n  -H \"Content-Type: application/json\"$auth \\\n  -d '{\"model\":\"$name\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"
}

private fun availableMemory(context: Context): Long {
  val info = ActivityManager.MemoryInfo()
  (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
  return info.availMem
}

// ---------------------------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------------------------

private data class RoleUi(val role: ModelRole, val title: Int, val desc: Int)

private val ROLE_UI =
  listOf(
    RoleUi(ModelRole.CHAT, R.string.pocket_node_role_chat, R.string.pocket_node_role_chat_desc),
    RoleUi(ModelRole.COMPLETIONS, R.string.pocket_node_role_completions, R.string.pocket_node_role_completions_desc),
    RoleUi(ModelRole.STT, R.string.pocket_node_role_stt, R.string.pocket_node_role_stt_desc),
    RoleUi(ModelRole.VISION, R.string.pocket_node_role_vision, R.string.pocket_node_role_vision_desc),
    RoleUi(ModelRole.ROUTER, R.string.pocket_node_role_router, R.string.pocket_node_role_router_desc),
  )

@Composable
private fun ModelsTab(modelManagerViewModel: ModelManagerViewModel) {
  val context = LocalContext.current
  val slots = PocketNodeState.slots
  var version by remember { mutableIntStateOf(0) }
  val allModels = remember(version) { PocketNodeModelResolver.getAllModels(context) }
  var importMessage by remember { mutableStateOf<Int?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }
  var aliases by remember { mutableStateOf(slots?.aliasesText().orEmpty()) }

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val json = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
      importError = if (json == null) "File unreadable" else PocketNodeModelResolver.importAllowlist(context, json)
      importMessage = if (importError == null) R.string.pocket_node_allowlist_ok else null
      version++
    }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { Hint(stringResource(R.string.pocket_node_models_intro)) }
    items(ROLE_UI) { ui ->
      val base =
        when (ui.role) {
          ModelRole.STT -> allModels.filter { PocketNodeModelResolver.supportsAudio(it) }
          ModelRole.VISION -> allModels.filter { it.llmSupportImage }
          else -> allModels
        }
      val roleKey = ui.role.key
      val candidates =
        base.sortedWith(
          compareByDescending<Model> { roleKey in it.recommendedRoles }
            .thenByDescending { "es" in it.languages }
            .thenByDescending { PocketNodeModelResolver.isDownloaded(context, it) }
            .thenBy { it.name }
        )
      SectionCard(stringResource(ui.title)) {
        Hint(stringResource(ui.desc))
        val downloadStatus = modelManagerViewModel.uiState.collectAsState().value.modelDownloadStatus
        ModelDropdown(
          selected = slots?.get(ui.role).orEmpty(),
          models = candidates,
          roleKey = roleKey,
          optional = ui.role != ModelRole.CHAT && ui.role != ModelRole.STT,
          downloadStatus = downloadStatus,
          onSelect = { modelName ->
            PocketNodeState.setRoleModel(context, ui.role, modelName)
            version++
          },
          onDownload = { model -> modelManagerViewModel.downloadModel(null, model) },
        )
      }
    }
    item { TtsCard() }
    item {
      SectionCard(stringResource(R.string.pocket_node_aliases_title)) {
        Hint(stringResource(R.string.pocket_node_aliases_desc))
        OutlinedTextField(value = aliases, onValueChange = { aliases = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        OutlinedButton(onClick = { slots?.setAliasesText(aliases) }) { Text(stringResource(R.string.pocket_node_aliases_save)) }
      }
    }
    item {
      SectionCard(stringResource(R.string.pocket_node_allowlist_title)) {
        Hint(stringResource(R.string.pocket_node_allowlist_desc))
        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.pocket_node_allowlist_import)) }
        importMessage?.let { Hint(stringResource(it)) }
        importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
  selected: String,
  models: List<Model>,
  roleKey: String,
  optional: Boolean,
  downloadStatus: Map<String, ModelDownloadStatus> = emptyMap(),
  onSelect: (String) -> Unit,
  onDownload: (Model) -> Unit,
) {
  val context = LocalContext.current
  var expanded by remember { mutableStateOf(false) }
  val auto = stringResource(R.string.pocket_node_automatic)
  val missing = stringResource(R.string.pocket_node_not_downloaded)
  val current = models.find { it.name.equals(selected, ignoreCase = true) }
  val label = current?.name ?: if (selected.isEmpty()) auto else selected
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = label,
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      if (optional) DropdownMenuItem(text = { Text(auto) }, onClick = { onSelect(""); expanded = false })
      models.forEach { model ->
        val ready = PocketNodeModelResolver.isDownloaded(context, model)
        val tags = mutableListOf<String>()
        if ("es" in model.languages) tags.add("ES")
        if (roleKey in model.recommendedRoles) tags.add("REC")
        val suffix = if (tags.isEmpty()) "" else " [" + tags.joinToString("/") + "]"
        val text = if (ready) "${model.name}$suffix" else "${model.name}$suffix ($missing)"
        DropdownMenuItem(
          text = { Text(text) },
          onClick = { onSelect(model.name); expanded = false },
        )
      }
    }
  }
  if (current != null) {
    val downloaded = PocketNodeModelResolver.isDownloaded(context, current)
    val status = downloadStatus[current.name]
    val inProgress = status?.status == ModelDownloadStatusType.IN_PROGRESS

    if (inProgress) {
      val progressFrac =
        if (status != null && status.totalBytes > 0) {
          (status.receivedBytes.toFloat() / status.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
      Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val pct = (progressFrac * 100).toInt()
        Text(
          text = "Descargando... $pct%",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
          progress = { progressFrac },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    } else if (!downloaded) {
      Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          stringResource(R.string.pocket_node_not_downloaded_warning),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
        val sizeFormatted = Formatter.formatShortFileSize(context, current.sizeInBytes)
        Button(
          onClick = { onDownload(current) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("${stringResource(R.string.pocket_node_download)} ($sizeFormatted)")
        }
      }
    } else {
      Text(
        text = "✓ Modelo descargado y listo para usar",
        color = Color(0xFF2E7D32),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

@Composable
private fun TtsCard() {
  val context = LocalContext.current
  var language by remember { mutableStateOf(PocketNodeState.ttsLanguage) }
  var rate by remember { mutableStateOf(PocketNodeState.ttsRate) }
  SectionCard(stringResource(R.string.pocket_node_tts_title)) {
    Hint(stringResource(R.string.pocket_node_tts_desc))
    OutlinedTextField(
      value = language,
      onValueChange = { language = it; PocketNodeState.saveTtsPreferences(context, it, rate) },
      label = { Text(stringResource(R.string.pocket_node_tts_language)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.pocket_node_tts_rate, "%.2f".format(rate)))
    Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = { PocketNodeState.saveTtsPreferences(context, language, rate) }, valueRange = 0.5f..2f)
  }
}

// ---------------------------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------------------------

@Composable
private fun SecurityTab() {
  val context = LocalContext.current
  val copy = copyToClipboard()
  var key by remember { mutableStateOf(PocketNodeState.apiKey) }
  var cors by remember { mutableStateOf(PocketNodeState.corsEnabled) }
  var visible by remember { mutableStateOf(false) }
  var saved by remember { mutableStateOf(false) }
  val restart = stringResource(R.string.pocket_node_saved_restart)
  val plain = stringResource(R.string.pocket_node_saved)

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SectionCard(stringResource(R.string.pocket_node_api_key)) {
      Hint(stringResource(R.string.pocket_node_api_key_hint))
      OutlinedTextField(
        value = key,
        onValueChange = { key = it; saved = false },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.pocket_node_hide else R.string.pocket_node_show)) }
        TextButton(onClick = { key = generateKey(); visible = true; saved = false }) { Text(stringResource(R.string.pocket_node_generate)) }
        TextButton(enabled = key.isNotEmpty(), onClick = { copy(key) }) { Text(stringResource(R.string.pocket_node_copy)) }
      }
    }
    SectionCard(stringResource(R.string.pocket_node_cors)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Hint(stringResource(R.string.pocket_node_cors_desc)) }
        Switch(checked = cors, onCheckedChange = { cors = it; saved = false })
      }
    }
    Button(
      onClick = { PocketNodeState.saveSecurityPreferences(context, key, cors); saved = true },
      modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(stringResource(R.string.pocket_node_save)) }
    if (saved) Hint(if (PocketNodeState.isServerRunning) restart else plain)
  }
}

private fun generateKey(): String {
  val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
  return "pn-" + bytes.joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActivityTab() {
  val log = PocketNodeState.requestLog
  val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
  val copy = copyToClipboard()

  Column(Modifier.fillMaxSize()) {
    if (log.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Hint(stringResource(R.string.pocket_node_activity_empty))
      }
    } else {
      val total = log.size
      val errors = log.count { it.status >= 400 }

      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.pocket_node_activity_summary, total, errors),
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = {
            val allLogs = log.joinToString("\n") { e ->
              "[${e.id.ifEmpty { "req" }}] ${time.format(Date(e.timeMillis))} ${e.method} ${e.path} -> ${e.status} (${e.durationMs}ms)" +
                (e.model?.let { " model=$it" } ?: "") +
                (e.clientIp?.let { " ip=$it" } ?: "") +
                (e.error?.let { " error=\"$it\"" } ?: "")
            }
            copy(allLogs)
          }) {
            Text(stringResource(R.string.pocket_node_activity_copy_all))
          }
          TextButton(onClick = { PocketNodeState.clearLog() }) {
            Text(stringResource(R.string.pocket_node_activity_clear))
          }
        }
      }
      HorizontalDivider()

      LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(log.toList()) { e ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = if (e.status >= 400) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
              else MaterialTheme.colorScheme.surfaceContainer,
            ),
          ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = e.status.toString(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (e.status < 400) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = "${e.method} ${e.path}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                  )
                }
                if (e.id.isNotEmpty()) {
                  Text(
                    text = e.id,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              val meta = buildList {
                add(time.format(Date(e.timeMillis)))
                add("${e.durationMs} ms")
                e.model?.let { add("modelo: $it") }
                e.clientIp?.let { add("ip: $it") }
              }.joinToString(" · ")
              Hint(meta)

              if (!e.error.isNullOrBlank()) {
                Surface(
                  shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.errorContainer,
                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                  Text(
                    text = "⚠️ ${e.error}",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
