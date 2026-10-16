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
- **`PocketNodeServer`**: Embedded Ktor CIO server on port `8080` (`Dispatchers.IO.limitedParallelism(2)`) exposing `GET /`, `POST /v1/chat/completions`, and `POST /v1/audio/transcriptions`. Preloads active models immediately via `preloadActiveModels` (respecting `hasEnoughRamForDualResidency`), isolates each request in its own disposable LiteRT-LM `Conversation`, defaults to Spanish (`es`) and `8000` max tokens (overridable via UI or per-request `max_tokens`), emits `X-Model-Name` and `X-STT-Timings` diagnostic headers, and logs every operation to `PocketNodeLogger`.
- **`PocketNodeLogger`**: Ring-buffered (500 entries) and disk-backed (`cacheDir/logs/pocket_node_api.log`) API operation logger supporting enable/disable toggle, `FileProvider` sharing (`shareLogs`), and SAF document export (`exportToUri`).
- **`PocketNodeState`**: Singleton state & `SharedPreferences` manager isolating `activeChatModel`, `activeAudioModel` (`RuntimeType.TFLITE_WHISPER` is never assigned to chat), `configuredMaxTokens` (`DEFAULT_MAX_TOKENS = 8000`, range `256..32768`), and `configuredIdleMinutes` (`DEFAULT_IDLE_TIMEOUT_MINUTES = 3`, range `1..60`).
- **`PocketNodeInference`**: Unified model loader and isolated execution manager (`preloadModel`, `initializeLlm`, `runIsolatedLlmInference`, `buildChatPromptContext`, `closeIdleConversation`, `hasEnoughRamForDualResidency`, `guardMemoryBeforeLoad`) with GPU-first initialization (`preferredAccelerator`), zero KV-cache retention when idle (`LlmModelInstance.conversation = null`), configurable token window (`8000` default), control-token sanitization (`sanitizeControlTokens`, `sanitizeModelOutput`), global per-model `Mutex`, on-demand `PARTIAL_WAKE_LOCK` (`withInferenceWakeLock`), and configurable idle hibernation (`configuredIdleMinutes`, default 3 minutes) to save battery.
- **`PocketNodeModelResolver`**: Merges local disk `model_allowlist.json` with bundled `assets/model_allowlists/1_0_11.json` and upgrades stale CPU-only disk entries to GPU when the bundled asset supports GPU.
- **`PocketNodeApiTester` & `PocketNodeServerDialog`**: Self-test runner against `127.0.0.1:8080` and Compose configuration/diagnostics/logs UI (including editable max-tokens input, preset chips `2048`/`4096`/`8000`/`16384`, and idle hibernation chips `1m`/`3m`/`5m`/`15m`).
- **`whisper/` (`WhisperEngine`, `WhisperFeatureExtractor`, `WhisperTokenizer`)**: On-device LiteRT 2.1.0 (`tflite_whisper`) pipeline for `Whisper-Large-V3-Turbo-i4` with 128-mel Slaney spectrogram extraction, BPE decoding, XNNPACK acceleration (`2..4` battery-balanced threads), and Spanish (`es`) default language.

## Testing, Code Indexing & Security Verification

- **Unit Tests**: Run `JAVA_HOME=/path/to/jdk-21 ./gradlew testDebugUnitTest` inside `Android/src` (covers `PocketNodeApiTesterTest`, `PocketNodeLoggerTest`, `PocketNodeModelResolverTest`, `AndroidNativeSttEngineTest`, `AudioDecoderHelperTest`, `TranscriptionFormatsTest`, and the legacy `whisper/*` tests that go away with the Whisper removal).
- **CodeGraph**: Run `codegraph sync` at the start and end of tasks to keep `.codegraph/` synchronized.
- **Graphify**: Run `graphify extract . --code-only` and `graphify cluster-only .` to update `graphify-out/` artifacts.
- **Security Scan**: Run `trivy fs --scanners vuln,secret,misconfig .` to verify zero leaked secrets or misconfigurations.
