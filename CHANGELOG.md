# Changelog

Cambios aplicados a Pocket Node (fork de AI Edge Gallery). Los más recientes van primero.

## Unreleased

### Límite de tokens por petición en 8000 por defecto y configurable en UI / API
- `server/PocketNodeState.kt`:
  - Nuevo `DEFAULT_MAX_TOKENS = 8000` (`MIN_MAX_TOKENS = 256`, `MAX_MAX_TOKENS = 32768`) y estado reactivo `configuredMaxTokens` persistido en `SharedPreferences` (`api_max_tokens`).
- `server/PocketNodeServerDialog.kt`:
  - Nuevo control editable **Máximo de Tokens por Petición (Contexto + Respuesta)** (iniciado en `8000`) con selector rápido (`2048`, `4096`, `8000`, `16384`).
- `server/PocketNodeInference.kt` y `server/PocketNodeServer.kt`:
  - `applyFastServingConfig` aplica `configuredMaxTokens` (`8000` por defecto) o `max_tokens` enviado en el JSON de `/v1/chat/completions` directamente sobre `ConfigKeys.MAX_TOKENS` sin recortarlo al `llmMaxToken` base del catálogo (`1024`/`4096`).
  - Si cambia el límite de tokens en la UI o por petición HTTP, `initializeLlmLocked` / `runIsolatedLlmInference` reinicializa el motor LiteRT-LM con la nueva ventana de tokens.

### Aislamiento de contexto por petición (anti-mezcla de prompts) y optimización de batería
- `server/PocketNodeInference.kt`:
  - `buildChatPromptContext`: separa mensajes `system`/`developer` hacia `ConversationConfig.systemInstruction` (junto con `DEFAULT_SPANISH_SYSTEM_PROMPT`), limpia tags de control inyectados (`<start_of_turn>`, `<end_of_turn>`, `<|im_start|>`, `<|im_end|>`, `<|endoftext|>`, `<bos>`, `<eos>`, `<ctrl...>`) y estructura el historial multiturno (`<historial_conversacion>` vs `<mensaje_actual_usuario>`) para evitar mezcla de solicitudes.
  - `runIsolatedLlmInference`: crea una `Conversation` nueva y aislada por cada llamada HTTP bajo un `Mutex` global por modelo (`modelMutex`), soporta cancelación nativa (`suspendCancellableCoroutine` -> `cancelProcess()`), filtra tokens `<ctrl...>` y cierra siempre la sesión en `finally`.
  - **Ahorro de batería**: adquiere `PARTIAL_WAKE_LOCK` (`withInferenceWakeLock`) únicamente mientras hay una inferencia activa, hiberna automáticamente los modelos en RAM/GPU tras 10 minutos de inactividad (`IDLE_UNLOAD_TIMEOUT_MS`) y libera todos los modelos al detener el servidor (`releaseAllLoadedModels`).
- `server/PocketNodeServer.kt`:
  - Soporta `temperature` y `max_tokens` opcionales en `ChatRequest`, usa `buildChatPromptContext` y `runIsolatedLlmInference`, evita escrituras a disco en `GET /` y libera todos los modelos en `stop()`.
- `ui/llmchat/LlmChatModelHelper.kt`:
  - Invoca `cancelProcess()` antes de `close()` en `resetConversation` y `cleanUp` para evitar sesiones nativas colgadas.
- `server/whisper/WhisperEngine.kt`:
  - `optimalThreadCount`: limita los hilos de CPU a `(availableProcessors / 2).coerceIn(2, 4)` para evitar saturación térmica y consumo excesivo de batería.

### Precarga en memoria, GPU por defecto, optimización de latencia y español por defecto
- `server/PocketNodeInference.kt`:
  - Nuevo `preloadModel(context, model, forAudio)` que carga en memoria tanto modelos LLM como Whisper apenas se habilita la API.
  - `preferredAccelerator` y `applyFastServingConfig`: activa `GPU` por defecto si el modelo lo soporta, desactiva el modo `thinking` (`ENABLE_THINKING = false`), reduce `topK` a `16`, `topP` a `0.85`, `temperature` a `0.4`, y evita cargar el codificador de visión en el servidor API (`supportImage = false`).
  - Configura español por defecto (`DEFAULT_LANGUAGE = "es"` y `DEFAULT_SPANISH_SYSTEM_PROMPT`) en la inicialización y en cada petición de la API.
