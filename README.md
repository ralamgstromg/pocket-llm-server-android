# Pocket Node (Google AI-Edge-gallery)✨
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Turn your Android phone into a true, offline AI network node.** 

Pocket Node is a customized fork of the open-source **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**. It takes the lightning-fast, on-device LiteRT inference engine of the original app and seamlessly bridges it to an embedded HTTP Server via Ktor.

Instead of burning your PC's GPU power or paying for external cloud APIs, you can now expose your phone's offline LLMs directly to your local WiFi network!

---

## 🚀 The Power of Pocket Node

*   **API on the Go**: Pocket Node exposes background network services on `http://YOUR_PHONE_IP:8080`:
    *   `POST /v1/chat/completions`: Text generation / chat completions.
    *   `POST /v1/audio/transcriptions`: Speech-to-Text (STT) audio transcription compatible with OpenAI.
*   **Immediate Startup Model Preloading**: Active models are loaded into RAM/GPU immediately when the HTTP server is enabled (with automatic low-RAM protection to avoid Android `zram` thrashing when two heavy models are selected), eliminating cold-start latency on the first API call.
*   **Strict Per-Request Context Isolation**:
    *   Each API call runs in a fresh, disposable LiteRT-LM `Conversation` guarded by a global per-model `Mutex` with native cancellation (`cancelProcess()`), guaranteeing zero prompt or token leakage across requests.
    *   Separates `system`/`developer` instructions into native `ConversationConfig.systemInstruction`, strips injected control tags (`<start_of_turn>`, `<end_of_turn>`, `<|im_start|>`, `<|im_end|>`, `<ctrl...>`), and cleanly structures multi-turn history (`<historial_conversacion>` vs `<mensaje_actual_usuario>`).
*   **8,000 Tokens by Default (Configurable in UI & API)**:
    *   Configured to **8,000 tokens** (`DEFAULT_MAX_TOKENS = 8000`) by default so long prompts and responses fit comfortably.
    *   Editable directly in **Pocket Node Server** (`256..32768`, with quick presets `2048`, `4096`, `8000`, `16384`) or per HTTP request via `"max_tokens"` in `POST /v1/chat/completions`.
*   **GPU by Default, Low-Latency & Ultra Battery Optimization**:
    *   Automatically enables `Accelerator.GPU` by default whenever supported by the model (with automatic fallback to `CPU` if initialization fails).
    *   Applies a fast serving profile (`ENABLE_THINKING = false`, `topK = 16`, `topP = 0.85`, `temperature = 0.4`, `maxTokens = 8000`), persistent GPU shader cache (`cacheDir`), and balanced `2..4` XNNPACK threads for Whisper.
    *   **Battery Saver (Foreground & Background)**:
        *   Allows the screen to turn off normally (`FLAG_KEEP_SCREEN_ON` removed) while `PocketNodeService` serves HTTP requests in the background.
        *   **Engine Warm / Zero KV-Cache Idle**: Keeps the LiteRT-LM `Engine` warm in memory (`< 5 ms` conversation creation) while closing the default KV-cache `Conversation` whenever no request is active.
        *   Acquires `PARTIAL_WAKE_LOCK` only during active inference, disables background Firebase telemetry/FCM wakeups, constrains Ktor CIO to `2` network threads, and hibernates idle models after **3 minutes** of inactivity by default (configurable in UI: `1m`, `3m`, `5m`, `15m`).
*   **Spanish by Default (`es`)**:
    *   Chat/Completion LLMs initialize with a default Spanish system instruction (`DEFAULT_SPANISH_SYSTEM_PROMPT`).
    *   Audio transcriptions (`/v1/audio/transcriptions` and `WhisperEngine`) default to Spanish (`language=es`) when no language parameter is provided, skipping the extra language-detection decoder pass in Whisper.
*   **Interactive Model Configuration UI**: Select your desired active models directly in the app before starting the HTTP server:
    *   **Chat / Completions Model**: Dedicated model selector for text generation.
    *   **Native STT Service**: Speech-to-text uses the phone's own `SpeechRecognizer` (on-device preferred), with no extra model to download.
    *   **Persistent Preferences**: Model choices, token budget, and idle hibernation timeout are saved in `SharedPreferences` and automatically initialized when starting `PocketNodeService`.
*   **API Operation Logging, Export & Share**:
    *   Real-time operation log (`PocketNodeLogger`) capturing timestamps, routes, HTTP status codes, latency (`ms`), serving model name, and payload/timing details.
    *   Toggle logging on/off, view recent requests live in **Pocket Node Server**, **Export** logs to a `.log` file (`CreateDocument`), or **Share** logs directly with other apps (Email, WhatsApp, Telegram, Drive) via `FileProvider`.
