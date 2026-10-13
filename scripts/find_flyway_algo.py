#!/usr/bin/env python3
"""Brute-force Flyway checksum algorithm against known DB values."""
import zlib, os

MIG = "/home/z/my-project/workflow_lowcode/backend/src/main/resources/db/migration"
KNOWN = {  # from flyway_schema_history
    "V2__init.sql": 84590707,
    "V1__workflow.sql": -1390981594,
    "V39__optimize_retry_index.sql": -473665810,
    "V53": -1564541405,
}

def variants(text: str):
    t = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = t.split("\n")
    yield "join_nosep", "".join(lines)
    yield "join_nosep_strip", "".join(l.rstrip() for l in lines)
    yield "join_nl_notrail", "\n".join(lines).rstrip("\n")
    yield "join_nl_strip", "\n".join(l.rstrip() for l in lines)
    yield "raw", text
    yield "raw_rstrip", text.rstrip()
    yield "crlf2lf", t
    yield "crlf2lf_rstrip", t.rstrip("\n")
    yield "join_nl_keep", "\n".join(lines)
    yield "per_line_nl", "".join(l + "\n" for l in lines)

def find_file(name_hint):
    for fn in os.listdir(MIG):
        if fn.startswith(name_hint):
            return os.path.join(MIG, fn)
    return None

# collect all variant scores across all known files
scores = {}
for hint, target in KNOWN.items():
    path = find_file(hint)
    if not path:
        print(f"skip {hint}")
        continue
    with open(path, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")
    for name, t in variants(text):
        val = zlib.crc32(t.encode("utf-8"))
        scores.setdefault(name, []).append(val == target)

for name, results in sorted(scores.items(), key=lambda x: -sum(x[1])):
    if sum(results):
        print(f"CANDIDATE {name}: {sum(results)}/{len(results)} match")
        if all(results):
            print(f"*** FULL MATCH: {name}")
