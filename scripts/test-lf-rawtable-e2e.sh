#!/bin/bash
# 需求r22-E2E：数据族节点任意数据表路径（查询/新增/删除直连物理表）+ rows[0] 列表索引取值 + 表单路径回归
# 临时表自建自清理；表单路径用例自恢复（插入行随即条件删除）
set -e
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-Tenant-Id: default' -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8080/api/auth/login | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H1="Authorization: Bearer $TOKEN"; H2="X-Tenant-Id: default"; BASE="http://127.0.0.1:8080/api/v1"
export MDB=/home/z/my-project/mariadb-user
export LD_LIBRARY_PATH="$MDB/sysroot/usr/lib/x86_64-linux-gnu:$MDB/root/usr/lib/x86_64-linux-gnu"
MDBCLI="$MDB/root/bin/mariadb -u root -p740130 -S $MDB/mysql.sock workflow"

# 创建/删除临时逻辑流（PUT DSL → publish → run → 可选输出检查 → 删流）
run_flow () {
  local KEY="$1" NAME="$2" DSL_PY="$3" NODE="$4" VARS="$5"
  if [ -z "$VARS" ]; then VARS='{"vars":{}}'; fi
  local CREATE FLOW_ID DSL UPD PUB RUN
  CREATE=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$KEY\",\"name\":\"$NAME\",\"description\":\"临时验证流-测完删除\"}" "$BASE/logic-flows")
  FLOW_ID=$(echo "$CREATE" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('id',''))")
  if [ -z "$FLOW_ID" ]; then echo "  创建失败: $CREATE"; return 1; fi
  DSL=$(python3 -c "
import json
dsl = ($DSL_PY)
print(json.dumps({'name':'$NAME','description':'临时验证流','dsl':json.dumps(dsl,ensure_ascii=False)},ensure_ascii=False))")
  UPD=$(curl -s -X PUT -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d "$DSL" "$BASE/logic-flows/$FLOW_ID")
  PUB=$(curl -s -X POST -H "$H1" -H "$H2" "$BASE/logic-flows/$FLOW_ID/publish")
  RUN=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d "$VARS" "$BASE/logic-flows/$FLOW_ID/run")
  python3 -c "
import sys,json
upd=json.loads(sys.argv[1]); pub=json.loads(sys.argv[2]); d=json.loads(sys.argv[3])
data=d.get('data') or {}
print('  ['+sys.argv[4]+'] update:', upd.get('code'), '| publish:', pub.get('code'), '| run status:', data.get('status'))
out=(data.get('outputVars') or {}).get(sys.argv[4])
if out is not None:
    if isinstance(out, dict):
        print('  output keys:', {k: out.get(k) for k in list(out)[:6]})
    else:
        print('  output:', out)
err=data.get('errorMessage')
if err: print('  error:', str(err)[:300])" "$UPD" "$PUB" "$RUN" "$NODE"
  curl -s -X DELETE -H "$H1" -H "$H2" "$BASE/logic-flows/$FLOW_ID" > /dev/null
}

echo "== 0. 准备临时物理表 e2e_lf_raw_demo / e2e_lf_raw_log =="
$MDBCLI -e "DROP TABLE IF EXISTS e2e_lf_raw_demo; CREATE TABLE e2e_lf_raw_demo (id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64), name VARCHAR(128), qty INT, tenant_id VARCHAR(64) NOT NULL DEFAULT 'default') ENGINE=InnoDB;
INSERT INTO e2e_lf_raw_demo (code,name,qty) VALUES ('C001','甲',10),('C002','乙',20),('C003','丙',30);
DROP TABLE IF EXISTS e2e_lf_raw_log; CREATE TABLE e2e_lf_raw_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64), name VARCHAR(128), tenant_id VARCHAR(64) NOT NULL DEFAULT 'default') ENGINE=InnoDB;"
echo "  种子 3 行（qty 10/20/30）"

echo "== A. 任意表查询：filter qty GTE 20（期望 total=2）=="
run_flow "lf_raw_qa_t1" "任意表查询-GTE" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dq1','type':'DATA_QUERY','name':'查物理表','x':80,'y':176,
  'config':{'formKey':'e2e_lf_raw_demo','filter':[{'column':'qty','op':'GTE','value':'20'}],'size':10}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dq1'},{'id':'e2','source':'dq1','target':'end1'}]}" "dq1"

echo "== B. 任意表查询：keyword+keywordColumn LIKE 乙（期望 total=1）=="
run_flow "lf_raw_qb_t1" "任意表查询-关键字" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dq1','type':'DATA_QUERY','name':'关键字查询','x':80,'y':176,
  'config':{'formKey':'e2e_lf_raw_demo','keyword':'乙','keywordColumn':'name','size':10}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dq1'},{'id':'e2','source':'dq1','target':'end1'}]}" "dq1"

