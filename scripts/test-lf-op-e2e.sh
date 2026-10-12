#!/bin/bash
# 修改21-E2E v2：DATA_QUERY 比较符实测（create key + PUT dsl + publish + run + 清理）
set -e
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-Tenant-Id: default' -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8080/api/auth/login | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H1="Authorization: Bearer $TOKEN"; H2="X-Tenant-Id: default"; BASE="http://127.0.0.1:8080/api/v1"

run_case () {
  local KEY="$1" NAME="$2" FILTERS="$3" NODE="$4" EXPECT="$5"
  echo "=== 用例 $NAME（期望 $EXPECT）==="
  CREATE=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$KEY\",\"name\":\"$NAME\",\"description\":\"临时验证流-测完删除\"}" "$BASE/logic-flows")
  FLOW_ID=$(echo "$CREATE" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('id',''))")
  if [ -z "$FLOW_ID" ]; then echo "创建失败: $CREATE"; return; fi
  # DSL JSON 原文（update 接口 dsl 为字符串字段）
  DSL=$(python3 -c "
import json
filters = json.loads('''$FILTERS''')
dsl = {'nodes':[
  {'id':'start1','type':'START','name':'开始','x':80,'y':60},
  {'id':'$NODE','type':'DATA_QUERY','name':'筛选测试','x':80,'y':176,
   'config':{'formKey':'material_stock_demo','filter':filters,'size':10},'errorAction':'FAIL_FLOW'},
  {'id':'end1','type':'END','name':'结束','x':80,'y':300}],
 'edges':[{'id':'e1','source':'start1','target':'$NODE'},{'id':'e2','source':'$NODE','target':'end1'}]}
print(json.dumps({'name':'$NAME','description':'临时验证流','dsl':json.dumps(dsl,ensure_ascii=False)},ensure_ascii=False))
")
  UPD=$(curl -s -X PUT -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d "$DSL" "$BASE/logic-flows/$FLOW_ID")
  echo "$UPD" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  update:', d.get('code'), d.get('msg',''))" 
  PUB=$(curl -s -X POST -H "$H1" -H "$H2" "$BASE/logic-flows/$FLOW_ID/publish")
  echo "$PUB" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  publish:', d.get('code'), d.get('msg',''))"
  RUN=$(curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d '{"vars":{}}' "$BASE/logic-flows/$FLOW_ID/run")
  echo "$RUN" | python3 -c "
import sys,json
d=json.load(sys.stdin)
data=d.get('data') or {}
out=(data.get('outputVars') or {}).get('$NODE') or {}
rows=out.get('rows') or []
print('  run status:', data.get('status'), '| total:', out.get('total'))
for r in rows: print('   row:', r.get('productCode'), r.get('stockQty'))"
  curl -s -X DELETE -H "$H1" -H "$H2" "$BASE/logic-flows/$FLOW_ID" > /dev/null && echo "  清理完成"
}

run_case "lf_op_gte_t1" "op测试-GTE" '[{"column":"stockQty","op":"GTE","value":"100"}]' "dq_gte" "P001=185 共1行"
run_case "lf_op_lt_t1"  "op测试-LT+NOT_NULL" '[{"column":"stockQty","op":"LT","value":"100"},{"column":"productCode","op":"NOT_NULL"}]' "dq_lt" "P002=50,P003=80 共2行"
run_case "lf_op_eq_t1"  "op测试-无op缺省EQ(存量兼容)" '[{"column":"productCode","value":"P002"}]' "dq_eq" "P002=50 共1行"
run_case "lf_op_isnull" "op测试-IS_NULL" '[{"column":"productName","op":"IS_NULL"}]' "dq_nu" "0行不报错"
