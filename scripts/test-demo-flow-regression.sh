#!/bin/bash
# 存量演示流回归：leave_ledger_sync（无op等值筛选+DATA_UPDATE建档）/ pickup_stock_sync（SUB扣库存）
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-Tenant-Id: default' -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8080/api/auth/login | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H1="Authorization: Bearer $TOKEN"; H2="X-Tenant-Id: default"; BASE="http://127.0.0.1:8080/api/v1"

LIST=$(curl -s -H "$H1" -H "$H2" "$BASE/logic-flows?size=50")
for KEY in leave_ledger_sync pickup_stock_sync; do
  ID=$(echo "$LIST" | python3 -c "
import sys,json
d=json.load(sys.stdin)
content=(d.get('data') or {}).get('content') or (d.get('data') or {}).get('rows') or []
print(next((f['id'] for f in content if f.get('flowKey')=='$KEY'),''))")
  echo "=== $KEY (id=$ID) ==="
  if [ "$KEY" = "leave_ledger_sync" ]; then
    VARS='{"vars":{"formData":{"applicant":"回归测试员","department":"质检部","leaveType":"年假","days":1}}}'
  else
    VARS='{"vars":{"formData":{"productCode":"P003","qty":2,"reason":"回归测试"}}}'
  fi
  curl -s -X POST -H "$H1" -H "$H2" -H 'Content-Type: application/json' -d "$VARS" "$BASE/logic-flows/$ID/run" | python3 -c "
import sys,json
d=json.load(sys.stdin)
data=d.get('data') or {}
print('  status:', data.get('status'))
out=data.get('outputVars') or {}
for k,v in (out or {}).items():
    if isinstance(v,dict): print('  ',k,':',{kk:v.get(kk) for kk in list(v)[:4]})
    else: print('  ',k,':',v)
err=data.get('errorMessage')
if err: print('  error:', err)"
done
echo "=== 副作用核对 ==="
curl -s -H "$H1" -H "$H2" "http://127.0.0.1:8080/api/v1/biz-data/material_stock_demo?size=10" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for r in ((d.get('data') or {}).get('records') or []): print('  stock:', r['data'].get('productCode'), r['data'].get('stockQty'))"
curl -s -H "$H1" -H "$H2" "http://127.0.0.1:8080/api/v1/biz-data/pickup_log_demo?size=3" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  pickup_log total:', (d.get('data') or {}).get('total'))"
