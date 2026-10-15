# Model Allowlists Documentation

This directory contains the allowlist configurations for supported on-device models in Pocket Node (Google AI Edge Gallery).

## Active Allowlist: `1_0_11.json`

The active allowlist only supports model files in the `.litertlm` format, optimized for on-device inference with **LiteRT-LM**.

### Supported Models Summary

| Model Name | Model ID | Model File (`.litertlm`) | Size (Bytes) | Minimum RAM | Commit Hash |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Qwen3-ASR-1.7B** (STT ES/Multi) | `litert-community/Qwen3-ASR-1.7B` | `Qwen3-ASR-1.7B.litertlm` | 2,693,064,192 | 8 GB | `184ec0f1ef8c8b5254100e95ec713b5557fb91de` |
| **Parakeet-TDT-0.6B-v3** (STT) | `litert-community/parakeet-tdt-0.6b-v3` | `parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm` | 614,588,752 | 4 GB | `6301c08d8d2c233e90ccbd8f193392abe191a0b3` |
| **Ministral-3-3B-Instruct** (Chat/Tools) | `litert-community/Ministral-3-3B-Instruct-2512` | `Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm` | 2,342,866,928 | 8 GB | `f0d5ce0bf03a85e905b0590330fb69ae72784559` |
| **Granite-4.1-3B** (Chat/Tools) | `litert-community/granite-4.1-3b` | `granite-4.1-3b_int4.litertlm` | 2,191,445,936 | 8 GB | `99121c48b85295847c9ebe2d73b956a1e07bd2b5` |
| **Qwen3.5-0.8B** (Router/Chat) | `litert-community/Qwen3.5-0.8B` | `Qwen3.5-0.8B_int8.litertlm` | 964,861,904 | 4 GB | `efb93fca20bc4b65df078b2b0a365d3350b3dcfb` |
| **LFM2.5-350M** (Router) | `litert-community/LFM2.5-350M` | `LFM2.5-350M_int4.litertlm` | 247,747,696 | 3 GB | `0fdead63ee63cd01703adb5f085d0a72b0cb5e20` |
| **SmolLM3-3B** (Chat) | `litert-community/SmolLM3-3B` | `SmolLM3-3B_q4_block32_ekv4096.litertlm` | 2,004,633,520 | 8 GB | `7621a4f099a00b0518e0a6e7c5628af08ab4184e` |
| **Gemma-4-E2B-it** | `litert-community/gemma-4-E2B-it-litert-lm` | `gemma-4-E2B-it.litertlm` | 2,588,147,712 | 8 GB | `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` |
| **Gemma-4-E4B-it** | `litert-community/gemma-4-E4B-it-litert-lm` | `gemma-4-E4B-it.litertlm` | 3,659,530,240 | 12 GB | `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` |
| **Qwen3.5-4B** | `litert-community/Qwen3.5-4B` | `Qwen3.5-4B_mixed_int4.litertlm` | 2,754,365,536 | 8 GB | `6e49632c02747372da061e86016c68a980753d0e` |

## Applied Changes Log

1. **Updated Commit Hashes & Sizes**: Updated existing `.litertlm` model definitions (`Gemma-4`, `Gemma-3n`, `Gemma3-1B`, `DeepSeek-R1-Distill-Qwen-1.5B`, `TinyGarden-270M`, `MobileActions-270M`) with their latest Hugging Face repository commit hashes and exact byte sizes.
2. **Added New Models**:
   - `Gemma3-270M-IT` (`litert-community/gemma-3-270m-it`)
   - `Qwen3-0.6B` (`litert-community/Qwen3-0.6B`)
   - `LFM2.5-1.2B-Instruct` (`litert-community/LFM2.5-1.2B-Instruct`)
   - `Phi-4-mini-instruct` (`litert-community/Phi-4-mini-instruct`)
3. **Format Restriction**: Confirmed that all models in `1_0_11.json` strictly use the `.litertlm` format.
4. **Single-Instance Multimodal Model Architecture**:
   - Updated `LlmChatTaskModule.kt` to pass `supportImage = model.llmSupportImage` and `supportAudio = model.llmSupportAudio` during model initialization, allowing single-process multimodal support.
   - Updated `PocketNodeState.kt` with `syncSharedModels()` so that `activeChatModel` and `activeAudioModel` reference the exact same `Model` instance in memory when a multimodal model (e.g. Gemma 4, Gemma 3n) is selected.
   - Updated `PocketNodeServer.kt` so `/v1/chat/completions` and `/v1/audio/transcriptions` route requests to the shared single model instance.
   - Updated `PocketNodeServerDialog.kt` to automatically link chat and audio selection for multimodal audio models, avoiding double RAM allocation.
   - Synced bundled assets in `Android/src/app/src/main/assets/model_allowlists/1_0_11.json`.
