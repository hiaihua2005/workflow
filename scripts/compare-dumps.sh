#!/bin/bash
# 对比两份 dump 各表 INSERT 数量，量化数据差异
OLD=/home/z/my-project/backups/db-workflow-full-20261010-200510.sql
NEW=$(ls -t /home/z/my-project/backups/db-workflow-full-2026101*.sql | head -1)
[ -f "$OLD" ] || { git -C /home/z/my-project show 17b30c9d:backups/db-workflow-full-20261010-200510.sql > /tmp/old-full.sql; OLD=/tmp/old-full.sql; }
echo "旧(20:05快照): $OLD  新(当前库): $NEW"
for tbl in wf_form_def wf_form_data wf_form_release wf_process_def wf_model wf_data_source sys_menu wf_task wf_instance; do
  o=$(rg -c "INSERT INTO \`$tbl\`" "$OLD" 2>/dev/null || echo 0)
  n=$(rg -c "INSERT INTO \`$tbl\`" "$NEW" 2>/dev/null || echo 0)
  [ "$o" != "0" ] || [ "$n" != "0" ] && echo "$tbl: 旧=$o 新=$n"
done
echo "--- wf_biz_* ---"
for tbl in $(rg -o 'CREATE TABLE `(wf_biz_\w+)`' -r '$1' "$OLD" | sort -u); do
  o=$(rg -c "INSERT INTO \`$tbl\`" "$OLD" 2>/dev/null || echo 0)
  n=$(rg -c "INSERT INTO \`$tbl\`" "$NEW" 2>/dev/null || echo 0)
  echo "$tbl: 旧=$o 新=$n"
done
