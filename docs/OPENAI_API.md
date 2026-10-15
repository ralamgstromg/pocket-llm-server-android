# Pocket Node — OpenAI-compatible REST API

Pocket Node serves an OpenAI-compatible HTTP API on `http://<PHONE_IP>:<PORT>/v1` (default port 8080, configurable in the app). Any OpenAI client can use it by setting the base URL: the official `openai` Python and JS SDKs, LangChain, n8n, Open WebUI, and others.

The server runs inside `PocketNodeService`, a foreground service built on Ktor CIO. Inference runs on the device through **LiteRT-LM 0.18**.

- Code: `Android/src/app/src/main/java/com/google/ai/edge/gallery/server/`
  - `PocketNodeServer.kt`: routing, authentication, request log, SSE with keep-alive, model loading and unloading.
  - `ChatRunner.kt`: one inference for chat, completions and responses (live streaming or buffered, timeout, tool-call resolution).
  - `ModelSlots.kt`: `ModelRole`, per-role model slots, aliases, name matching, router prompt.
  - `PocketNodeState.kt`: settings (port, API key, CORS, TTS), slots and the request log.
  - `PocketNodeModelResolver.kt`: allowlist loading/import and `pick()` (which model serves a request).
  - `TtsSynthesizer.kt`: `/v1/audio/speech` on the Android speech engine.
  - `PocketNodeServerScreen.kt`: the control panel UI (Status, Models, Security, Activity).
  - `openai/OpenAiModels.kt`, `CompletionsApi.kt`, `ResponsesApi.kt`: DTOs and request/response mapping.
  - `openai/ChatRequestParser.kt`: validates requests and converts them to `ParsedChatRequest`.
  - `openai/LiteRtLmMapper.kt`, `ChatCompletionEngine.kt`: OpenAI to LiteRT-LM mapping; one stateless `Conversation` per request.
  - `openai/ResponseUtils.kt`: `<tool_call>` parser, stop sequences, `<think>` handling, SSE, API key check.
  - `openai/ToolCallStreamFilter.kt`, `ThinkStreamSplitter.kt`, `TranscriptSanitizer.kt`: streaming and cleanup helpers.
- Tests: `Android/src/app/src/test/java/com/google/ai/edge/gallery/server/` (86 JVM tests). Run them with `./gradlew :app:testDebugUnitTest` from `Android/src`.

## Authentication and security

| Setting | Where | Default |
|---|---|---|
| API key (`Authorization: Bearer <key>`) | Server screen, **Security** tab (has a Generate button) | Empty, so no authentication |
| CORS (calls from browsers on other origins) | **Security** tab, "Allow browser apps (CORS)" | **Disabled** (turn it on only for browser clients, ideally with an API key) |
| Port | **Status** tab | 8080 (1024–65535) |
| Maximum body size | `MAX_BODY_BYTES` in `PocketNodeServer.kt` | 20 MB, then responds 413. Enforced while reading, so chunked uploads without `Content-Length` and multipart audio files are capped too. |

- Settings take effect when the server is **(re)started**.
- The API key is stored encrypted with the Android Keystore (`EncryptedSharedPreferences`, file `pocket_node_secret_prefs`). A key saved by an older version moves there on first start. If the Keystore is unavailable the key falls back to `pocket_node_prefs` (logged as an error). Both files are excluded from cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
- The key is compared in constant time (`MessageDigest.isEqual`).
- **Recommendation:** set an API key whenever the phone is on a shared network. The server listens on `0.0.0.0:<port>` and is reachable by any device on the LAN.
- Remote image URLs (`http(s)://…`) are **never fetched**, which prevents SSRF. Only `data:` URIs with base64 content are accepted.
- Every response carries an `X-Request-ID` header (e.g. `req_abc1234567`) matching the server's **Activity** log for end-to-end traceability and error troubleshooting.

## Choosing the model

Each kind of request has its own **slot**, set in the app (server screen, **Models** tab). A request is served by, in order:

1. The model named in the request's `model` field: exact name or file name (ignoring case), then an alias, then the same ignoring punctuation (`qwen3.5 4b` = `Qwen3.5-4B`). Matching is **never by substring**.
2. The slot for the request's role. Requests with audio use `stt`, requests with images use `vision`, legacy completions use `completions`, everything else uses `chat`.
3. The `chat` slot (when a role slot is empty and the role is `completions` or `vision`).
4. The model the in-app screens loaded last, then any downloaded model that fits (audio or image capable when needed).

