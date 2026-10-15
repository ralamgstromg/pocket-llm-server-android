"""Add the curated Spanish-capable models and annotate existing ones. Idempotent.

Usage: PYTHONPATH=tools python3 -s tools/curate_allowlist.py ALLOWLIST.json
Every model URL is checked with an HTTP HEAD request before it is written.
"""
import json
import sys
import urllib.request
from hf_catalog import describe

ES = ["es", "en"]


def head_ok(repo, sha, file):
    url = f"https://huggingface.co/{repo}/resolve/{sha}/{file}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers={"User-Agent": "pocket-node-catalog"}), timeout=30) as r:
            return r.status == 200
    except Exception:
        return False


def cfg(top_k=20, top_p=0.8, temp=0.7, ctx=4096, tokens=2048, acc="gpu,cpu"):
    return {"topK": top_k, "topP": top_p, "temperature": temp, "maxContextLength": ctx, "maxTokens": tokens, "accelerators": acc}


T_CHAT = ["llm_chat", "llm_prompt_lab"]
T_AGENT = ["llm_chat", "llm_prompt_lab", "llm_agent_chat"]
T_AUDIO = ["llm_ask_audio", "llm_chat", "llm_prompt_lab"]

# (name, repo, file, description, minRamGb, extra fields)
NEW = [
    ("Qwen3-ASR-1.7B", "Qwen3-ASR-1.7B", "Qwen3-ASR-1.7B.litertlm",
     "Qwen3-ASR 1.7B: reconocimiento de voz (STT) en 30 idiomas, incluido espanol, para LiteRT-LM. Audio de hasta 30 s por peticion. Mejor opcion para /v1/audio/transcriptions.",
     8, dict(llmSupportAudio=True, languages=["es", "en", "pt", "fr", "de", "it", "zh", "ja", "ko", "ar", "ru"], recommendedRoles=["stt"],
             defaultConfig=cfg(temp=0.0, ctx=2048, tokens=1024), taskTypes=T_AUDIO, bestForTaskTypes=["llm_ask_audio"])),
    ("Qwen3-ASR-1.7B-15s", "Qwen3-ASR-1.7B", "Qwen3-ASR-1.7B-15s.litertlm",
     "Qwen3-ASR 1.7B con ventana de 15 s: mas ligero en memoria que la de 30 s. STT multilingue con espanol.",
     6, dict(llmSupportAudio=True, languages=["es", "en", "pt", "fr", "de", "it", "zh", "ja", "ko", "ar", "ru"], recommendedRoles=["stt"],
             defaultConfig=cfg(temp=0.0, ctx=2048, tokens=1024), taskTypes=T_AUDIO)),
    ("Parakeet-TDT-0.6B-v3", "parakeet-tdt-0.6b-v3", "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm",
     "NVIDIA Parakeet TDT 0.6B v3 (int8): STT multilingue europeo con espanol, muy rapido. Ventana de 5 s por bloque.",
     4, dict(llmSupportAudio=True, languages=["es", "en", "fr", "de", "it", "pt", "nl", "pl", "ru"], recommendedRoles=["stt"],
             defaultConfig=cfg(temp=0.0, ctx=1024, tokens=512), taskTypes=T_AUDIO)),
    ("Qwen3.5-0.8B", "Qwen3.5-0.8B", "Qwen3.5-0.8B_int8.litertlm",
     "Qwen3.5 0.8B: modelo diminuto multilingue (incluye espanol), ideal como router/decisor y para tareas rapidas con herramientas.",
     4, dict(llmSupportThinking=True, languages=ES, recommendedRoles=["router", "chat"], toolFormat="hermes", defaultConfig=cfg(), taskTypes=T_AGENT)),
    ("Qwen3-1.7B", "Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
     "Qwen3 1.7B int4: chat multilingue con modo de razonamiento. Buen equilibrio calidad/memoria en espanol.",
     6, dict(llmSupportThinking=True, languages=ES, recommendedRoles=["chat", "router"], toolFormat="hermes", defaultConfig=cfg(), taskTypes=T_AGENT)),
    ("Qwen3-4B", "Qwen3-4B", "qwen3_4b_mixed_int4.litertlm",
     "Qwen3 4B mixed int4: chat y herramientas multilingue (espanol) con razonamiento.",
     8, dict(llmSupportThinking=True, languages=ES, recommendedRoles=["chat"], toolFormat="hermes", defaultConfig=cfg(), taskTypes=T_AGENT)),
    ("Ministral-3-3B-Instruct", "Ministral-3-3B-Instruct-2512", "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
     "Mistral Ministral 3 3B Instruct (2512): excelente en espanol y frances, con llamadas a herramientas.",
     8, dict(languages=["es", "en", "fr", "de", "it", "pt"], recommendedRoles=["chat"], toolFormat="mistral", defaultConfig=cfg(temp=0.3, top_p=0.95), taskTypes=T_AGENT)),
    ("Granite-4.1-3B", "granite-4.1-3b", "granite-4.1-3b_int4.litertlm",
     "IBM Granite 4.1 3B int4: instrucciones y herramientas multilingues (espanol), licencia Apache-2.0.",
     8, dict(languages=["es", "en", "fr", "de", "pt", "it"], recommendedRoles=["chat"], toolFormat="hermes", defaultConfig=cfg(temp=0.3), taskTypes=T_AGENT)),
    ("LFM2.5-350M", "LFM2.5-350M", "LFM2.5-350M_int4.litertlm",
     "Liquid LFM2.5 350M int4: el modelo mas pequeno del catalogo, para decisiones rapidas (router) y extraccion de datos.",
     3, dict(languages=["en", "es", "fr", "de", "ja", "ko", "zh", "ar"], recommendedRoles=["router"], toolFormat="pythonic", defaultConfig=cfg(temp=0.1), taskTypes=T_CHAT)),
    ("SmolLM3-3B", "SmolLM3-3B", "SmolLM3-3B_q4_block32_ekv4096.litertlm",
     "SmolLM3 3B q4: chat multilingue (espanol) con razonamiento opcional.",
     8, dict(llmSupportThinking=True, languages=["es", "en", "fr", "de", "it", "pt"], recommendedRoles=["chat"], toolFormat="hermes", defaultConfig=cfg(), taskTypes=T_AGENT)),
]

