#!/bin/bash
# 运行时 E2E：数据族「业务表单/数据表」双路径 + rows[0].字段取值链路
set -u
BASE=http://127.0.0.1:8080/api/v1
export MDB=/home/z/my-project/mariadb-user
export LD_LIBRARY_PATH="$MDB/sysroot/usr/lib/x86_64-linux-gnu:$MDB/root/usr/lib/x86_64-linux-gnu"
M() { $MDB/root/bin/mariadb -u root -p740130 -S $MDB/mysql.sock workflow -N -s -e "$1"; }

echo "== 0. 临时物理表（无 tenant_id 列，验证无租户列场景） =="
M "DROP TABLE IF EXISTS tmp_e2e_dt;
CREATE TABLE tmp_e2e_dt (id BIGINT AUTO_INCREMENT PRIMARY KEY, product_code VARCHAR(50), stock_qty INT, note VARCHAR(200));"

TOKEN=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -H 'X-Tenant-Id: default' \
  -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8080/api/auth/login | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])')
[ -n "$TOKEN" ] && echo "login OK" || { echo "login FAIL"; exit 1; }
H1="Authorization: Bearer $TOKEN"; H2="X-Tenant-Id: default"

mkg() { python3 -c "
import json,sys
dsl = json.loads(sys.argv[1])
print(json.dumps({'name':sys.argv[2],'description':'临时验证流-测完删除','dsl':json.dumps(dsl,ensure_ascii=False)},ensure_ascii=False))
" "$1" "$2"; }

mkflow() { # $1=dsl_json $2=name $3=key → echo flow_id
  ID=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$3\",\"name\":\"$2\",\"description\":\"临时验证流-测完删除\"}" "$BASE/logic-flows" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("id","") or "ERR:"+json.dumps(d,ensure_ascii=False))')
  case "$ID" in ERR*|"") echo "$ID"; return 1;; esac
  UPD=$(curl -s -X PUT -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d "$(mkg "$1" "$2")" "$BASE/logic-flows/$ID")
  PUB=$(curl -s -X POST -H "$H1" -H "$H2" "$BASE/logic-flows/$ID/publish")
  echo "$ID"
}

echo "== 1. 流A：查台账表单（张三） → 任意表插入 {{dq1.rows[0].xxx}} =="
DSL_A='{"nodes":[
 {"id":"s","type":"START","name":"开始","x":80,"y":60},
 {"id":"dq1","type":"DATA_QUERY","name":"查台账","x":80,"y":176,"config":{"formKey":"leave_ledger_demo","filter":[{"column":"employeeName","op":"EQ","value":"张三"}],"size":10},"errorAction":"FAIL_FLOW"},
 {"id":"di1","type":"DATA_INSERT","name":"写临时表","x":80,"y":292,"config":{"formKey":"tmp_e2e_dt","data":[{"column":"product_code","value":"{{dq1.rows[0].employeeName}}"},{"column":"stock_qty","value":"{{dq1.rows[0].annualDays}}"},{"column":"note","value":"from-rows-index"}]},"errorAction":"FAIL_FLOW"},
 {"id":"e","type":"END","name":"结束","x":80,"y":408}],
 "edges":[{"id":"e1","source":"s","target":"dq1"},{"id":"e2","source":"dq1","target":"di1"},{"id":"e3","source":"di1","target":"e"}]}'
FA=$(mkflow "$DSL_A" "临时E2E-任意表插入" "tmp_e2e_dt_flow_a")
echo "flowA: $FA"
[ -n "${FA:-}" ] && case "$FA" in ERR*) exit 1;; esac
RUN=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d '{"vars":{}}' "$BASE/logic-flows/$FA/run")
echo "$RUN" | python3 -c "import sys,json;d=json.load(sys.stdin);data=d.get('data') or {};di=(data.get('outputVars') or {}).get('di1') or {};print('  run:',data.get('status'),'| insert out:',di)"

echo "== 2. 校验插入行（product_code=张三，stock_qty=张三 annual_days） =="
M "SELECT id,product_code,stock_qty,note FROM tmp_e2e_dt;"

echo "== 3. 流B：DATA_QUERY 任意表路径（formKey=物理表） → 任意表条件删除 =="
DSL_B='{"nodes":[
 {"id":"s","type":"START","name":"开始","x":80,"y":60},
 {"id":"dq2","type":"DATA_QUERY","name":"查临时表","x":80,"y":176,"config":{"formKey":"tmp_e2e_dt","filter":[{"column":"product_code","op":"EQ","value":"张三"}],"size":10},"errorAction":"FAIL_FLOW"},
 {"id":"dd2","type":"DATA_DELETE","name":"删临时表行","x":80,"y":292,"config":{"formKey":"tmp_e2e_dt","filter":[{"column":"note","op":"EQ","value":"from-rows-index"}]},"errorAction":"FAIL_FLOW"},
 {"id":"e","type":"END","name":"结束","x":80,"y":408}],
 "edges":[{"id":"e1","source":"s","target":"dq2"},{"id":"e2","source":"dq2","target":"dd2"},{"id":"e3","source":"dd2","target":"e"}]}'
FB=$(mkflow "$DSL_B" "临时E2E-任意表查询删除" "tmp_e2e_dt_flow_b")
echo "flowB: $FB"
case "$FB" in ERR*|"") exit 1;; esac
RUN2=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d '{"vars":{}}' "$BASE/logic-flows/$FB/run")
echo "$RUN2" | python3 -c "import sys,json;d=json.load(sys.stdin);data=d.get('data') or {};ov=data.get('outputVars') or {};print('  run:',data.get('status'),'| query total:',(ov.get('dq2') or {}).get('total'),'| deleted:',(ov.get('dd2') or {}).get('deleted'))"

echo "== 4. 校验删除后余行 =="
M "SELECT COUNT(*) FROM tmp_e2e_dt;"

echo "== 5. 清理 =="
M "DROP TABLE IF EXISTS tmp_e2e_dt;"
for FID in "$FA" "$FB"; do
  case "$FID" in ERR*|"") continue;; esac
  curl -s -X DELETE -H "$H1" -H "$H2" "$BASE/logic-flows/$FID" > /dev/null && echo "  flow $FID deleted"
done
echo "== E2E DONE =="