*   **Local Allowlist Asset Bundling**: Bundles `assets/model_allowlists/1_0_11.json` inside the APK assets. Models defined in local allowlists (like Whisper-Large-V3-Turbo) load instantly in the app's download manager without depending on internet GitHub raw files.
*   **OpenAI-Compatible Schema**: Designed to cleanly integrate with your existing scripts, Python backends, and low-code wrappers (like `n8n`) using standard JSON / Multipart schemas.
*   **Speech-to-Text (STT) via Native Service**: Transcription runs on the Android `SpeechRecognizer` and is served through an OpenAI-compatible `/v1/audio/transcriptions` endpoint, including with the screen off. Defaults to Spanish (`language=es`).
*   **Dual Active Model Management**: `PocketNodeState` manages `activeChatModel` and `activeAudioModel` independently.
*   **Zero Cloud, Zero Cost**: 100% on-device inference using your Android's NPU/GPU/CPU. Completely offline and private.

---

## 🏁 Get Started in Minutes

1. **Clone & Build**: Clone this repository and build the APK using Android Studio or Gradle CLI.
2. **Download Models**: Open the app and download your preferred chat model (e.g. Gemma 4). Speech-to-Text needs no model download.
3. **Configure & Start Server**: Open the left-side navigation drawer, tap **Pocket Node Server** to open the server dialog:
   - Choose your **Chat / Completions Model**.
   - Choose your **Audio STT Model**.
   - Adjust **Máximo de Tokens por Petición** (defaults to `8000`) and **Hibernación en Reposo** (defaults to `3m`).
   - Tap **Iniciar Servidor HTTP** to preload models into memory and ignite the background service!
4. **Test it out**:
   - **Text Completion**:
     ```bash
     curl http://<YOUR_PHONE_IP>:8080/v1/chat/completions \
       -H "Content-Type: application/json" \
       -d '{
         "max_tokens": 8000,
         "messages": [
           {
             "role": "user",
             "content": "Explica la computación cuántica en una frase."
           }
         ]
       }'
     ```
   - **Audio Transcription (Spanish / STT)**:
     ```bash
     curl http://<YOUR_PHONE_IP>:8080/v1/audio/transcriptions \
       -H "Content-Type: multipart/form-data" \
       -F "file=@audio_espanol.mp3" \
       -F "model=android-native-stt" \
       -F "language=es" \
       -F "response_format=json"
     ```

---

## 🛠️ Compiling & Building the APK

### Quick build (recommended)
`scripts/build-apk.sh` installs whatever is missing and builds the APK. It needs `curl`, `unzip` and `tar`, and runs on Linux without root access.

```bash
scripts/build-apk.sh             # release APK (default)
scripts/build-apk.sh --debug     # debug APK
scripts/build-apk.sh --install   # also installs it on a device with adb
```

The script does the following:
- Uses JDK 21 from `JAVA_HOME` or `~/.jdks/jdk-21*`. If none is found it downloads Temurin 21 into `~/.jdks`.
- Uses the Android SDK from `ANDROID_HOME` or `~/android-sdk`. If none is found it downloads the command-line tools there, accepts the licenses, and installs `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`.
- Writes `Android/src/local.properties` with `sdk.dir` if it is missing (the file is gitignored).
- Runs `./gradlew assembleRelease` (or `assembleDebug`) in `Android/src`.

Output: `Android/src/app/build/outputs/apk/{release,debug}/app-*.apk`. The release APK is signed with the project's debug keystore, so it installs without extra secrets.