- `server/PocketNodeServer.kt` y `server/PocketNodeServerDialog.kt`:
  - Precargan tanto `activeChatModel` como `activeAudioModel` (incluyendo Whisper) apenas se inicia el servidor, registrando el evento `SERVER preload` en `PocketNodeLogger`.
  - Crean una conversación aislada en cada petición HTTP stateless para evitar acumulación en el KV-cache.
  - Usan `"es"` como idioma por defecto en `/v1/audio/transcriptions`.
- `data/Config.kt`, `data/ModelAllowlist.kt` y `server/PocketNodeModelResolver.kt`:
  - Priorizan `Accelerator.GPU` como valor por defecto en `ConfigKeys.ACCELERATOR` y actualizan entradas antiguas en disco sin GPU cuando el asset empaquetado soporta GPU.
- `ui/llmchat/LlmChatModelHelper.kt`:
  - Activa siempre `cacheDir = context.cacheDir.absolutePath` en `EngineConfig` para persistir la compilación de shaders de GPU.
- `server/whisper/WhisperEngine.kt`:
  - Usa español (`"es"`) por defecto en `resolveLanguageCode` (ahorrando el paso extra del decodificador en `detectLanguage()`), activa `setUseXNNPACK(true)` y ajusta hilos de forma eficiente (`2..4`).

### Log de operaciones de la API con exportación y uso compartido
- `server/PocketNodeLogger.kt`: registro en tiempo real de todas las operaciones del servidor (`start`, `stop`, `GET /`, `POST /v1/chat/completions`, `POST /v1/audio/transcriptions`) con marca de tiempo, código HTTP, latencia (`ms`), modelo utilizado y detalle de la operación o error.
- Persistencia en `cacheDir/logs/pocket_node_api.log` y buffer en memoria (hasta 500 entradas), con interruptor activo/inactivo guardado en `SharedPreferences`.
- `res/xml/file_paths.xml`: añade `<cache-path name="server_logs" path="logs/" />` al `FileProvider` para compartir el archivo `.log` con otras aplicaciones (`Intent.ACTION_SEND`).
- `server/PocketNodeServerDialog.kt`: nueva sección **Log de Operaciones API** con interruptor, vista en vivo desplazable y botones **Compartir** (con otras apps), **Exportar** (`CreateDocument`) y **Limpiar**.
- `PocketNodeLoggerTest`: pruebas unitarias del registro, desactivación y formato de exportación.

### Nombre del modelo en el test de rutas (`feat/test-shows-model-name`)
- `PocketNodeServer`: añade la cabecera diagnóstica `X-Model-Name` (`MODEL_HEADER`) justo tras resolver el modelo en `/v1/chat/completions` y `/v1/audio/transcriptions` (saneada a ASCII imprimible mediante `headerSafe`) e incluye el campo `model` en `ChatResponse`.
- `PocketNodeApiTester`: resuelve el nombre del modelo mediante `resolveModelName` (cabecera `X-Model-Name`, campo JSON `model` o fallback al modelo activo/preferido en `PocketNodeState`) y lo guarda en `ApiTestResult.model`.
- `PocketNodeServerDialog`: muestra `Modelo: <nombre>` en cada fila de resultado del test de rutas cuando la ruta utiliza un modelo.
- `PocketNodeApiTesterTest`: pruebas unitarias para `headerSafe` y `resolveModelName`.

### Correcciones del test de rutas
- `PocketNodeApiTester`: las respuestas correctas de chat y audio se marcaban como ❌ porque el texto de éxito se trataba como error. Ahora los verificadores devuelven un `Verdict` y el éxito conserva su detalle.
- Nuevas pruebas (`PocketNodeApiTesterTest`) que cubren el caso de regresión.
- Diagnóstico de latencia: `WhisperEngine.lastTimings` y la cabecera `X-Whisper-Timings` en `/v1/audio/transcriptions` (mel, encoder, pasos de decodificación), mostrada en la fila de audio del diálogo.


