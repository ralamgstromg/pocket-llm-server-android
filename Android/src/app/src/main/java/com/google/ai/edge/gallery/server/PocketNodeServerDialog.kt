package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.server.whisper.isWhisperStt
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch
import java.io.File

private fun Model.isDownloaded(context: Context): Boolean {
    val file = File(getPath(context))
    return file.exists() && file.length() > 0L
}

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

    var chatDropdownExpanded by remember { mutableStateOf(false) }
    var audioDropdownExpanded by remember { mutableStateOf(false) }

    var isInitializing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    var isTestingApis by remember { mutableStateOf(false) }
    var apiTestResults by remember { mutableStateOf<List<ApiTestResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        PocketNodeState.loadPreferences(context)
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
                                    text = "http://localhost:${PocketNodeServer.PORT}",
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

                Spacer(modifier = Modifier.height(24.dp))

                // Action Toggle Button
                Button(
                    enabled = !isInitializing,
                    onClick = {
                        val matchedChat = allModels.find { it.name == selectedChatModelName }
                        if (matchedChat != null) {
                            PocketNodeState.activeChatModel = matchedChat
                        }
                        val matchedAudio = audioCapableModels.find { it.name == selectedAudioModelName }
                        if (matchedAudio != null) {
                            PocketNodeState.activeAudioModel = matchedAudio
                        }
                        PocketNodeState.syncSharedModels()
                        PocketNodeState.savePreferences(context, selectedChatModelName, selectedAudioModelName)

                        val intent = Intent(context, PocketNodeService::class.java)
                        if (PocketNodeState.isServerRunning) {
                            context.stopService(intent)
                        } else {
                            val pendingStt = PocketNodeState.activeAudioModel?.takeIf { it.isWhisperStt }
                            if (pendingStt != null && !pendingStt.isDownloaded(context)) {
                                statusMessage = "⚠️ Descarga el modelo STT '${pendingStt.name}' antes de iniciar el servidor."
                                return@Button
                            }
                            // Whisper loads lazily on the first transcription request, so only the chat model is preloaded here.
                            val targetModel = PocketNodeState.activeChatModel ?: PocketNodeState.activeAudioModel?.takeUnless { it.isWhisperStt }
                            if (targetModel != null && targetModel.instance == null) {
                                val modelFile = File(targetModel.getPath(context))
                                if (!modelFile.exists() || modelFile.length() == 0L) {
                                    statusMessage = "⚠️ El modelo '${targetModel.name}' aún no se ha descargado en el dispositivo."
                                    return@Button
                                }

                                isInitializing = true
                                statusMessage = "⏳ Cargando modelo '${targetModel.name}' en memoria RAM/NPU..."

                                scope.launch {
                                    val result = PocketNodeInference.initializeLlm(context, targetModel)
                                    isInitializing = false
                                    result.onSuccess { backend ->
                                        statusMessage = "✅ Modelo cargado en ${backend.label}."
                                        try {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.startForegroundService(intent)
                                            } else {
                                                context.startService(intent)
                                            }
                                        } catch (e: Exception) {
                                            statusMessage = "❌ Error iniciando servicio de servidor: ${e.message}"
                                        }
                                    }.onFailure { e ->
                                        statusMessage = "❌ Error cargando modelo: ${e.message}"
                                    }
                                }
                            } else {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "❌ Error al iniciar servicio HTTP: ${e.message}"
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
            }
        }
    }
}
