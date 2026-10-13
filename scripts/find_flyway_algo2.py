#!/usr/bin/env python3
"""Refine Flyway checksum algorithm variants."""
import zlib, os, glob

MIG = "/home/z/my-project/workflow_lowcode/backend/src/main/resources/db/migration"
KNOWN = {
    "V2__init.sql": 84590707,
    "V1__workflow.sql": -1390981594,
}
# find V53 file
for fn in os.listdir(MIG):
    if fn.startswith("V53"):
        KNOWN[fn] = -1564541405

def variants(t: str):
    t = t.replace("\r\n", "\n").replace("\r", "\n")
    lines = t.split("\n")
    yield "join_nosep", "".join(lines)
    yield "join_nosep_skipempty", "".join(l for l in lines if l.strip())
    yield "join_nosep_strip_each", "".join(l.rstrip() for l in lines)
    yield "join_nosep_trim_each", "".join(l.strip() for l in lines)
    yield "join_nosep_rstrip_all", "".join(lines).rstrip()
    yield "join_nosep_skipempty_trim", "".join(l for l in lines if l.strip())

for hint, target in KNOWN.items():
    path = os.path.join(MIG, hint)
    if not os.path.exists(path):
        print(f"skip {hint}")
        continue
    with open(path, "rb") as f:
        raw = f.read().decode("utf-8", errors="replace")
    print(f"=== {hint} target={target}")
    for name, t in variants(raw):
        val = zlib.crc32(t.encode("utf-8"))
        mark = " *** MATCH" if val == target else ""
        print(f"  {name}: {val}{mark}")