### GPU por defecto y rendimiento de la API
- `server/PocketNodeInference.kt`: inicialización única de los LLM para servidor y diálogo. Usa GPU si el modelo la soporta y repite en CPU si falla. Registra el backend final (`Modelo cargado en GPU/CPU`). Reemplaza dos copias de `LlmChatModelHelper.initialize`.
- `assets/model_allowlists/1_0_11.json`: Gemma-3n-E2B y E4B pasan a `gpu,cpu` (antes arrancaban en CPU).
- Whisper: extracción de espectrograma en paralelo (misma salida, verificada por tests); decodificación limitada a ~8 tokens por segundo de audio (`WhisperEngine.maxNewTokens`); precarga en segundo plano al iniciar el servidor.

### Visibilidad de Whisper y pruebas de API
- `PocketNodeModelResolver`: fusiona el allowlist de disco con el del asset. Un `model_allowlist.json` viejo en el teléfono ya no oculta modelos nuevos como Whisper.
- Diálogo: el botón **Probar todas las rutas** siempre se muestra; deshabilitado mientras el servidor está parado.


### Whisper large-v3-turbo como modelo STT
- Nuevo modelo `Whisper-Large-V3-Turbo-i4` (`litert-community/whisper-large-v3-turbo`, commit `4e3af6a1597a54b6e47d2899cf4f6c39932b0b7e`, `whisper_large_v3_turbo_30s_i4.tflite`, 755 273 648 bytes) en `assets/model_allowlists/1_0_11.json` con `runtimeType: tflite_whisper` y sin tareas de la galería.
- `data/Model.kt`: nuevo `RuntimeType.TFLITE_WHISPER`.
- `server/whisper/WhisperFeatureExtractor.kt`: log-mel de 128 bandas que replica `transformers.WhisperFeatureExtractor` (n_fft 400, hop 160, Hann periódica, reflect-pad, filtros Slaney). Verificado con tests contra valores de referencia de transformers.
- `server/whisper/WhisperTokenizer.kt` y `assets/whisper/tokenizer.json` (MIT, de `openai/whisper-large-v3-turbo`): decodificación BPE byte-level e ids de idioma.
- `server/whisper/WhisperEngine.kt`: carga `encode`/`decode` con LiteRT 2.1.0, decodificación greedy sin KV cache, ventanas de 30 s, detección de idioma si no se envía `language`.
- `server/AudioDecoderHelper.kt`: `decodeToMono16kSamples` devuelve floats; reutiliza `decodeToMonoPcmWav`.
- `server/PocketNodeServer.kt`: `/v1/audio/transcriptions` usa Whisper cuando el modelo STT es Whisper; Whisper nunca se usa como modelo de chat; el motor se libera en `stop()`.
- `server/PocketNodeState.kt`: `syncSharedModels` no copia un modelo Whisper a chat.
- `server/PocketNodeServerDialog.kt`: el selector STT incluye Whisper; muestra estado de descarga, botón **Descargar** y progreso; el inicio se bloquea si falta el archivo.
- Tests unitarios en `app/src/test/.../server/whisper/`: extractor (5 valores de referencia + suma) y tokenizer.

### Dependencias
- Se reemplazan `play-services-tflite-java`, `-gpu` y `-support` (sin uso en el código) por `com.google.ai.edge.litert:litert:2.1.0`.

### Script de build y documentación
- `scripts/build-apk.sh`: instala JDK 21 y Android SDK en el directorio del usuario si faltan, crea `local.properties` y compila debug o release (`--debug`, `--install`).
- README: nueva sección de build, sección de Whisper STT y sección de test de rutas. `DEVELOPMENT.md` apunta al script.

### Test de rutas desde la app
- `server/PocketNodeApiTester.kt`: prueba `GET /`, `POST /v1/chat/completions` y `POST /v1/audio/transcriptions` contra `127.0.0.1:8080`.
- `server/PocketNodeServerDialog.kt`: botón **Probar todas las rutas** con resultados ✅/❌.
- `res/xml/network_security_config.xml`: cleartext permitido solo para `localhost` y `127.0.0.1`.
- `server/PocketNodeServer.kt`: constante `PORT`, usada también en el diálogo y la notificación.

### Build de APK
- `app/proguard-rules.pro` (vacío), referenciado por `app/build.gradle.kts`.
- `Android/src/local.properties` (gitignored) con el SDK local.
