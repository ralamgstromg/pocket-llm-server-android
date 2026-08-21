package com.google.ai.edge.gallery.server

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
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch
import java.io.File

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

    val audioCapableModels = remember(allModels) {
        allModels.filter { it.llmSupportAudio || it.bestForTaskIds.contains("llm_ask_audio") }
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
                                    text = "http://localhost:8080",
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
                                    val matchedModel = allModels.find { it.name == model.name }
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

                Spacer(modifier = Modifier.height(24.dp))

                // Action Toggle Button
                Button(
                    enabled = !isInitializing,
                    onClick = {
                        val matchedChat = allModels.find { it.name == selectedChatModelName }
                        if (matchedChat != null) {
                            PocketNodeState.activeChatModel = matchedChat
                        }
                        val matchedAudio = allModels.find { it.name == selectedAudioModelName }
                        if (matchedAudio != null) {
                            PocketNodeState.activeAudioModel = matchedAudio
                        }
                        PocketNodeState.syncSharedModels()
                        PocketNodeState.savePreferences(context, selectedChatModelName, selectedAudioModelName)

                        val intent = Intent(context, PocketNodeService::class.java)
                        if (PocketNodeState.isServerRunning) {
                            context.stopService(intent)
                        } else {
                            val targetModel = PocketNodeState.activeChatModel ?: PocketNodeState.activeAudioModel
                            if (targetModel != null && targetModel.instance == null) {
                                val modelFile = File(targetModel.getPath(context))
                                if (!modelFile.exists() || modelFile.length() == 0L) {
                                    statusMessage = "⚠️ El modelo '${targetModel.name}' aún no se ha descargado en el dispositivo."
                                    return@Button
                                }

                                isInitializing = true
                                statusMessage = "⏳ Cargando modelo '${targetModel.name}' en memoria RAM/NPU..."

                                scope.launch {
                                    LlmChatModelHelper.initialize(
                                        context = context,
                                        model = targetModel,
                                        supportImage = targetModel.llmSupportImage,
                                        supportAudio = targetModel.llmSupportAudio,
                                        onDone = { errorMsg ->
                                            isInitializing = false
                                            if (errorMsg.isEmpty()) {
                                                statusMessage = "✅ Modelo cargado exitosamente."
                                                try {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        context.startForegroundService(intent)
                                                    } else {
                                                        context.startService(intent)
                                                    }
                                                } catch (e: Exception) {
                                                    statusMessage = "❌ Error iniciando servicio de servidor: ${e.message}"
                                                }
                                            } else {
                                                statusMessage = "❌ Error cargando modelo: $errorMsg"
                                            }
                                        }
                                    )
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
            }
        }
    }
}
