#!/usr/bin/env python3
"""Find which historical version of V41/V52 migration files matches DB checksums."""
import subprocess, zlib, sys

REPO = "/home/z/my-project"
V41 = "workflow_lowcode/backend/src/main/resources/db/migration/V41__add_task_comment_signature.sql"
V52 = "workflow_lowcode/backend/src/main/resources/db/migration/V52__sys_attachment_image_meta.sql"
TARGETS = {"V41": -317086640, "V52": 1003945511}

def git(*args):
    return subprocess.run(["git", "-C", REPO] + list(args), capture_output=True, text=True).stdout

def flyway_checksum(data: bytes):
    """Flyway ChecksumCalculator: CRC32 over line-ending-normalized text."""
    text = data.decode("utf-8", errors="replace")
    variants = {
        "raw": text,
        "crlf2lf": text.replace("\r\n", "\n"),
        "crlf2lf_notrail": text.replace("\r\n", "\n").rstrip("\n"),
        "striplines": "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").split("\n")),
    }
    out = {}
    for name, t in variants.items():
        out[name] = zlib.crc32(t.encode("utf-8"))
    return out

def candidates(path):
    """Yield (label, bytes) for each historical blob of path plus working tree."""
    hashes = git("log", "--all", "--format=%H", "--", path).split()
    seen = set()
    for h in hashes:
        blob = git("rev-parse", f"{h}:{path}").strip()
        if blob and blob not in seen:
            seen.add(blob)
            short = git("log", "-1", "--format=%h %s", h).strip()[:60]
            yield blob[:12] + " " + short, subprocess.run(["git", "-C", REPO, "cat-file", "blob", blob], capture_output=True).stdout
    try:
        with open(f"{REPO}/{path}", "rb") as f:
            yield "WORKTREE", f.read()
    except FileNotFoundError:
        pass

for key, path in (("V41", V41), ("V52", V52)):
    print(f"=== {key} target={TARGETS[key]} ===")
    for label, data in candidates(path):
        cks = flyway_checksum(data)
        for vname, val in cks.items():
            if val == TARGETS[key]:
                print(f"  *** MATCH [{label}] variant={vname}")
        print(f"  {label}: {cks['crlf2lf']}")
