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

## 🎙️ Whisper STT (Speech-to-Text)

Pocket Node can transcribe audio with **Whisper large-v3-turbo** (`litert-community/whisper-large-v3-turbo`, int4 variant, ~720 MiB). It is not a chat model. It runs through its own LiteRT engine in `server/whisper/`, not through LiteRT-LM.

1. Open **Pocket Node Server** in the app and pick **Whisper-Large-V3-Turbo-i4** in *Modelo para Transcripción (Audio STT)*.
2. If the model is not on the device, tap **Descargar**. The server button stays disabled until the download finishes.
3. Start the server, then transcribe:
   ```bash
   curl http://YOUR_PHONE_IP:8080/v1/audio/transcriptions \
     -F file=@audio.wav -F language=es
   ```
   The response is `{"text": "..."}`. Use `-F response_format=text` for plain text.

Behaviour and limits:
- `language` is an ISO code such as `es` or `en`. Without it, Whisper detects the language from the first 30 s window. Unknown codes return HTTP 500 with `Internal Server Error: Idioma no soportado por Whisper: <code>` in the body.
- Audio is decoded to 16 kHz mono, the same as the LLM STT path. The `prompt` field is ignored by Whisper.
- Audio is processed in 30 s windows and the texts are joined. Decoding has no KV cache, so it is slow on phones. Expect seconds per window.
- The model loads in the background when the server starts (if it is downloaded), so the first transcription is not slower. It is released when the server stops.
- The Whisper tokenizer (`assets/whisper/tokenizer.json`, MIT, from `openai/whisper-large-v3-turbo`) is bundled in the app.

**Manual checklist (not covered by automated tests):** the model loads on a real device, the transcript of a known clip is correct, and memory use is acceptable.

---

## 🧪 Testing the API routes from the app

The **Probar todas las rutas** button is always visible in **Pocket Node Server**. It is enabled once the server is running. The app calls each route on `127.0.0.1:8080` and shows a ✅ or ❌ with the HTTP code, latency and a short detail for:
- `GET /`: expects the health text.
- `POST /v1/chat/completions`: expects a non-empty `choices[0].message.content`.
- `POST /v1/audio/transcriptions`: sends one second of silence and expects a `text` field.

For the transcription route, the row also shows the Whisper timing breakdown (mel, encoder, and decoder steps). The server sends the same data in the `X-Whisper-Timings` response header, a diagnostic header outside the OpenAI-compatible API.

The app allows cleartext HTTP only for `localhost` and `127.0.0.1` (`res/xml/network_security_config.xml`), so the test can run without weakening the rest of the network policy.

---

## 📖 Upstream Features & Documentation

This project retains all the amazing conversational features of the standalone app, including Agentic Skills, Multimodal Image Scanning, and Audio Scribes! 

To explore the original user interface capabilities and architectural deep-dives, please read the full documentation on Google's master repository:

👉 **[Google AI Edge Gallery Official Repo & Wiki](https://github.com/google-ai-edge/gallery)**

---

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
