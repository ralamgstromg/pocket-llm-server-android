# Pocket Node (Google AI-Edge-gallery)✨
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Turn your Android phone into a true, offline AI network node.** 

Pocket Node is a customized fork of the open-source **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**. It takes the lightning-fast, on-device LiteRT inference engine of the original app and seamlessly bridges it to an embedded HTTP Server via Ktor.

Instead of burning your PC's GPU power or paying for external cloud APIs, you can now expose your phone's offline LLMs directly to your local WiFi network!

---

## 🚀 The Power of Pocket Node

*   **OpenAI-compatible API on the Go**: Pocket Node exposes an OpenAI-compatible REST API on `http://YOUR_PHONE_IP:8080/v1` (full reference: **[docs/OPENAI_API.md](docs/OPENAI_API.md)**):
    *   `GET /v1/models`, `GET /v1/models/{id}`: List the available on-device models (with download status, capabilities, and assigned roles).
    *   `POST /v1/chat/completions`: Multi-turn chat with system/developer/user/assistant/tool roles, images and audio as content parts, sampling parameters, `stop`, `n`, `response_format` (JSON / JSON Schema), reasoning (`reasoning_content`), `usage` and **SSE streaming**.
    *   `POST /v1/completions` (legacy) and `POST /v1/responses` (with streaming events).
    *   **Universal Tool Calling**: OpenAI `tools` / `tool_choice` / `tool_calls` with arguments always parsed and validated as standard JSON objects regardless of model (supports native LiteRT-LM, Hermes `<tool_call>`, Mistral `[TOOL_CALLS]`, Liquid Pythonic `<|tool_call_start|>`, Granite, and Markdown JSON).
    *   **On-Device Skills as Tools**: Pass `"x_pocket_skills": true` in chat requests to automatically expose and execute installed Android skills (e.g. hash calculation, map, wiki) inside a headless sandboxed WebView loop.
    *   `POST /v1/audio/transcriptions` and `/v1/audio/translations`: Speech-to-text with Spanish support via **Qwen3-ASR-1.7B** (`.litertlm`) and multi-format audio decoding.
    *   `POST /v1/audio/speech` & `GET /v1/audio/voices`: Text-to-speech through Android system voices (WAV output), with Spanish priority.
    *   Optional **API key** (`Authorization: Bearer`, stored encrypted with Keystore), CORS toggle, OpenAI-style error bodies.
*   **Curated Hugging Face Catalog (`litert-community`)**: Bundles `.litertlm` models with Spanish language tags and recommended roles:
    *   **STT**: `Qwen3-ASR-1.7B` (30s & 15s), `Parakeet-TDT-0.6B-v3`.
    *   **Chat & Tools**: `Qwen3.5-4B`, `Qwen3.5-2B`, `Qwen3-4B`, `Ministral-3-3B-Instruct`, `Granite-4.1-3B`, `SmolLM3-3B`.
    *   **Router / Decider**: `Qwen3.5-0.8B`, `Qwen3-0.6B`, `LFM2.5-350M`, `Decider-0.8B`.
*   **Server control panel**: a full-screen panel with four tabs:
    *   **Status**: start/stop, the **LAN base URL** with a copy button, configurable port, models in memory, free RAM and a ready-to-paste `curl` test.
    *   **Models**: one selector per role (chat, completions, speech-to-text, vision, router) with direct **Download** buttons, Spanish (`[ES]`) and Recommended (`[REC]`) badges, TTS language and speed, model aliases (`gpt-4o=Qwen3.5-4B`) and import of your own `model_allowlist.json`.
    *   **Security**: API key (show, generate, copy) and CORS.
    *   **Activity**: the latest requests with status, duration and the model that answered.
*   **Choose what serves each request**: a request's `model` field wins; otherwise the slot for its kind of request (audio, image, completions, chat). A small **router** model can pick the best model when a client sends `model: "auto"`. Matching is exact (never by substring).
*   **Local Allowlist Asset Bundling**: Bundles `assets/model_allowlists/1_0_11.json` inside the APK assets. Models defined in local allowlists (like Whisper-Large-V3-Turbo) load instantly in the app's download manager without depending on internet GitHub raw files.
*   **Speech-to-Text (STT) & Whisper Support**: Optimized for `litert-community/whisper-large-v3-turbo` (`whisper_large_v3_turbo_30s_i4.tflite`) and multimodal LiteRT-LM models (e.g., Gemma 3n / Gemma 4), supporting accurate Spanish (`language=es`) and multilingual audio transcription.
*   **Zero Cloud, Zero Cost**: 100% on-device inference using your Android's NPU/GPU/CPU. Completely offline and private.

---

## 🏁 Get Started in Minutes

1. **Clone & Build**: Clone this repository and build the APK using Android Studio or Gradle CLI.
2. **Download Models**: Open the app and download your preferred models (e.g. Gemma 4 for Chat, `Whisper-Large-V3-Turbo` for Speech-to-Text).
3. **Configure & Start Server**: Open the left-side navigation drawer, tap **Pocket Node Server** to open the server panel:
   - **Models** tab: choose the model for chat, speech-to-text, and the rest (or leave them on Automatic).
   - **Security** tab: generate an API key.
   - **Status** tab: tap **Start server**, then copy the base URL shown there.
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
