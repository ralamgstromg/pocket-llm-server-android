"""Query the Hugging Face API for litert-community repos: current sha, .litertlm files, sizes.

Usage: python3 -I tools/hf_catalog.py REPO [REPO ...]   (REPO without the 'litert-community/' prefix)
Prints JSON lines. Read-only; no credentials.
"""
import json
import sys
import urllib.request

BASE = "https://huggingface.co"


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "pocket-node-catalog"}), timeout=30) as r:
        return json.load(r)


def describe(repo):
    info = get(f"{BASE}/api/models/litert-community/{repo}")
    tree = get(f"{BASE}/api/models/litert-community/{repo}/tree/main?recursive=true")
    files = {t["path"]: t.get("lfs", {}).get("size", t.get("size", 0)) for t in tree if t["type"] == "file"}
    card = info.get("cardData") or {}
    return {
        "repo": f"litert-community/{repo}",
        "sha": info["sha"],
        "lang": card.get("language"),
        "license": card.get("license"),
        "files": {p: s for p, s in files.items() if p.endswith((".litertlm", ".tflite", ".task"))},
    }


if __name__ == "__main__":
    for r in sys.argv[1:]:
        try:
            print(json.dumps(describe(r)))
        except Exception as e:  # keep going on one bad repo
            print(json.dumps({"repo": r, "error": str(e)}))
