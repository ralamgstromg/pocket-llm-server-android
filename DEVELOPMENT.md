# Development notes

## Build app locally

To successfully build and run the application through Android Studio, you need to configure it with your own HuggingFace Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)). This is required for the model download functionality to work correctly.

After you've created a developer application:

1. In [`ProjectConfig.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the placeholders for `clientId` and `redirectUri` with the values from your HuggingFace developer application.

1. In [`app/build.gradle.kts`](https://github.com/google-ai-edge/gallery/blob/c1b50e160a66d5ea2ec2d8d8e63088b3cc0761bc/Android/src/app/build.gradle.kts#L41-L44), modify the `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured in your HuggingFace developer application.

## Build the APK from the command line

Run `scripts/build-apk.sh` from the repo root. It installs JDK 21 and the Android SDK components if they are missing (Linux only) and builds the release APK. Use `--debug` for the debug APK. See the README section "Compiling & Building the APK" for the manual steps.

## Server Architecture (`com.google.ai.edge.gallery.server`)

- **`PocketNodeService`**: Foreground service keeping the Ktor server alive and preloading selected chat/audio models on start.
- **`PocketNodeServer`**: Embedded Ktor CIO server on port `8080` exposing `GET /`, `POST /v1/chat/completions`, and `POST /v1/audio/transcriptions`. Preloads both active models immediately via `preloadActiveModels`, resets conversation state per stateless HTTP request, defaults to Spanish (`es`), emits `X-Model-Name` and `X-Whisper-Timings` diagnostic headers, and logs every operation to `PocketNodeLogger`.
- **`PocketNodeLogger`**: Ring-buffered (500 entries) and disk-backed (`cacheDir/logs/pocket_node_api.log`) API operation logger supporting enable/disable toggle, `FileProvider` sharing (`shareLogs`), and SAF document export (`exportToUri`).
- **`PocketNodeState`**: Singleton state & `SharedPreferences` manager isolating `activeChatModel` and `activeAudioModel` (`RuntimeType.TFLITE_WHISPER` is never assigned to chat).
- **`PocketNodeInference`**: Unified model loader (`preloadModel`, `initializeLlm`) with GPU-first initialization (`preferredAccelerator`), low-latency serving config (`applyFastServingConfig`: `ENABLE_THINKING=false`, `topK=16`, `topP=0.85`, `temperature=0.4`, `maxTokens=1024`, vision encoder disabled for API), and default Spanish system instruction (`DEFAULT_SPANISH_SYSTEM_PROMPT`).
- **`PocketNodeModelResolver`**: Merges local disk `model_allowlist.json` with bundled `assets/model_allowlists/1_0_11.json` and upgrades stale CPU-only disk entries to GPU when the bundled asset supports GPU.
- **`PocketNodeApiTester` & `PocketNodeServerDialog`**: Self-test runner against `127.0.0.1:8080` and Compose configuration/diagnostics/logs UI.
- **`whisper/` (`WhisperEngine`, `WhisperFeatureExtractor`, `WhisperTokenizer`)**: On-device LiteRT 2.1.0 (`tflite_whisper`) pipeline for `Whisper-Large-V3-Turbo-i4` with 128-mel Slaney spectrogram extraction, BPE decoding, XNNPACK acceleration, and Spanish (`es`) default language.

## Testing, Code Indexing & Security Verification

- **Unit Tests**: Run `JAVA_HOME=/path/to/jdk-21 ./gradlew testDebugUnitTest` inside `Android/src` (covers `PocketNodeApiTesterTest`, `PocketNodeLoggerTest`, `PocketNodeModelResolverTest`, `WhisperDecodeBudgetTest`, `WhisperFeatureExtractorTest`, `WhisperTokenizerTest`).
- **CodeGraph**: Run `codegraph sync` at the start and end of tasks to keep `.codegraph/` synchronized.
- **Graphify**: Run `graphify extract . --code-only` and `graphify cluster-only .` to update `graphify-out/` artifacts.
- **Security Scan**: Run `trivy fs --scanners vuln,secret,misconfig .` to verify zero leaked secrets or misconfigurations.