| Slot | Used by | Empty means |
|---|---|---|
| `chat` | `/v1/chat/completions`, `/v1/responses` | first downloaded model |
| `completions` | `/v1/completions` | chat model |
| `stt` | `/v1/audio/transcriptions`, `/v1/audio/translations` | first downloaded audio model |
| `vision` | chat requests with images | chat model |
| `router` | `model: "auto"` | `auto` uses the defaults above |

- **Aliases** (Models tab): one `alias=model` per line, e.g. `gpt-4o=Qwen3.5-4B`. Unknown names that match no alias fall back to the slots, so clients that send `gpt-4o` keep working.
- **Router (decision model):** when a client sends `"model":"auto"` and a router is set, the router model is asked to pick one of the downloaded models for the request. If it fails or names nothing valid, the normal slots apply.
- **Text to speech** has no model slot: it uses the Android speech engine (language and speed in the Models tab).
- **Importing a model list:** the Models tab can load your own `model_allowlist.json`. It is saved to the app's external files directory and replaces the bundled list.
- **Memory:** when free RAM is short for a model being loaded, idle models that this server loaded earlier are unloaded first. Models loaded by the in-app screens, busy models and the router are kept.

## Endpoints

| Endpoint | Status |
|---|---|
| `GET /v1/models`, `GET /v1/models/{id}` | Supported |
| `POST /v1/chat/completions` | Supported (streaming, tools, vision, audio, `n`) |
| `POST /v1/completions` | Supported (legacy) |
| `POST /v1/responses` | Supported (text, images, function tools, streaming events) |
| `POST /v1/audio/transcriptions` | Supported |
| `POST /v1/audio/translations` | Supported (to English, through the audio model) |
| `POST /v1/audio/speech` | Supported (WAV only, Android speech engine) |
| `GET /v1/skills` | Pocket Node extra |
| `/v1/embeddings`, `/v1/moderations`, `/v1/images/*`, `/v1/files`, `/v1/batches`, `/v1/fine_tuning/jobs`, `/v1/assistants`, `/v1/threads` | **501** `not_implemented`: no on-device model serves them |
| Any other `/v1/...` | 404 `unknown_url` |

### `GET /v1/models` and `GET /v1/models/{id}`
Lists the models in the allowlist. Non-standard fields: `downloaded` (the model file exists on the device) and `x_pocket` (`roles` the model is assigned to, `audio`, `image`, `thinking`, `size_bytes`).
```json
{"object":"list","data":[{"id":"Qwen3.5-4B","object":"model","created":1760000000,"owned_by":"pocket-node","downloaded":true,
  "x_pocket":{"roles":["chat"],"audio":false,"image":false,"thinking":true,"size_bytes":2800000000}}]}
```

### `POST /v1/chat/completions`

**Messages**
- Roles: `system`, `developer`, `user`, `assistant`, `tool`.
  - Every `system` and `developer` message is joined, in order, into the system instruction.
- `content` can be a string or an array of parts:
  - `{"type":"text","text":"..."}`
  - `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}`. Only in `user` messages, and only for models with vision.
  - `{"type":"input_audio","input_audio":{"data":"<base64>","format":"wav|mp3|..."}}`. Decoded to 16 kHz mono WAV with `AudioDecoderHelper`; requires a model with audio.
- The last message must be `user` or `tool`. Several trailing `tool` messages (parallel calls) are sent together.
- The API is **stateless**. Each request builds a new LiteRT-LM `Conversation` from the full history and closes it at the end, so the in-app chat conversation is not touched.
  - If the engine does not allow a second conversation, the server borrows the UI conversation. Afterwards it recreates it with its original system instruction, tools and constrained decoding (stored on `LlmModelInstance`), including when creating the request conversation fails. The UI chat history is lost from the model's context in that case.
- Deltas are collected with an unbounded buffer, so no token or tool call is lost if the client reads slowly.
- Model loading is serialized (`initMutex`), so two simultaneous first requests never load the model twice.
- Requests are serialized per model with a mutex.
- Inference stops after 10 minutes with 504 `inference_timeout`. Streams send `: keep-alive` comments every 15 s so proxies do not close them while a request waits for the model.

