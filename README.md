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
*   **OpenAI-Compatible Schema**: Designed to cleanly integrate with your existing scripts, Python backends, and low-code wrappers (like `n8n`) using standard JSON / Multipart schemas.
*   **Speech-to-Text (STT) & Whisper Support**: Optimized for `litert-community/whisper-large-v3-turbo` and multimodal LiteRT-LM models (e.g., Gemma 3n / Gemma 4), supporting accurate Spanish (`language=es`) and multilingual audio transcription.
*   **Dual Active Model Management**: `PocketNodeState` manages `activeChatModel` and `activeAudioModel` independently, enabling a dedicated STT engine alongside your LLM chat model.
*   **Zero Cloud, Zero Cost**: 100% on-device inference using your Android's NPU/GPU/CPU. Completely offline and private.

## 🏁 Get Started in Minutes

1. **Clone & Build**: Clone this repository and build the APK using Android Studio.
2. **Launch Models**: Open the app and load your preferred models (e.g. Gemma 4 for Chat, `Whisper-Large-V3-Turbo` or Gemma 3n for Speech-to-Text).
3. **Ignite the Server**: Open the left-side navigation drawer and tap **Pocket Node Server** to instantly turn your phone into an AI API server!
4. **Test it out**:
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

## 🛠️ Compiling & Building the APK (CLI)

### Requirements
- **JDK 21** (e.g. OpenJDK 21). Java 21 is required for compatibility with `LiteRT-LM` class bytecode.
- **Android SDK** (configured in `Android/src/local.properties`).

### 1. Configure SDK Path
Ensure `Android/src/local.properties` contains your Android SDK location:
```properties
sdk.dir=/home/rcastro/android-sdk
```

*Note: All `./gradlew` commands must be executed from the `Android/src` directory (from the repo root run `cd Android/src`, or if you are inside `Android/` run `cd src`).*


### 2. Build Debug APK
To build the debug APK:
```bash
cd Android/src
JAVA_HOME=/home/rcastro/.jdks/jdk-21.0.6+7 ./gradlew assembleDebug
```
The output APK will be generated at:
`Android/src/app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK
To build the release APK:
```bash
cd Android/src
JAVA_HOME=/home/rcastro/.jdks/jdk-21.0.6+7 ./gradlew assembleRelease
```
The output APK will be generated at:
`Android/src/app/build/outputs/apk/release/app-release.apk`

## 📖 Upstream Features & Documentation

This project retains all the amazing conversational features of the standalone app, including Agentic Skills, Multimodal Image Scanning, and Audio Scribes! 

To explore the original user interface capabilities and architectural deep-dives, please read the full documentation on Google's master repository:

👉 **[Google AI Edge Gallery Official Repo & Wiki](https://github.com/google-ai-edge/gallery)**

---

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

