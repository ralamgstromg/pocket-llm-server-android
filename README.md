# Pocket Node (Google AI-Edge-gallery)✨
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Turn your Android phone into a true, offline AI network node.** 

Pocket Node is a customized fork of the open-source **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**. It takes the lightning-fast, on-device LiteRT inference engine of the original app and seamlessly bridges it to an embedded HTTP Server via Ktor.

Instead of burning your PC's GPU power or paying for external cloud APIs, you can now expose your phone's offline LLMs directly to your local WiFi network!

---

## 🚀 The Power of Pocket Node

*   **OpenAI-compatible API on the Go**: Pocket Node exposes an OpenAI-compatible REST API on `http://YOUR_PHONE_IP:8080/v1` (full reference: **[docs/OPENAI_API.md](docs/OPENAI_API.md)**):
    *   `GET /v1/models`, `GET /v1/models/{id}`: List the available on-device models (with download status).
    *   `POST /v1/chat/completions`: Multi-turn chat with system/developer/user/assistant/tool roles, images and audio as content parts, sampling parameters, `stop`, `response_format` (JSON / JSON Schema), reasoning (`reasoning_content`), `usage` and **SSE streaming**.
    *   **Native tool calling**: OpenAI `tools` / `tool_choice` / `tool_calls` mapped to LiteRT-LM 0.18 `OpenApiTool` (plus a `<tool_call>` text fallback for generic models).
    *   `POST /v1/audio/transcriptions`: Speech-to-Text (STT) audio transcription compatible with OpenAI.
    *   Optional **API key** (`Authorization: Bearer`) and CORS toggle, OpenAI-style error bodies.
*   **Interactive Model Configuration UI**: Select your desired active models directly in the app before starting the HTTP server:
    *   **Chat / Completions Model**: Dedicated model selector for text generation.
    *   **Audio STT Model**: Dedicated model selector for speech-to-text (e.g. `Whisper-Large-V3-Turbo`).
    *   **Persistent Preferences**: Model choices are saved in `SharedPreferences` and automatically initialized when starting `PocketNodeService`.
*   **Local Allowlist Asset Bundling**: Bundles `assets/model_allowlists/1_0_11.json` inside the APK assets. Models defined in local allowlists (like Whisper-Large-V3-Turbo) load instantly in the app's download manager without depending on internet GitHub raw files.
*   **OpenAI-Compatible Schema**: Designed to cleanly integrate with your existing scripts, Python backends, and low-code wrappers (like `n8n`) using standard JSON / Multipart schemas.
*   **Speech-to-Text (STT) & Whisper Support**: Optimized for `litert-community/whisper-large-v3-turbo` (`whisper_large_v3_turbo_30s_i4.tflite`) and multimodal LiteRT-LM models (e.g., Gemma 3n / Gemma 4), supporting accurate Spanish (`language=es`) and multilingual audio transcription.
*   **Dual Active Model Management**: `PocketNodeState` manages `activeChatModel` and `activeAudioModel` independently.
*   **Zero Cloud, Zero Cost**: 100% on-device inference using your Android's NPU/GPU/CPU. Completely offline and private.

---

## 🏁 Get Started in Minutes

1. **Clone & Build**: Clone this repository and build the APK using Android Studio or Gradle CLI.
2. **Download Models**: Open the app and download your preferred models (e.g. Gemma 4 for Chat, `Whisper-Large-V3-Turbo` for Speech-to-Text).
3. **Configure & Start Server**: Open the left-side navigation drawer, tap **Pocket Node Server** to open the server dialog:
   - Choose your **Chat / Completions Model**.
   - Choose your **Audio STT Model**.
   - Tap **Iniciar Servidor HTTP** to ignite the background service!
4. **Test it out**:
   - **List models**:
     ```bash
     curl http://<YOUR_PHONE_IP>:8080/v1/models
     ```
   - **Any OpenAI SDK** (Python shown; streaming and tools work too):
     ```python
     from openai import OpenAI
     client = OpenAI(base_url="http://<YOUR_PHONE_IP>:8080/v1", api_key="<your key or anything>")
     r = client.chat.completions.create(model="Qwen3.5-4B", messages=[{"role": "user", "content": "Hola!"}])
     print(r.choices[0].message.content)
     ```
   - **Text Completion**:
     ```bash
     curl http://<YOUR_PHONE_IP>:8080/v1/chat/completions \
       -H "Content-Type: application/json" \
       -d '{
         "messages": [
           {
             "role": "user",
             "content": "Explain quantum computing in one sentence."
           }
         ]
       }'
     ```
   - **Audio Transcription (Spanish / STT)**:
     ```bash
     curl http://<YOUR_PHONE_IP>:8080/v1/audio/transcriptions \
       -H "Content-Type: multipart/form-data" \
       -F "file=@audio_espanol.mp3" \
       -F "model=whisper-large-v3-turbo" \
       -F "language=es" \
       -F "response_format=json"
     ```

---

## 🛠️ Compiling & Building the APK (CLI)

### Requirements
- **JDK 27** (Temurin 27). Gradle 9.8.1 runs on it; the app bytecode targets Java 25 (the highest target supported by Kotlin 2.4.21). Example: `mise use -g java@temurin-27`.
- **Android SDK** with `platforms;android-37.0` and `build-tools;37.0.0` (configured in `Android/src/local.properties`).
- Toolchain versions: Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.12, compileSdk/targetSdk 37, minSdk 31, LiteRT-LM 0.18.0. See [DEVELOPMENT.md](DEVELOPMENT.md).

### 1. Configure SDK Path
Ensure `Android/src/local.properties` contains your Android SDK location:
```properties
sdk.dir=/home/<you>/Android/Sdk
```

*Note: All `./gradlew` commands must be executed from the `Android/src` directory (from the repo root run `cd Android/src`, or if you are inside `Android/` run `cd src`).*

### 2. Build Debug APK
To build the debug APK:
```bash
cd Android/src
JAVA_HOME=$(mise where java@temurin-27) ./gradlew assembleDebug
```
The output APK will be generated at:
`Android/src/app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK
To build the release APK:
```bash
cd Android/src
JAVA_HOME=$(mise where java@temurin-27) ./gradlew assembleRelease
```
The output APK will be generated at:
`Android/src/app/build/outputs/apk/release/app-release.apk`

### 4. Tests & Lint
```bash
cd Android/src
./gradlew :app:testDebugUnitTest   # OpenAI API parser/mapper unit tests
./gradlew :app:lintDebug
```

---

## 📖 Upstream Features & Documentation

This project retains all the amazing conversational features of the standalone app, including Agentic Skills, Multimodal Image Scanning, and Audio Scribes! 

To explore the original user interface capabilities and architectural deep-dives, please read the full documentation on Google's master repository:

👉 **[Google AI Edge Gallery Official Repo & Wiki](https://github.com/google-ai-edge/gallery)**

---

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
