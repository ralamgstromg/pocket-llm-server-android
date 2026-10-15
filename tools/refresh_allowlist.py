"""Refresh commit hashes, file names and sizes of the bundled allowlist from Hugging Face.

Usage: python3 -I tools/refresh_allowlist.py ALLOWLIST.json   (rewrites the file, prints a report)
Entries whose repo is gated (HTTP 401) or whose model file no longer exists are reported and left as they are.
"""
import json
import sys
from hf_catalog import describe

path = sys.argv[1]
data = json.load(open(path))
for m in data["models"]:
    repo = m["modelId"].split("/", 1)
    if repo[0] != "litert-community":
        print("skip   ", m["name"], "(not litert-community)")
        continue
    try:
        d = describe(repo[1])
    except Exception as e:
        print("ERROR  ", m["name"], e)
        continue
    f = m["modelFile"]
    if f not in d["files"]:
        print("MISSING", m["name"], f, "-> available:", [p for p in d["files"] if "/" not in p][:6])
        continue
    old = m["commitHash"]
    m["commitHash"] = d["sha"]
    m["sizeInBytes"] = d["files"][f]
    print("ok     ", m["name"], old[:8], "->", d["sha"][:8])
json.dump(data, open(path, "w"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