5. **Multi-Format Audio Decoding in STT API (`AudioDecoderHelper.kt`)**:
   - Implemented native `MediaExtractor` + `MediaCodec` audio decoder helper in `AudioDecoderHelper.kt`.
   - Supports decoding uploaded MP3, M4A, AAC, OGG, FLAC, WAV, AMR, WebM audio bytes into 16-bit 16kHz Mono PCM WAV data.
   - Integrated `AudioDecoderHelper` into `/v1/audio/transcriptions` endpoint in `PocketNodeServer.kt`.
6. **Automatic On-the-Fly Model Initialization**:
   - Updated `PocketNodeServerDialog.kt` button action to automatically load downloaded models into RAM (`model.instance`) when clicking **Iniciar Servidor HTTP**, displaying a progress indicator ("⏳ Cargando modelo...").
   - Updated `PocketNodeServer.kt` route handlers (`/v1/chat/completions` and `/v1/audio/transcriptions`) to auto-initialize `model.instance` on-the-fly if an HTTP request arrives while the model is not yet loaded in memory.
7. **PocketNodeModelResolver Auto-Discovery & Request Model Matching**:
   - Created `PocketNodeModelResolver.kt` to auto-parse `1_0_11.json` and resolve downloaded models automatically when `PocketNodeService` or Ktor starts, preventing `No active model initialized` errors even if the user never opened the server dialog.
   - Support for dynamic `req.model` matching in `/v1/chat/completions` payload to match requested model names against allowlist.
8. **Resilient Vision/Audio Encoder Fallback in LiteRT-LM (`LlmChatModelHelper.kt`)**:
   - Fixed `INVALID_ARGUMENT: The Vision Encoder model must have exactly one signature but got 3` error when initializing models with `llmSupportImage` flag in `.litertlm` files without vision signatures.
   - Implemented automatic retry/fallback in `LlmChatModelHelper.kt`: if initializing `EngineConfig` with `visionBackend` or `audioBackend` fails due to signature/encoder mismatch, `LlmChatModelHelper` gracefully retries in text-only mode (`visionBackend = null`), guaranteeing successful model initialization across all model architectures.