# name -> fields to merge into existing entries
ANNOTATE = {
    "Gemma-4-E2B-it": dict(languages=ES, recommendedRoles=["chat", "stt", "vision", "router"], toolFormat="native"),
    "Gemma-4-E4B-it": dict(languages=ES, recommendedRoles=["chat", "stt", "vision"], toolFormat="native"),
    "Gemma-3n-E2B-it": dict(languages=ES, recommendedRoles=["stt", "vision"]),
    "Gemma-3n-E4B-it": dict(languages=ES, recommendedRoles=["stt", "vision"]),
    "Gemma3-1B-IT": dict(languages=ES, recommendedRoles=["router"]),
    "Qwen3-0.6B": dict(languages=ES, recommendedRoles=["router"], toolFormat="hermes"),
    "Qwen3.5-2B": dict(languages=ES, recommendedRoles=["chat"], toolFormat="hermes"),
    "Qwen3.5-4B": dict(languages=ES, recommendedRoles=["chat"], toolFormat="hermes"),
    "Qwen2.5-1.5B-Instruct": dict(languages=ES, toolFormat="hermes"),
    "LFM2.5-1.2B-Instruct": dict(languages=ES, toolFormat="pythonic"),
    "Agents-A1-4B": dict(languages=ES, recommendedRoles=["chat"], toolFormat="hermes"),
    "Decider-0.8B": dict(languages=["en"], recommendedRoles=["router"]),
    "VibeVoice-ASR-Streaming-1.5B": dict(languages=["en", "es"], recommendedRoles=["stt"]),
    "Whisper-Large-v3-Turbo": dict(languages=ES, servingNote="Formato .tflite: el servidor Pocket Node (LiteRT-LM) no puede ejecutarlo. Usa Qwen3-ASR para STT."),
    "Audio8-TTS-0.6B": dict(servingNote="Formato .tflite de sintesis de voz: sin soporte en el servidor. /v1/audio/speech usa el motor de voz de Android."),
    "Qwen3-TTS-0.6B": dict(languages=["es", "en", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko"],
                           servingNote="Unico TTS de litert-community con espanol, pero es un pipeline de varios .tflite sin runtime en el servidor todavia. /v1/audio/speech usa el motor de voz de Android."),
    "Kev-4B": dict(servingNote="Formato .tflite de clasificacion: el servidor no puede ejecutarlo como router. Usa Qwen3.5-0.8B, Qwen3-0.6B o Decider-0.8B."),
    "d1-omni-600M": dict(servingNote="Formato .tflite de clasificacion: el servidor no puede ejecutarlo como router. Usa Qwen3.5-0.8B, Qwen3-0.6B o Decider-0.8B."),
}

path = sys.argv[1]
data = json.load(open(path))
by_name = {m["name"]: m for m in data["models"]}
for name, extra in ANNOTATE.items():
    if name in by_name:
        by_name[name].update(extra)
for name, repo, file, desc, ram, extra in NEW:
    d = describe(repo)
    if file not in d["files"]:
        print("MISSING", name, file)
        continue
    if not head_ok(f"litert-community/{repo}", d["sha"], file):
        print("HEAD FAILED", name)
        continue
    entry = {"name": name, "modelId": f"litert-community/{repo}", "modelFile": file, "commitHash": d["sha"], "description": desc,
             "sizeInBytes": d["files"][file], "minDeviceMemoryInGb": ram}
    entry.update(extra)
    if name in by_name:
        by_name[name].update(entry)
    else:
        data["models"].append(entry)
    print("ok", name, d["sha"][:8], d["files"][file])
json.dump(data, open(path, "w"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
