# Pocket Node — OpenAI-compatible REST API

Pocket Node serves an OpenAI-compatible HTTP API on `http://<PHONE_IP>:8080/v1`. Any OpenAI client can use it by setting the base URL: the official `openai` Python and JS SDKs, LangChain, n8n, Open WebUI, and others.

The server runs inside `PocketNodeService`, a foreground service built on Ktor CIO. Inference runs on the device through **LiteRT-LM 0.18**.

- Code: `Android/src/app/src/main/java/com/google/ai/edge/gallery/server/`
  - `PocketNodeServer.kt`: routing, authentication, streaming, transcriptions.
  - `openai/OpenAiModels.kt`: request, response, chunk and error DTOs.
  - `openai/ChatRequestParser.kt`: validates requests and converts them to `ParsedChatRequest`.
  - `openai/LiteRtLmMapper.kt`: converts between OpenAI messages and tools and LiteRT-LM types.
  - `openai/ChatCompletionEngine.kt`: runs inference in a stateless `Conversation` per request.
  - `openai/ResponseUtils.kt`: `<tool_call>` parser, stop sequences, `<think>` handling, SSE, API key check.
- Tests: `Android/src/app/src/test/java/com/google/ai/edge/gallery/server/openai/` (44 JVM tests). Run them with `./gradlew :app:testDebugUnitTest`.

## Authentication and security

| Setting | Where | Default |
|---|---|---|
| API key (`Authorization: Bearer <key>`) | Pocket Node Server dialog, "API Key" field | Empty, so no authentication |
| CORS (calls from browsers on other origins) | Dialog, "Permitir CORS" switch | Enabled |
| Maximum body size | `MAX_BODY_BYTES` in `PocketNodeServer.kt` | 64 MB, then responds 413 |

- Both settings are stored in `SharedPreferences` (`pocket_node_prefs`) and take effect when the server is **(re)started**.
- The key is compared in constant time (`MessageDigest.isEqual`).
- **Recommendation:** set an API key whenever the phone is on a shared network. The server listens on `0.0.0.0:8080` and is reachable by any device on the LAN.
- Remote image URLs (`http(s)://…`) are **never fetched**, which prevents SSRF. Only `data:` URIs with base64 content are accepted.

## Endpoints

