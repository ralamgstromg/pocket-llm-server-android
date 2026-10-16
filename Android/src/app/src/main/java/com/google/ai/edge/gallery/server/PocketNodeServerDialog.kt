package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.server.whisper.isWhisperStt
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Model.isDownloaded(context: Context): Boolean {
    val file = File(getPath(context))
    return file.exists() && file.length() > 0L
}

private val TOKEN_PRESETS = listOf(2048, 4096, 8000, 16384)
private val IDLE_MINUTES_PRESETS = listOf(1, 3, 5, 15)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketNodeServerDialog(
    modelManagerViewModel: ModelManagerViewModel,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by modelManagerViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Get all available models across tasks
    val allModels = remember(uiState) {
        uiState.tasks.flatMap { it.models }.distinctBy { it.name }
    }

    // STT candidates: LLMs with audio support plus Whisper models, which are not part of any task list.
    val audioCapableModels = remember(allModels) {
        allModels.filter { it.llmSupportAudio || it.bestForTaskIds.contains("llm_ask_audio") } +
            PocketNodeModelResolver.getAllModels(context).filter { it.isWhisperStt }
    }

    var selectedChatModelName by remember {
        mutableStateOf(PocketNodeState.preferredChatModelName.ifEmpty { PocketNodeState.activeChatModel?.name ?: "" })
    }
    var selectedAudioModelName by remember {
        mutableStateOf(PocketNodeState.preferredAudioModelName.ifEmpty { PocketNodeState.activeAudioModel?.name ?: "" })
    }
    var maxTokensText by remember {
        mutableStateOf(PocketNodeState.configuredMaxTokens.toString())
    }

    var chatDropdownExpanded by remember { mutableStateOf(false) }
    var audioDropdownExpanded by remember { mutableStateOf(false) }

    var isInitializing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    var isTestingApis by remember { mutableStateOf(false) }
    var apiTestResults by remember { mutableStateOf<List<ApiTestResult>>(emptyList()) }

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            PocketNodeLogger.exportToUri(context, uri)
                .onSuccess { statusMessage = "✅ Log exportado correctamente." }
                .onFailure { e -> statusMessage = "❌ Error al exportar log: ${e.message}" }
        }
    }

    LaunchedEffect(Unit) {
        PocketNodeState.loadPreferences(context)
        PocketNodeLogger.loadPreferences(context)
        maxTokensText = PocketNodeState.configuredMaxTokens.toString()
        if (selectedChatModelName.isEmpty() && allModels.isNotEmpty()) {
            selectedChatModelName = PocketNodeState.preferredChatModelName.ifEmpty { allModels.first().name }
        }
        if (selectedAudioModelName.isEmpty() && audioCapableModels.isNotEmpty()) {
            selectedAudioModelName = PocketNodeState.preferredAudioModelName.ifEmpty { audioCapableModels.first().name }
        }
    }

    LaunchedEffect(PocketNodeState.lastStartupError) {
        if (!PocketNodeState.lastStartupError.isNullOrEmpty()) {
            statusMessage = "❌ ${PocketNodeState.lastStartupError}"
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pocket Node Server",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status Indicator
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (PocketNodeState.isServerRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (PocketNodeState.isServerRunning) Color(0xFF4CAF50) else Color(0xFFFF9800))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (PocketNodeState.isServerRunning) "Servidor HTTP Activo" else "Servidor HTTP Inactivo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (PocketNodeState.isServerRunning) {
                                Text(
                                    text = "http://localhost:${PocketNodeServer.PORT} · ${PocketNodeState.configuredMaxTokens} tokens · hiberna ${PocketNodeState.configuredIdleMinutes}m",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Selector de Modelo para Chat (LLM / Completions)
                Text(
                    text = "Modelo para Chat / Completions:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                ExposedDropdownMenuBox(
                    expanded = chatDropdownExpanded,
                    onExpandedChange = { chatDropdownExpanded = !chatDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = (allModels.find { it.name == selectedChatModelName })?.name ?: selectedChatModelName.ifEmpty { "Seleccionar modelo Chat" },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = chatDropdownExpanded,
                        onDismissRequest = { chatDropdownExpanded = false }
                    ) {
                        allModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                onClick = {
                                    selectedChatModelName = model.name
                                    chatDropdownExpanded = false
                                    val matchedModel = allModels.find { it.name == model.name }
                                    if (matchedModel != null) {
                                        PocketNodeState.activeChatModel = matchedModel
                                        if (matchedModel.llmSupportAudio && selectedAudioModelName.isEmpty()) {
                                            selectedAudioModelName = model.name
                                            PocketNodeState.activeAudioModel = matchedModel
                                        }
                                    }
                                    PocketNodeState.syncSharedModels()
                                    PocketNodeState.savePreferences(context, selectedChatModelName, selectedAudioModelName)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Selector de Modelo para Transcripción (Audio STT)
                Text(
                    text = "Modelo para Transcripción (Audio STT):",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                ExposedDropdownMenuBox(
                    expanded = audioDropdownExpanded,
                    onExpandedChange = { audioDropdownExpanded = !audioDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = (audioCapableModels.find { it.name == selectedAudioModelName } ?: allModels.find { it.name == selectedAudioModelName })?.name ?: selectedAudioModelName.ifEmpty { "Seleccionar modelo STT" },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = audioDropdownExpanded,
                        onDismissRequest = { audioDropdownExpanded = false }
                    ) {
                        val displayList = if (audioCapableModels.isNotEmpty()) audioCapableModels else allModels
                        displayList.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                onClick = {
                                    selectedAudioModelName = model.name
                                    audioDropdownExpanded = false
                                    val matchedModel = audioCapableModels.find { it.name == model.name }
                                    if (matchedModel != null) {
                                        PocketNodeState.activeAudioModel = matchedModel
                                    }
                                    PocketNodeState.syncSharedModels()
                                    PocketNodeState.savePreferences(context, selectedChatModelName, selectedAudioModelName)
                                }
                            )
                        }
                    }
                }

                // Whisper STT model: download status and progress (it is not listed in the Gallery tasks)
                val sttModel = PocketNodeState.activeAudioModel?.takeIf { it.isWhisperStt }
                if (sttModel != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val downloadStatus = uiState.modelDownloadStatus[sttModel.name]
                    if (downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS) {
                        val progress = downloadStatus?.let {
                            if (it.totalBytes > 0) it.receivedBytes.toFloat() / it.totalBytes else 0f
                        } ?: 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        val downloaded = sttModel.isDownloaded(context)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (downloaded) "✅ Modelo STT descargado" else "⚠️ Modelo STT no descargado",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (!downloaded) {
                                TextButton(onClick = { modelManagerViewModel.downloadModel(task = null, model = sttModel) }) {
                                    Text("Descargar")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Configurable Max Tokens per request (default 8000)
                Text(
                    text = "Máximo de Tokens por Petición (Contexto + Respuesta):",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { newText ->
                        val digitsOnly = newText.filter { it.isDigit() }
                        maxTokensText = digitsOnly
                        digitsOnly.toIntOrNull()?.let { parsed ->
                            PocketNodeState.savePreferences(
                                context = context,
                                chatModelName = selectedChatModelName,
                                audioModelName = selectedAudioModelName,
                                maxTokens = parsed,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "Por defecto: ${PocketNodeState.DEFAULT_MAX_TOKENS} (rango ${PocketNodeState.MIN_MAX_TOKENS}..${PocketNodeState.MAX_MAX_TOKENS})",
                            fontSize = 11.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TOKEN_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = PocketNodeState.configuredMaxTokens == preset,
                            onClick = {
                                maxTokensText = preset.toString()
                                PocketNodeState.savePreferences(
                                    context = context,
                                    chatModelName = selectedChatModelName,
                                    audioModelName = selectedAudioModelName,
                                    maxTokens = preset,
                                )
                                statusMessage = "✅ Límite ajustado a $preset tokens."
                            },
                            label = { Text("$preset", fontSize = 12.sp) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Configurable Idle Hibernation timeout (battery saver)
                Text(
                    text = "Hibernación en Reposo (Ahorro de Batería):",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IDLE_MINUTES_PRESETS.forEach { mins ->
                        FilterChip(
                            selected = PocketNodeState.configuredIdleMinutes == mins,
                            onClick = {
                                PocketNodeState.savePreferences(
                                    context = context,
                                    chatModelName = selectedChatModelName,
                                    audioModelName = selectedAudioModelName,
                                    idleMinutes = mins,
                                )
                                PocketNodeInference.touchActivity(context)
                                statusMessage = "🔋 Hibernación por inactividad ajustada a $mins min."
                            },
                            label = { Text("${mins}m", fontSize = 12.sp) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Toggle Button
                Button(
                    enabled = !isInitializing,
                    onClick = {
                        val parsedTokens = maxTokensText.toIntOrNull() ?: PocketNodeState.DEFAULT_MAX_TOKENS
                        val matchedChat = allModels.find { it.name == selectedChatModelName }
                        if (matchedChat != null) {
                            PocketNodeState.activeChatModel = matchedChat
                        }
                        val matchedAudio = audioCapableModels.find { it.name == selectedAudioModelName }
                        if (matchedAudio != null) {
                            PocketNodeState.activeAudioModel = matchedAudio
                        }
                        PocketNodeState.syncSharedModels()
                        PocketNodeState.savePreferences(
                            context = context,
                            chatModelName = selectedChatModelName,
                            audioModelName = selectedAudioModelName,
                            maxTokens = parsedTokens,
                        )
                        maxTokensText = PocketNodeState.configuredMaxTokens.toString()

                        val intent = Intent(context, PocketNodeService::class.java)
                        if (PocketNodeState.isServerRunning) {
                            context.stopService(intent)
                        } else {
                            val chatModel = PocketNodeState.activeChatModel?.takeUnless { it.isWhisperStt }
                            val audioModel = PocketNodeState.activeAudioModel
                            val sharedAudio = chatModel != null && audioModel != null && chatModel.name == audioModel.name

                            if (chatModel != null && !chatModel.isDownloaded(context)) {
                                statusMessage = "⚠️ El modelo Chat '${chatModel.name}' aún no se ha descargado en el dispositivo."
                                return@Button
                            }
                            if (audioModel != null && !audioModel.isDownloaded(context)) {
                                statusMessage = "⚠️ Descarga el modelo STT '${audioModel.name}' antes de iniciar el servidor."
                                return@Button
                            }

                            isInitializing = true
                            statusMessage = "⏳ Cargando modelos en memoria (GPU / Español / ${PocketNodeState.configuredMaxTokens} tokens)..."

                            scope.launch {
                                val loadedSummary = mutableListOf<String>()
                                if (chatModel != null) {
                                    statusMessage = "⏳ Cargando modelo Chat '${chatModel.name}' en memoria..."
                                    val chatRes = withContext(Dispatchers.IO) {
                                        PocketNodeInference.preloadModel(context, chatModel, forAudio = sharedAudio)
                                    }
                                    chatRes.onSuccess { backend ->
                                        loadedSummary += "${chatModel.name} ($backend)"
                                    }.onFailure { e ->
                                        isInitializing = false
                                        statusMessage = "❌ Error cargando modelo Chat: ${e.message}"
                                        return@launch
                                    }
                                }

                                val canPreloadBoth = chatModel == null || PocketNodeInference.hasEnoughRamForDualResidency(context)
                                if (audioModel != null && !sharedAudio && canPreloadBoth) {
                                    statusMessage = "⏳ Cargando modelo STT '${audioModel.name}' en memoria..."
                                    val audioRes = withContext(Dispatchers.IO) {
                                        PocketNodeInference.preloadModel(context, audioModel, forAudio = true)
                                    }
                                    audioRes.onSuccess { backend ->
                                        loadedSummary += "${audioModel.name} ($backend)"
                                    }.onFailure { e ->
                                        isInitializing = false
                                        statusMessage = "❌ Error cargando modelo STT: ${e.message}"
                                        return@launch
                                    }
                                }

                                isInitializing = false
                                statusMessage = if (loadedSummary.isNotEmpty()) {
                                    "✅ Modelos en memoria (${PocketNodeState.configuredMaxTokens} tokens): ${loadedSummary.joinToString(", ")}"
                                } else {
                                    "✅ Servidor iniciado."
                                }
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "❌ Error iniciando servicio de servidor: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (PocketNodeState.isServerRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isInitializing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (PocketNodeState.isServerRunning) "Detener Servidor" else "Iniciar Servidor HTTP",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // API route tests: always visible so they can be found; enabled only while the server runs
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    enabled = PocketNodeState.isServerRunning && !isTestingApis && !isInitializing,
                    onClick = {
                        isTestingApis = true
                        apiTestResults = emptyList()
                        scope.launch {
                            apiTestResults = PocketNodeApiTester.runAll()
                            isTestingApis = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = if (isTestingApis) "Probando rutas..." else "Probar todas las rutas",
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!PocketNodeState.isServerRunning) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Inicia el servidor para probar sus rutas.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                apiTestResults.forEach { result ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${if (result.ok) "✅" else "❌"} ${result.route}  ·  ${result.httpCode?.let { "HTTP $it" } ?: "sin respuesta"}  ·  ${result.millis} ms",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (result.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                    result.model?.let { model ->
                        Text(
                            text = "Modelo: $model",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = result.detail,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // API Operation Logs section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Log de Operaciones API",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "${PocketNodeLogger.entries.size} operaciones registradas",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = PocketNodeLogger.isLoggingEnabled,
                        onCheckedChange = { enabled ->
                            PocketNodeLogger.setLoggingEnabled(context, enabled)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 180.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    val logEntries = PocketNodeLogger.entries
                    if (logEntries.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = if (PocketNodeLogger.isLoggingEnabled) {
                                    "Sin operaciones registradas aún."
                                } else {
                                    "Log de operaciones desactivado."
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            logEntries.takeLast(50).asReversed().forEach { entry ->
                                Text(
                                    text = entry.formatLine(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (entry.statusCode >= 400) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = PocketNodeLogger.entries.isNotEmpty(),
                        onClick = {
                            PocketNodeLogger.shareLogs(context)
                                .onFailure { e -> statusMessage = "❌ Error al compartir log: ${e.message}" }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Compartir", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        enabled = PocketNodeLogger.entries.isNotEmpty(),
                        onClick = {
                            exportLogsLauncher.launch(PocketNodeLogger.defaultExportFileName())
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Exportar", fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = PocketNodeLogger.entries.isNotEmpty(),
                        onClick = {
                            PocketNodeLogger.clear(context)
                            statusMessage = "🧹 Log de operaciones limpiado."
                        },
                    ) {
                        Text("Limpiar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