echo "== C. 任意表链路：查询 rows[0] 索引取值 → 写入任意表（期望 log 行 code=C001,name=甲）=="
run_flow "lf_raw_chain_t1" "rows0索引+任意表新增" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dq1','type':'DATA_QUERY','name':'查第一行','x':80,'y':176,
  'config':{'formKey':'e2e_lf_raw_demo','size':1}},
 {'id':'di1','type':'DATA_INSERT','name':'写任意表','x':80,'y':292,
  'config':{'formKey':'e2e_lf_raw_log','data':[{'column':'code','value':'{{dq1.rows[0].code}}'},{'column':'name','value':'{{dq1.rows[0].name}}'}]}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':408}],
 'edges':[{'id':'e1','source':'start1','target':'dq1'},{'id':'e2','source':'dq1','target':'di1'},{'id':'e3','source':'di1','target':'end1'}]}" "di1"
echo -n "  log 表实内容: "; $MDBCLI -N -e "SELECT code,name,tenant_id FROM e2e_lf_raw_log;"

echo "== D. 任意表按条件删除：qty LT 30（期望 deleted=2，剩 1 行）=="
run_flow "lf_raw_del_t1" "任意表条件删除" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dd1','type':'DATA_DELETE','name':'条件删','x':80,'y':176,
  'config':{'formKey':'e2e_lf_raw_demo','filter':[{'column':'qty','op':'LT','value':'30'}]}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dd1'},{'id':'e2','source':'dd1','target':'end1'}]}" "dd1"
echo -n "  剩余行: "; $MDBCLI -N -e "SELECT COUNT(*) FROM e2e_lf_raw_demo;"

echo "== E. 任意表按 ID 删除（期望 deleted=1，剩 0 行）=="
LAST_ID=$($MDBCLI -N -e "SELECT id FROM e2e_lf_raw_demo ORDER BY id DESC LIMIT 1;")
echo "  目标 id=$LAST_ID"
run_flow "lf_raw_delid_t1" "任意表按ID删除" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dd1','type':'DATA_DELETE','name':'按ID删','x':80,'y':176,
  'config':{'formKey':'e2e_lf_raw_demo','id':'$LAST_ID'}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dd1'},{'id':'e2','source':'dd1','target':'end1'}]}" "dd1"
echo -n "  剩余行: "; $MDBCLI -N -e "SELECT COUNT(*) FROM e2e_lf_raw_demo;"

echo "== F. 直连业务表物理名 wf_biz_material_stock_demo（期望 raw 路径 total=1，含 P001）=="
run_flow "lf_raw_form_t1" "物理表名直连" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dq1','type':'DATA_QUERY','name':'查业务物理表','x':80,'y':176,
  'config':{'formKey':'wf_biz_material_stock_demo','filter':[{'column':'productCode','op':'EQ','value':'P001'}],'size':10}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dq1'},{'id':'e2','source':'dq1','target':'end1'}]}" "dq1"

echo "== G. 表单路径回归：查询 material_stock_demo（total=1）=="
run_flow "lf_form_q_t1" "表单查询回归" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'dq1','type':'DATA_QUERY','name':'查表单','x':80,'y':176,
  'config':{'formKey':'material_stock_demo','filter':[{'column':'productCode','op':'EQ','value':'P001'}],'size':10}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'dq1'},{'id':'e2','source':'dq1','target':'end1'}]}" "dq1"

echo "== H. 表单路径回归：新增 pickup_log_demo 后按条件删除（自恢复，期望 deleted=1）=="
run_flow "lf_form_idel_t1" "表单增删回归" "
{'nodes':[
 {'id':'start1','type':'START','name':'开始','x':80,'y':60},
 {'id':'di1','type':'DATA_INSERT','name':'写表单','x':80,'y':176,
  'config':{'formKey':'pickup_log_demo','data':[{'column':'productCode','value':'E2E_R22'},{'column':'productName','value':'临时验证'},{'column':'qty','value':'1'},{'column':'pickupBy','value':'admin'},{'column':'sourceNote','value':'r22自恢复'}]}},
 {'id':'dd1','type':'DATA_DELETE','name':'条件删','x':80,'y':292,
  'config':{'formKey':'pickup_log_demo','filter':[{'column':'productCode','op':'EQ','value':'E2E_R22'}]}},
 {'id':'end1','type':'END','name':'结束','x':80,'y':408}],
 'edges':[{'id':'e1','source':'start1','target':'di1'},{'id':'e2','source':'di1','target':'dd1'},{'id':'e3','source':'dd1','target':'end1'}]}" "dd1"

echo "== 清理临时表 =="
$MDBCLI -e "DROP TABLE IF EXISTS e2e_lf_raw_demo; DROP TABLE IF EXISTS e2e_lf_raw_log;"
echo "E2E 完成"