9. **Observable Reactive Server State (`PocketNodeState.kt` & `PocketNodeService.kt`)**:
   - Converted `PocketNodeState.isServerRunning` to a Jetpack Compose observable state (`var isServerRunning by mutableStateOf(false)`).
   - Updated `PocketNodeService.kt` to set `isServerRunning = true` on `onStartCommand` and `isServerRunning = false` on `onDestroy`, ensuring immediate real-time UI state recomposition in `PocketNodeServerDialog.kt` (updating badge to **🟢 Servidor HTTP Activo (http://localhost:8080)** and button text to **"Detener Servidor"**).
10. **Strict Spoken Text Sanitization in Transcribe API (`PocketNodeServer.kt`)**:
   - Updated prompt system instruction for STT to instruct model: *"Output ONLY the verbatim spoken text from this audio. Do NOT include any intro, explanation, labels, or extra words."*
   - Added `sanitizeSpokenText(rawInput: String)` in `PocketNodeServer.kt` to filter out reasoning tags (`<think>...</think>`), code blocks, intro labels (`"Transcription:"`, `"El usuario dijo:"`), and surrounding quotes before returning the final response.
11. **Anti-Crash & Resource Cleanup Protections (`AudioDecoderHelper.kt` & `PocketNodeServer.kt`)**:
   - Guaranteed full cleanup of `MediaCodec`, `MediaExtractor`, and temporary audio files using strict nested `try-finally` blocks, preventing Android file descriptor and C++ native memory leaks.
   - Added `serverMutexMap` with `Mutex.withLock` to serialize concurrent HTTP inference requests per model, preventing native `SIGABRT` / `SIGSEGV` LiteRT-LM C++ crashes.
   - Guarded `suspendCoroutine` resumptions with `AtomicBoolean` to prevent `IllegalStateException: Already resumed` crashes.
   - Wrapped route handlers in global `try-catch (t: Throwable)` to gracefully return HTTP 500 JSON error responses instead of crashing the process.
12. **Code Deduplication & Server Simplification (`PocketNodeServer.kt`)**:
   - Extracted `resolveAndInitModel()` and `executeModelInference()` helper methods to centralize model resolution, auto-initialization, locking, and coroutine resumption.
   - Eliminated over 120 lines of duplicate code between `/v1/chat/completions` and `/v1/audio/transcriptions`.
13. **HTTP Web Server Startup Exception Protections & UI Error Display (`PocketNodeService.kt`, `PocketNodeServer.kt`, `PocketNodeServerDialog.kt`)**:
   - Fixed Android 14+ (`API 34/35`) Foreground Service type crash by passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` to `startForeground(...)`.
   - Wrapped Ktor `embeddedServer(CIO, port = 8080)` startup in `try-catch` to capture socket/port bind exceptions safely, recording `PocketNodeState.lastStartupError`.
   - Added `LaunchedEffect(PocketNodeState.lastStartupError)` in `PocketNodeServerDialog.kt` to display exact error details in `statusMessage` with `❌` icon instead of crashing the app.
14. **Qwen3.5-4B (Mixed INT4) Model**:
   - Added `Qwen3.5-4B` (`litert-community/Qwen3.5-4B`, file `Qwen3.5-4B_mixed_int4.litertlm`, commit `1f89ed8a`) to `1_0_11.json` and to the bundled asset copy (both files are byte-identical).
   - Hybrid GatedDeltaNet architecture, **text only** (no vision/audio), 4096-token context, thinking switch (`llmSupportThinking: true`, reasoning returned on the `thought` channel, off by default).
   - Defaults: `topK 20`, `topP 0.8`, `temperature 0.7`, `maxTokens 4096`, `accelerators "cpu,gpu"`. The model card recommends the **CPU backend on 8 GB phones** (measured ~4.4 GB peak on a Pixel 8a) and has not verified GPU on Qualcomm Adreno.
   - Requires **LiteRT-LM >= 0.15**, which is why the runtime was upgraded to 0.18.0 (see item 15).
   - Its bundle declares `supports_function_calling=false`: tool calls come back as `<tool_call>` text and are parsed by the server fallback (see item 16).
15. **Toolchain & Library Upgrade**:
   - Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin: the `org.jetbrains.kotlin.android` plugin was removed), Kotlin 2.4.21, KSP 2.3.12; Hilt moved from `kapt` to KSP.
   - JDK 27 toolchain; Java/Kotlin bytecode target 25 (Kotlin 2.4.21 supports at most `JVM_26`).
   - compileSdk/targetSdk 37; removed the `<uses-sdk>` element from `AndroidManifest.xml` (rejected by AGP 9).
   - All catalog libraries at their latest stable version (LiteRT-LM 0.18.0, Ktor 3.6.0, Compose BOM 2026.09.00, Hilt 2.60.1, Firebase BoM 35.0.0, CameraX 1.6.2, protobuf 4.36.2, ...).
   - Fixed new Compose lint errors (`NonObservableLocale` in benchmark viewers, unused `AnimatedContent` target state in Mobile Actions).
   - CI (`.github/workflows/build_android.yaml`): JDK 27, `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`.
16. **OpenAI-Compatible REST API & Native Tool Calling** (`server/openai/*`, `PocketNodeServer.kt`) — full reference in [`docs/OPENAI_API.md`](../docs/OPENAI_API.md):
   - `/v1/chat/completions` now honours the full message history (stateless per-request LiteRT-LM `Conversation`), system/developer/tool roles, image/audio content parts, sampling parameters, `stop`, `response_format`, `reasoning_effort`, `usage` and SSE streaming.
   - Native tools via LiteRT-LM `OpenApiTool` with `automaticToolCalling=false`; `tool_calls` / `tool_choice` / `parallel_tool_calls`; tool results as `Content.ToolResponse`.
   - New `/v1/models` endpoints, OpenAI error bodies, 20 MB body limit enforced while streaming the body, optional API key (excluded from backups) and CORS toggle (off by default) in the server dialog.
   - Review hardening: think-tag splitting in streaming, restore of the in-app conversation configuration after a borrow, unbounded delta buffer, serialized model loading, `finish_reason: "length"` from estimated tokens, `top_p: 0` accepted.
   - `PocketNodeService` now runs as a `specialUse` foreground service (API 34+) with `onTimeout()` handling.
   - `PocketNodeModelResolver.findModelByNameOrId` now prefers exact name/file matches.