**Parameters**

| Parameter | Support |
|---|---|
| `model` | See [Choosing the model](#choosing-the-model). `"auto"` uses the router. The response always reports the real model. |
| `temperature` (0–2), `top_p` (0–1; `0` is clamped to 1e-6), `top_k` (extension), `seed` | Mapped to `SamplerConfig`. If omitted, the model's configuration is used. NPU models ignore the sampler. |
| `max_completion_tokens` / `max_tokens` | Mapped to `maxOutputToken`. |
| `stop` (string or up to 4) | Applied by the server, including in streaming. |
| `stream`, `stream_options.include_usage` | SSE `text/event-stream`, `chat.completion.chunk` objects, ending with `data: [DONE]`. |
| `response_format` | `text`, `json_object`, and `json_schema` (`json_schema.schema`). Uses constrained decoding through LiteRT-LM `ResponseFormat`. |
| `reasoning_effort` | `none`/`minimal` turn thinking off; `low`/`medium`/`high` turn it on (`ThinkingConfig`). If omitted, the model default applies. |
| `tools`, `tool_choice`, `parallel_tool_calls` | See [Tools](#tools-function-calling). |
| `n` | 1–8, answered one after another; usage is summed. Not allowed together with `stream` (400). |
| `logit_bias`, `presence_penalty`, `frequency_penalty`, `user` | Accepted and ignored. |

**Response**
```json
{
  "id": "chatcmpl-…", "object": "chat.completion", "created": 1760000000, "model": "Qwen3.5-4B",
  "choices": [{"index": 0, "finish_reason": "stop",
    "message": {"role": "assistant", "content": "…", "reasoning_content": "…"}}],
  "usage": {"prompt_tokens": 12, "completion_tokens": 30, "total_tokens": 42, "estimated": true}
}
```
- `reasoning_content` comes from the LiteRT-LM `thought` channel, or from `<think>…</think>` blocks in the text, which are removed from `content`. This also works in streaming, where tags can be split across deltas (`ThinkStreamSplitter`), and when the template pre-fills `<think>` so that only `…</think>` appears.
- `finish_reason` is `stop`, `tool_calls` or `length`. `length` is reported when the completion tokens, exact or estimated, reach `max_completion_tokens`.
- `usage`: exact counts when LiteRT-LM reports them (`BenchmarkInfo`). Otherwise it is estimated at about 4 characters per token and marked `"estimated": true`.

### `POST /v1/completions` (legacy)
`prompt` (one string, or an array with one string), `max_tokens`, `temperature`, `top_p`, `top_k`, `stop`, `seed`, `n`, `stream`, `stream_options.include_usage`, `echo`. The prompt is sent to the model as one user turn, so it works best with instruction models. `suffix`, `logprobs` > 0 and `best_of` > 1 return 400. Responses use `object: "text_completion"`; streams send `choices[].text` chunks.

### `POST /v1/responses`
- `input`: a string, or an array of items: messages (`input_text` / `output_text` / `input_image` parts), `function_call` and `function_call_output`.
- `instructions`, `temperature`, `top_p`, `max_output_tokens`, `parallel_tool_calls`, `reasoning.effort`, `text.format` (`json_object`, `json_schema`).
- `tools`: flat function tools (`{"type":"function","name":…}`); `tool_choice`: `auto`, `none`, `required`, or `{"type":"function","name":…}`.
- Output: a `message` item (`output_text`) and one `function_call` item per tool call. `status` is `completed`, or `incomplete` with `incomplete_details.reason = "max_output_tokens"`.
- `stream: true` sends the standard events: `response.created`, `response.in_progress`, `response.output_item.added`, `response.content_part.added`, `response.output_text.delta`, `…done`, `response.function_call_arguments.delta/done`, and `response.completed` (or `response.incomplete` / `response.failed`).
- State is not kept: `previous_response_id` and `store` have no effect, so send the whole conversation in `input`.

### `POST /v1/audio/transcriptions` and `/v1/audio/translations`
Multipart form with the fields `file` (MP3, M4A, AAC, OGG, FLAC, WAV, AMR, WebM), `model`, `prompt`, `language` and `response_format` (`json` | `text` | `verbose_json`).
- `srt` and `vtt` return 400. `verbose_json` returns `{"task":"transcribe|translate","language":"es","text":"..."}`.
- Optimized for Spanish with **Qwen3-ASR-1.7B** (`.litertlm`): automatically extracts language tags and verbatim spoken text, stripping thinking and prefixes.
- `translations` asks the audio model for an English translation.

### `POST /v1/audio/speech` and `GET /v1/audio/voices`
JSON `{"model":"tts-1","input":"Hola","voice":"alloy","speed":1.0,"response_format":"wav"}`. Returns `audio/wav`.
- Uses the Android system speech engine, so `model` is ignored. `voice` may be the name of a system voice; OpenAI names (e.g. `alloy`, `nova`) automatically map to the highest quality offline voice of the configured language (Spanish prioritized).
- `GET /v1/audio/voices` lists available system voices with language tags, quality and network requirement.
- `speed` 0.25–4.0 is multiplied by the speech rate set in the app.
- Only `wav` (the default) is produced; `mp3`, `opus`, `aac`, `flac` and `pcm` return 400 `unsupported_format`.
- `input` is limited to 4096 characters. 503 if the device has no speech engine or no voice for the language.

### Skills & Agent Integration (`/v1/skills`)
Pocket Node bundles on-device skills that can be discovered and executed through the OpenAI API:
- `GET /v1/skills`: lists installed skills with their `id`, `name`, `description`, and `selected` status.
- `POST /v1/skills/{name}/select` & `POST /v1/skills/{name}/unselect`: enables or disables a skill.
- **Server-executed skills (`x_pocket_skills`)**: include `"x_pocket_skills": true` (or an array of skill names) in `POST /v1/chat/completions`. The server automatically injects `load_skill` and `run_skill_js` tools into the prompt, executes skill scripts in a headless WebView sandbox on behalf of the client, and feeds intermediate outputs back into the conversation up to 5 rounds until producing a final answer. Client-side tools are returned directly to the caller.

## Tools (function calling)

Tools are compatible with the OpenAI API format (arguments as valid JSON objects) regardless of the model:
- **Native runtime parsing**: models with native tool support (like Gemma 4) use LiteRT-LM `OpenApiTool` with `automaticToolCalling = false`.
- **Universal text tool parsing**: models without native parsers are supported via `ToolCallTextParser` which recognizes:
  - Hermes / Qwen: `<tool_call>{"name": "...", "arguments": {...}}</tool_call>`
  - Mistral / Ministral: `[TOOL_CALLS][{"name": "...", "arguments": {...}}]` or `[TOOL_CALLS]name[ARGS]{...}`
  - Liquid LFM: `<|tool_call_start|>[name(arg="val")]<|tool_call_end|>` (Pythonic call syntax converted to JSON)
  - IBM Granite / Llama: `<|tool_call|>[{...}]` or `<function_call>{...}</function_call>`
  - Markdown ```json blocks and bare JSON objects when matching declared tool names.
- **JSON argument normalization**: arguments are sanitized, trailing commas removed, single quotes normalized, and guaranteed to be returned as valid JSON object strings to the client.
- **Prompt hints**: for models that do not self-describe tool syntax, the server automatically injects tool descriptions and call format instructions in the system prompt.
- **Streaming with tools**: live text streams without delay while potential tool markers are buffered; tool call deltas stream as soon as parsed.
- Next turn: send the `assistant` message with its `tool_calls`, plus one `role:"tool"` message per result with its `tool_call_id`. Results are passed to the model as `Content.ToolResponse`.
- `tool_choice`: `auto`, `none`, `required`, or `{"type":"function","function":{"name":"x"}}`.
- `parallel_tool_calls: false` returns only the first call.

## Errors
Every error uses the OpenAI format:
```json
{"error": {"message": "…", "type": "invalid_request_error", "param": "messages", "code": null}}
```

| HTTP | `type` | Cases |
|---|---|---|
| 400 | `invalid_request_error` | Invalid JSON, empty `messages`, out-of-range parameters, `n` outside 1–8 or with `stream`, remote image URL (`code: unsupported_image_url`), unknown tool or `tool_call_id`, unsupported speech format |
| 401 | `authentication_error` | Missing or wrong API key (`code: invalid_api_key`) |
| 404 | `invalid_request_error` | `GET /v1/models/{id}` does not exist (`model_not_found`), unknown endpoint (`unknown_url`) |
| 413 | `invalid_request_error` | Body over 20 MB |
| 501 | `server_error` | Endpoint no on-device model can serve (`not_implemented`) |
| 503 | `service_unavailable_error` | No model available, or not downloaded (`model_not_downloaded`), or failed to load (`model_load_failed`), or no speech engine (`tts_unavailable`) |
| 504 | `server_error` | Inference timed out (`inference_timeout`) |
| 500 | `server_error` | Internal error. The message is generic; details are only in the device log. |

During streaming, an error after the first chunk is sent as `data: {"error":…}` followed by `data: [DONE]`.

## Examples

**curl, simple chat**
```bash
curl http://<PHONE_IP>:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "Authorization: Bearer $POCKET_KEY" \
  -d '{"model":"Qwen3.5-4B","messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"What is LiteRT?"}]}'
```

**Python (`openai` SDK), streaming**
```python
from openai import OpenAI
client = OpenAI(base_url="http://<PHONE_IP>:8080/v1", api_key="<key or anything>")
for chunk in client.chat.completions.create(
    model="Gemma-4-E2B-it", stream=True,
    messages=[{"role": "user", "content": "Write a haiku about phones."}],
):
    print(chunk.choices[0].delta.content or "", end="")
```

**Python, tool round trip**
```python
tools = [{"type": "function", "function": {
    "name": "get_weather", "description": "Current weather for a city",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}]
msgs = [{"role": "user", "content": "What's the weather in Lima?"}]
r = client.chat.completions.create(model="Gemma-4-E2B-it", messages=msgs, tools=tools)
call = r.choices[0].message.tool_calls[0]
msgs += [r.choices[0].message, {"role": "tool", "tool_call_id": call.id, "content": '{"temp_c": 19}'}]
print(client.chat.completions.create(model="Gemma-4-E2B-it", messages=msgs, tools=tools).choices[0].message.content)
```

**Structured output**
```bash
curl http://<PHONE_IP>:8080/v1/chat/completions -H "Content-Type: application/json" -d '{
  "messages":[{"role":"user","content":"Give me a fruit"}],
  "response_format":{"type":"json_schema","json_schema":{"name":"fruit","schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}}'
```

## Limitations
- Not implemented (501): embeddings, moderations, images, files, batches, fine-tuning, assistants, threads. `logprobs` on chat is accepted and ignored; on legacy completions it returns 400.
- Speech output is WAV only.
- `n > 1` runs the completions one after another and cannot stream.
- Tool-call quality depends on the model. Gemma 4 has a native parser; generic models depend on the `<tool_call>` format.
- Only one inference runs at a time per model. Other requests wait their turn.
- **Not yet verified on a device:** two live conversations on one engine, `json_object` quality under constrained decoding, LAN access on Android 15+, the new screen's insets, the speech engine, and whether native `tool_calls` also leave the call text in `content`.

## Request log (traceability)

Every API request is appended to a persistent log on the device:
`files/logs/api-YYYY-MM-DD.jsonl` (one JSON object per line, 7 days retention, max 20 MB/day).
Each line holds metadata only — never prompt/completion text or the API key:

`ts`, `id` (same value as the `X-Request-ID` response header), `method`, `path`, `status`,
`duration_ms`, `model`, `client_ip`, `user_agent`, `request_bytes`, `client_request_id`
(the client's own `X-Request-ID`, if sent), `error`, and for text endpoints `stream`,
`messages`, `prompt_chars`, `images`, `audios`, `tools`, `tool_choice`, `max_tokens`,
`temperature`, `thinking`, `finish_reason`, `prompt_tokens`, `completion_tokens`,
`tokens_estimated`, `content_chars`, `tool_calls`, `stream_error`.

| Endpoint | Description |
|---|---|
| `GET /v1/logs` | Lists available log days and sizes |
| `GET /v1/logs/{YYYY-MM-DD}` | Downloads that day's log (NDJSON) |
| `DELETE /v1/logs` | Deletes all log files |

```bash
curl -H "Authorization: Bearer $KEY" http://<phone-ip>:8080/v1/logs/2026-10-15 | jq 'select(.status >= 400)'
```
