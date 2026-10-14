#!/usr/bin/env python3
"""Verify Flyway checksum algorithm against DB records, then repair V41/V52."""
import subprocess, zlib, sys

REPO = "/home/z/my-project"
MIG = f"{REPO}/workflow_lowcode/backend/src/main/resources/db/migration"
MDB = "/home/z/my-project/mariadb-user"

def flyway_ck(path):
    with open(path, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")
    return zlib.crc32(text.replace("\r\n", "\n").encode("utf-8"))

def query(sql):
    env_cmd = (f'export MDB={MDB}; export LD_LIBRARY_PATH="$MDB/sysroot/usr/lib/x86_64-linux-gnu:'
               f'$MDB/root/usr/lib/x86_64-linux-gnu"; '
               f'{MDB}/root/bin/mariadb -u root -p740130 -S {MDB}/mysql.sock -N -e "{sql}" 2>/dev/null')
    return subprocess.run(["bash", "-c", env_cmd], capture_output=True, text=True).stdout.strip()

# 1. Verify algorithm: compare computed vs recorded for first 12 migrations
rows = query("SELECT version, checksum FROM workflow.flyway_schema_history WHERE checksum IS NOT NULL ORDER BY installed_rank LIMIT 12")
import os
ok, bad = 0, 0
for line in rows.splitlines():
    ver, ck = line.split("\t")
    matches = [fn for fn in os.listdir(MIG) if fn.startswith(f"V{ver}__")]
    if not matches:
        continue
    calc = flyway_ck(os.path.join(MIG, matches[0]))
    if calc == int(ck):
        ok += 1
    else:
        bad += 1
        print(f"MISMATCH V{ver}: db={ck} calc={calc}")
print(f"algorithm check: ok={ok} bad={bad}")
if bad > 0 and ok == 0:
    print("ALGORITHM UNRELIABLE - ABORT")
    sys.exit(1)

# 2. Repair V41/V52
for ver, fn in (("41", "V41__add_task_comment_signature.sql"), ("52", "V52__sys_attachment_image_meta.sql")):
    new_ck = flyway_ck(os.path.join(MIG, fn))
    print(f"V{ver}: setting checksum={new_ck}")
    r = query(f"UPDATE workflow.flyway_schema_history SET checksum={new_ck} WHERE version='{ver}'")
    if r:
        print("update output:", r)
# 3. Confirm
print(query("SELECT version, checksum FROM workflow.flyway_schema_history WHERE version IN ('41','52')"))
