# Model Allowlists Documentation

This directory contains the allowlist configurations for supported on-device models in Pocket Node (Google AI Edge Gallery).

## Active Allowlist: `1_0_11.json`

The active allowlist only supports model files in the `.litertlm` format, optimized for on-device inference with **LiteRT-LM**.

### Supported Models Summary

| Model Name | Model ID | Model File (`.litertlm`) | Size (Bytes) | Minimum RAM | Commit Hash |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Gemma-4-E2B-it** | `litert-community/gemma-4-E2B-it-litert-lm` | `gemma-4-E2B-it.litertlm` | 2,588,147,712 | 8 GB | `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94` |
| **Gemma-4-E4B-it** | `litert-community/gemma-4-E4B-it-litert-lm` | `gemma-4-E4B-it.litertlm` | 3,659,530,240 | 12 GB | `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` |
| **Gemma-3n-E2B-it** | `google/gemma-3n-E2B-it-litert-lm` | `gemma-3n-E2B-it-int4.litertlm` | 3,655,827,456 | 8 GB | `c03b6f60b8da6c5400b6838a2cf26420f80c0a01` |
| **Gemma-3n-E4B-it** | `google/gemma-3n-E4B-it-litert-lm` | `gemma-3n-E4B-it-int4.litertlm` | 4,919,541,760 | 12 GB | `297ed75955702dec3503e00c2c2ecbbf475300bc` |
| **Gemma3-1B-IT** | `litert-community/Gemma3-1B-IT` | `gemma3-1b-it-int4.litertlm` | 584,417,280 | 6 GB | `6d54daa71cfbffba6b2843c08eeb1a27e7430bf0` |
| **Gemma3-270M-IT** | `litert-community/gemma-3-270m-it` | `gemma3-270m-it-q8.litertlm` | 304,005,120 | 4 GB | `9d2093270fb5aa49a986b49b5779d763dde7b630` |
| **Qwen2.5-1.5B-Instruct** | `litert-community/Qwen2.5-1.5B-Instruct` | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1,597,931,520 | 6 GB | `19edb84c69a0212f29a6ef17ba0d6f278b6a1614` |
| **Qwen3-0.6B** | `litert-community/Qwen3-0.6B` | `qwen3_0_6b_mixed_int4.litertlm` | 497,664,000 | 6 GB | `8414150f2e9dcc82449bcc9c5abc404b399a4d06` |
| **DeepSeek-R1-Distill-Qwen-1.5B** | `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` | `DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm` | 1,833,451,520 | 6 GB | `2f8b8ee90d8f93b15305b699e8772b277d074a9a` |
| **LFM2.5-1.2B-Instruct** | `litert-community/LFM2.5-1.2B-Instruct` | `LFM2.5-1.2B-Instruct_int4.litertlm` | 736,015,744 | 6 GB | `82dd33e3afbdc92853293212a08d5aaa6d466c85` |
| **Phi-4-mini-instruct** | `litert-community/Phi-4-mini-instruct` | `Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 3,910,090,752 | 8 GB | `8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e` |
| **TinyGarden-270M** | `litert-community/functiongemma-270m-ft-tiny-garden` | `tiny_garden_q8_ekv1024.litertlm` | 288,964,608 | 6 GB | `aca35636dccadc77499c9843d9ff044b7e06566e` |
| **MobileActions-270M** | `litert-community/functiongemma-270m-ft-mobile-actions` | `mobile_actions_q8_ekv1024.litertlm` | 288,964,608 | 6 GB | `f1c7b940a5a2598fb940648fb3cfcc745b18184b` |

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