### Manual build
Requirements:
- **JDK 21**. Gradle 8.10.2 does not run on newer JDKs, and LiteRT-LM is built for Java 21 bytecode.
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`.

Set the SDK path in `Android/src/local.properties`:
```properties
sdk.dir=/home/you/android-sdk
```

Then, from `Android/src`:
```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug    # Android/src/app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleRelease  # Android/src/app/build/outputs/apk/release/app-release.apk
```

Run the unit tests with `./gradlew testDebugUnitTest`.

---

## 🎙️ Speech-to-Text con el servicio nativo del móvil

La transcripción la hace **íntegramente el reconocedor de voz nativo de Android** (`SpeechRecognizer`, preferentemente on-device). No hay modelos de STT que descargar ni se usa Whisper. El audio subido se decodifica a PCM 16 kHz mono y se entrega al reconocedor por pipe (`EXTRA_AUDIO_SOURCE`), así que funciona con la pantalla apagada.

Requisitos:
- Android 13 (API 33) o superior.
- Permiso de micrófono (`RECORD_AUDIO`). El diálogo lo pide antes de iniciar el servidor.
- Para uso sin conexión, instalar el paquete de voz offline del idioma en los ajustes de voz de Android/Google.

1. Open **Pocket Node Server** in the app and start the server. Elige en *Servicio STT* si prefieres solo on-device o el motor del sistema.
2. Transcribe:
   ```bash
   curl http://YOUR_PHONE_IP:8080/v1/audio/transcriptions \
     -F "file=@audio.m4a" \
     -F "language=es" \
     -F "response_format=verbose_json"
   ```
   Campos: `file` (obligatorio), `language` (`es` por defecto; ISO o BCP-47, p. ej. `en`, `pt-BR`). `model`, `temperature`, `prompt` y `timestamp_granularities` se aceptan pero se ignoran.
   Formatos de `response_format`: `json` (por defecto), `text`, `verbose_json`, `srt`, `vtt`. En `verbose_json`/`srt`/`vtt` los segmentos son ventanas de hasta 25 s, sin confianza ni tiempos por palabra (el reconocedor nativo no los expone).
   Errores en formato OpenAI `{"error":{"message","type"}}`: `400` entrada inválida, `429` cola llena, `503` reconocedor no disponible (p. ej. falta el paquete de idioma).
   `language=auto` no detecta el idioma: usa español.

<<<<<<< HEAD
## 🛠️ Herramientas (tools) en chat completions

`POST /v1/chat/completions` acepta `tools` y `tool_choice` en formato OpenAI. Sirve con cualquier modelo: el servidor describe las herramientas en el prompt, valida la salida JSON del modelo y la devuelve como `tool_calls`.

```bash
curl http://YOUR_PHONE_IP:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"¿Tiempo en Madrid?"}],
       "tools":[{"type":"function","function":{"name":"get_weather",
         "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}]}'
```

Respuesta con llamada: `finish_reason: "tool_calls"`, `message.content: null` y `message.tool_calls[]` con `arguments` como string JSON. Envía de vuelta el resultado con un mensaje `{"role":"tool","tool_call_id":"…","name":"get_weather","content":"…"}` tras el `tool_calls` del asistente para obtener la respuesta final en texto.

- `tool_choice`: `"auto"` (por defecto), `"none"` (no llama herramientas), `"required"` o `{"type":"function","function":{"name":"…"}}`.
- Si la salida del modelo no es una llamada válida (JSON incompleto, herramienta desconocida, falta un argumento obligatorio), la respuesta es texto normal con `finish_reason: "stop"`. Nunca se devuelve una llamada inválida.
- Sin streaming. Las llamadas dependen de que el modelo siga el formato pedido; los modelos pequeños pueden no hacerlo.

=======
>>>>>>> c5e815ef99d3b4a4748cdb909369138831783242
## 🔊 Síntesis de voz (TTS) con el motor del móvil

`POST /v1/audio/speech` sintetiza voz con el motor TTS del sistema (`android.speech.tts`). Solo usa voces locales instaladas, sin conexión.

```bash
curl http://YOUR_PHONE_IP:8080/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{"input":"Hola, ¿qué tal?","voice":"alloy","response_format":"wav"}' \
  -o voz.wav
```

- `input` (obligatorio, máximo 4096 caracteres). Textos largos se sintetizan por frases y se concatenan.
- `voice`: un nombre de voz de Android (p. ej. `es-es-x-eea-local`), una etiqueta BCP-47 (`en-US`) o un nombre OpenAI (`alloy`, `nova`, …), que usa el idioma español por defecto. Los nombres OpenAI no cambian la voz.
- `speed`: 0.25 a 4.0 (por defecto 1.0).
- `response_format`: `wav` (por defecto, a la frecuencia del motor), `pcm` (PCM 16 bits mono a 24 kHz, sin cabecera) o `aac` (AAC-LC con cabecera ADTS, unas 8 veces más pequeño que WAV para voz; reproducible con VLC o ffmpeg).
- `model` se acepta e ignora.

**Diferencia con OpenAI:** el formato por defecto es `wav`, no `mp3`, y `mp3`, `opus` y `flac` devuelven 400 porque el móvil no incluye codificadores de esos formatos. `aac` usa ADTS y no un contenedor m4a. Los clientes deben fijar `response_format`.
Si no hay voz local para el idioma, la ruta devuelve 503 con instrucciones para instalarla en Ajustes > Salida de texto a voz.

---

## 🧪 Testing the API from the App

Open **Pocket Node Server** and tap **Probar todas las rutas** while the server is running. The app calls `127.0.0.1:8080` directly and checks:
- `GET /` returns HTTP 200 and `Pocket Node AI is running!`.
- `POST /v1/chat/completions` returns HTTP 200 and a non-empty `choices[0].message.content` without error markers, showing the serving model name (`Modelo: <name>`).
- `POST /v1/audio/transcriptions` (with 1 s of 16 kHz mono WAV silence) returns HTTP 200 and a JSON `text` field without error markers, showing the serving model name and the STT timing breakdown.

---

## 🤝 Credits & Acknowledgments

*   **Original Base Application**: [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) by the Google AI Edge Team.
*   **HTTP Engine**: [Ktor Server CIO](https://ktor.io/) for lightweight asynchronous networking on Android.
