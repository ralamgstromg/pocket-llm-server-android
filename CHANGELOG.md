# Changelog

Cambios aplicados a Pocket Node (fork de AI Edge Gallery). Los más recientes van primero.

## Unreleased

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
