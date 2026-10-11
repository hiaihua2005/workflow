#!/bin/bash
# ============================================================
# validate-demo-sql.sh — 全新环境模拟验证 sql/demo-examples/
#
# 步骤：
#   1. 从当前 workflow 库导出纯结构 → 建 sqlcheck 库（全新环境）
#   2. 删除演示资产相关行 + DROP 演示业务表（模拟 Flyway 后的干净库）
#   3. 按序导入 01→08 两遍（验证语法 + 幂等不翻倍）
#   4. 对账关键表行数
# 用法：bash scripts/validate-demo-sql.sh
# ============================================================
set -u
MDB=/home/z/my-project/mariadb-user
M="$MDB/root/bin/mariadb"
DUMPBIN="$MDB/root/bin/mariadb-dump"
export LD_LIBRARY_PATH="$MDB/sysroot/usr/lib/x86_64-linux-gnu:$MDB/root/usr/lib/x86_64-linux-gnu"
AUTH="-u root -p740130 -S $MDB/mysql.sock"
WORK=/home/z/my-project/scripts/sqlcheck-work
DEMO=/home/z/my-project/sql/demo-examples
DB=sqlcheck

mkdir -p "$WORK"
echo "[1] 结构导出 → 重建 $DB"
$DUMPBIN $AUTH --no-data --routines=false workflow > "$WORK/schema.sql" || exit 1
$M $AUTH -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;"
$M $AUTH $DB < "$WORK/schema.sql" || exit 1

echo "[2] 清空演示资产（模拟全新环境：无表单/逻辑流/流程/业务表）"
$M $AUTH $DB <<'SQL'
DELETE FROM wf_form_def;
DELETE FROM wf_form_logic_binding;
DELETE FROM wf_logic_flow;
DELETE FROM wf_process_draft;
DELETE FROM wf_node_config;
DELETE FROM wf_serial_number;
DELETE FROM sys_dict_type; DELETE FROM sys_dict_data;
DELETE FROM sys_menu; DELETE FROM sys_role_menu;
DELETE FROM wf_data_source;
DELETE FROM wf_page_def;
DROP TABLE IF EXISTS wf_biz_cust_rel_demo, wf_biz_onsite_service_demo, wf_biz_visit_demo,
  wf_biz_ai_mv3ybmpz77, wf_biz_leave_ledger_demo, wf_biz_material_pickup_demo,
  wf_biz_material_stock_demo, wf_biz_pickup_log_demo;
SQL

echo "[3] 第一遍导入 01→08"
for f in "$DEMO"/0[1-8]-*.sql; do
  if ! $M $AUTH $DB < "$f"; then echo "FAIL(first) $f"; exit 1; fi
done
echo "[4] 第二遍导入（幂等验证）"
for f in "$DEMO"/0[1-8]-*.sql; do
  if ! $M $AUTH $DB < "$f"; then echo "FAIL(second) $f"; exit 1; fi
done

echo "[5] 对账（两遍导入后行数应与预期一致，无翻倍）"
$M $AUTH $DB -e "
SELECT 'form_def(9)' k, COUNT(*) v FROM wf_form_def WHERE \`key\` IN ('cust_rel_demo','onsite_service_demo','visit_demo','ai_mv3ybmpz77','leave_apply_demo','leave_ledger_demo','material_pickup_demo','material_stock_demo','pickup_log_demo')
UNION ALL SELECT 'logic_flow(2)', COUNT(*) FROM wf_logic_flow
UNION ALL SELECT 'binding(2)', COUNT(*) FROM wf_form_logic_binding
UNION ALL SELECT 'process_draft(1)', COUNT(*) FROM wf_process_draft
UNION ALL SELECT 'node_config', COUNT(*) FROM wf_node_config
UNION ALL SELECT 'act_re_procdef(1)', COUNT(*) FROM act_re_procdef
UNION ALL SELECT 'act_ge_bytearray', COUNT(*) FROM act_ge_bytearray
UNION ALL SELECT 'ledger(3)', COUNT(*) FROM wf_biz_leave_ledger_demo
UNION ALL SELECT 'stock(3)', COUNT(*) FROM wf_biz_material_stock_demo
UNION ALL SELECT 'pickup(1)', COUNT(*) FROM wf_biz_material_pickup_demo
UNION ALL SELECT 'pickup_log(1)', COUNT(*) FROM wf_biz_pickup_log_demo
UNION ALL SELECT 'dict_data', COUNT(*) FROM sys_dict_data
UNION ALL SELECT 'menu(9319)', COUNT(*) FROM sys_menu WHERE id=9319;
SELECT flow_key, version, status FROM wf_logic_flow;
SELECT deployment_id_ FROM act_re_procdef;
"

echo "[6] 清理沙箱库"
$M $AUTH -e "DROP DATABASE IF EXISTS $DB;"
rm -rf "$WORK"
echo "VALIDATE PASS"