### `GET /v1/models` and `GET /v1/models/{id}`
Lists the models in the allowlist. The non-standard field `downloaded` shows whether the model file exists on the device.
```json
{"object":"list","data":[{"id":"Qwen3.5-4B","object":"model","created":1760000000,"owned_by":"pocket-node","downloaded":true}]}
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
  - If the engine does not allow a second conversation, the server borrows the UI conversation and recreates it afterwards.
- Requests are serialized per model with a mutex.

**Parameters**

| Parameter | Support |
|---|---|
| `model` | Name from `/v1/models`, compared exactly first. If it does not exist, the model selected in the dialog is used, so clients that send `gpt-4o` keep working. The response always reports the real model. |
| `temperature` (0–2), `top_p` (0–1], `top_k` (extension), `seed` | Mapped to `SamplerConfig`. If omitted, the model's configuration is used. NPU models ignore the sampler. |
| `max_completion_tokens` / `max_tokens` | Mapped to `maxOutputToken`. |
| `stop` (string or up to 4) | Applied by the server, including in streaming. |
| `stream`, `stream_options.include_usage` | SSE `text/event-stream`, `chat.completion.chunk` objects, ending with `data: [DONE]`. |
| `response_format` | `text`, `json_object`, and `json_schema` (`json_schema.schema`). Uses constrained decoding through LiteRT-LM `ResponseFormat`. |
| `reasoning_effort` | `none`/`minimal` turn thinking off; `low`/`medium`/`high` turn it on (`ThinkingConfig`). If omitted, the model default applies. |
| `tools`, `tool_choice`, `parallel_tool_calls` | See [Tools](#tools-function-calling). |
| `n` | Only `1` (otherwise 400). |
| `logprobs`, `logit_bias`, `presence_penalty`, `frequency_penalty`, `user` | Accepted and ignored. |

**Response**
```json
{
  "id": "chatcmpl-…", "object": "chat.completion", "created": 1760000000, "model": "Qwen3.5-4B",
  "choices": [{"index": 0, "finish_reason": "stop",
    "message": {"role": "assistant", "content": "…", "reasoning_content": "…"}}],
  "usage": {"prompt_tokens": 12, "completion_tokens": 30, "total_tokens": 42, "estimated": true}
}
```
- `reasoning_content` comes from the LiteRT-LM `thought` channel, or from `<think>…</think>` blocks in the text, which are removed from `content`.
- `finish_reason` is `stop`, `tool_calls` or `length`. `length` is only reported when the runtime gives exact token counts.
- `usage`: exact counts when LiteRT-LM reports them (`BenchmarkInfo`). Otherwise it is estimated at about 4 characters per token and marked `"estimated": true`.

### `POST /v1/audio/transcriptions`
Multipart form with the fields `file` (MP3, M4A, AAC, OGG, FLAC, WAV, AMR, WebM), `model`, `prompt`, `language` and `response_format` (`json` | `text`).
- `srt`, `vtt` and `verbose_json` return 400.
- The text is cleaned up by removing `<think>` blocks, prefixes like "Transcription:", and surrounding quotes.

## Tools (function calling)

Tools use the **native** LiteRT-LM 0.18 support:
- Each tool in `tools` is wrapped in an `OpenApiTool` whose description is the OpenAI function object (`name`, `description`, `parameters`).
- The conversation is created with `automaticToolCalling = false`. The model **does not run** the tool: the server returns the calls to the client, following OpenAI semantics.
- Response: `message.tool_calls = [{id:"call_…", type:"function", function:{name, arguments:"<json>"}}]` with `finish_reason: "tool_calls"`. When there is no text, `content` is `null`.
- Next turn: send the `assistant` message with its `tool_calls`, plus one `role:"tool"` message per result with its `tool_call_id`. Results are passed to the model as `Content.ToolResponse`. If the result is valid JSON it goes in as an object; otherwise as text.
- `tool_choice`:
  - `auto` (default).
  - `none`: tools are not passed to the model.
  - `required`, or `{"type":"function","function":{"name":"x"}}`: filters to that tool, adds an instruction to the system prompt, and enables `ExperimentalFlags.enableConversationConstrainedDecoding`.
- `parallel_tool_calls: false` returns only the first call.
- Calls to tools that were not declared are dropped.
- **Models without a native parser** (`llm_model_type=generic_model`, for example Qwen3.5-4B): the server parses `<tool_call>{"name":…,"arguments":…}</tool_call>` blocks (the Qwen/Hermes format) from the text and returns them as `tool_calls`.
- **Streaming with tools:** text is sent at the end, together with the `tool_calls` delta (with `index`). This is needed to parse calls written as text.

## Errors
Every error uses the OpenAI format:
```json
{"error": {"message": "…", "type": "invalid_request_error", "param": "messages", "code": null}}
```

| HTTP | `type` | Cases |
|---|---|---|
| 400 | `invalid_request_error` | Invalid JSON, empty `messages`, out-of-range parameters, `n>1`, remote image URL (`code: unsupported_image_url`), unknown tool or `tool_call_id` |
| 401 | `authentication_error` | Missing or wrong API key (`code: invalid_api_key`) |
| 404 | `invalid_request_error` | `GET /v1/models/{id}` does not exist (`model_not_found`) |
| 413 | `invalid_request_error` | Body over 64 MB |
| 503 | `service_unavailable_error` | No model available, or not downloaded (`model_not_downloaded`), or failed to load (`model_load_failed`) |
| 500 | `server_error` | Internal error |

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
- Not implemented: `/v1/responses`, `/v1/embeddings`, `/v1/completions` (legacy), `n>1` and `logprobs`.
- Tool-call quality depends on the model. Gemma 4 has a native parser; generic models depend on the `<tool_call>` format.
- Only one inference runs at a time per model. Other requests wait their turn.
