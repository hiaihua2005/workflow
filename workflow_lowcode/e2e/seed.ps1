# E2E 种子脚本：登录 → 建 4 张业务表单并发布 → 建 3 条逻辑流并发布 → 表单触发绑定
# 用法: powershell -File seed.ps1   （需 backend 8080 / frontend 3000 已启动）
$ErrorActionPreference = 'Stop'
# 子进程可能继承系统代理导致 localhost 被拦截：禁用默认代理直连
[System.Net.WebRequest]::DefaultWebProxy = $null
$Base = 'http://localhost:8080'
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$StatePath = Join-Path $Dir 'seed-state.json'

function Invoke-Api($Method, $Path, $Body, $Token) {
  $headers = @{ 'X-Tenant-Id' = 'default' }
  if ($Token) { $headers['Authorization'] = "Bearer $Token" }
  $json = if ($null -ne $Body) { ConvertTo-Json -InputObject $Body -Depth 40 -Compress } else { $null }
  $resp = Invoke-RestMethod -Method $Method -Uri "$Base$Path" -Headers $headers `
    -ContentType 'application/json; charset=utf-8' -Body $json
  if ($resp.code -ne 0 -and $resp.code -ne 200) { throw "API $Path 失败: $($resp | ConvertTo-Json -Depth 5)" }
  return $resp.data
}

# 1. 登录
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/auth/login" `
  -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$token = $login.data.accessToken
Write-Host "[seed] login ok"

# 2. 业务表单（key → 列定义 + 表单组件 schema）
function SchemaOf($rules) {
  return '{"rule":[' + ($rules -join ',') + '],"option":{"labelWidth":"110px"}}'
}
$S = @{}
$S['purchase_req'] = @{ schema = SchemaOf @(
  '{"type":"input","field":"item_name","title":"品名","props":{"placeholder":"请输入品名"},"validate":[{"required":true,"message":"请填写品名"}]}',
  '{"type":"inputNumber","field":"qty","title":"数量","props":{"placeholder":"请输入数量"},"validate":[{"required":true,"message":"请填写数量"}]}',
  '{"type":"inputNumber","field":"unit_price","title":"单价","props":{"placeholder":"请输入单价"},"validate":[{"required":true,"message":"请填写单价"}]}',
  '{"type":"inputNumber","field":"total_amount","title":"总额","props":{"placeholder":"自动计算"}}',
  '{"type":"select","field":"dept","title":"申请部门","props":{"placeholder":"请选择部门"},"options":[{"label":"IT","value":"IT"},{"label":"FIN","value":"FIN"},{"label":"OTHER","value":"OTHER"}]}',
  '{"type":"select","field":"urgency","title":"紧急度","props":{"placeholder":"请选择"},"options":[{"label":"NORMAL","value":"NORMAL"},{"label":"HIGH","value":"HIGH"}]}',
  '{"type":"input","field":"applicant","title":"申请人","props":{"placeholder":"请输入申请人"}}'
); columns = @(
  @{ key='item_name'; label='品名'; columnType='VARCHAR'; length=128; required=$true },
  @{ key='qty'; label='数量'; columnType='INT'; required=$true },
  @{ key='unit_price'; label='单价'; columnType='DECIMAL'; scale=2; required=$true },
  @{ key='total_amount'; label='总额'; columnType='DECIMAL'; scale=2 },
  @{ key='dept'; label='部门'; columnType='VARCHAR'; length=32; indexed=$true },
  @{ key='urgency'; label='紧急度'; columnType='VARCHAR'; length=16 },
  @{ key='status'; label='状态'; columnType='VARCHAR'; length=32 },
  @{ key='applicant'; label='申请人'; columnType='VARCHAR'; length=64 }
) }
$S['supplier_price'] = @{ schema = SchemaOf @(
  '{"type":"input","field":"item_name","title":"品名","props":{"placeholder":"请输入品名"},"validate":[{"required":true,"message":"请填写品名"}]}',
  '{"type":"input","field":"supplier","title":"供应商","props":{"placeholder":"请输入供应商"}}',
  '{"type":"inputNumber","field":"price","title":"价格","props":{"placeholder":"请输入价格"}}'
); columns = @(
  @{ key='item_name'; label='品名'; columnType='VARCHAR'; length=128; required=$true; unique=$true },
  @{ key='supplier'; label='供应商'; columnType='VARCHAR'; length=128 },
  @{ key='price'; label='价格'; columnType='DECIMAL'; scale=2 }
) }
$S['supplier_quote'] = @{ schema = SchemaOf @(
  '{"type":"input","field":"supplier","title":"供应商","props":{"placeholder":"请输入供应商"},"validate":[{"required":true,"message":"请填写供应商"}]}',
  '{"type":"input","field":"item","title":"品名","props":{"placeholder":"请输入品名"}}',
  '{"type":"inputNumber","field":"price","title":"报价","props":{"placeholder":"请输入报价"}}'
); columns = @(
  @{ key='supplier'; label='供应商'; columnType='VARCHAR'; length=128; indexed=$true },
  @{ key='item'; label='品名'; columnType='VARCHAR'; length=128 },
  @{ key='price'; label='报价'; columnType='DECIMAL'; scale=2 }
) }
$S['audit_log'] = @{ schema = SchemaOf @(
  '{"type":"input","field":"req_id","title":"申请单号","props":{"placeholder":"申请单号"}}',
  '{"type":"input","field":"action","title":"动作","props":{"placeholder":"动作类型"}}',
  '{"type":"input","field":"note","title":"备注","props":{"type":"textarea","placeholder":"备注","rows":3}}',
  '{"type":"inputNumber","field":"amount","title":"金额","props":{"placeholder":"金额"}}'
); columns = @(
  @{ key='req_id'; label='申请单号'; columnType='VARCHAR'; length=64; indexed=$true },
  @{ key='action'; label='动作'; columnType='VARCHAR'; length=64; indexed=$true },
  @{ key='note'; label='备注'; columnType='VARCHAR'; length=255 },
  @{ key='amount'; label='金额'; columnType='DECIMAL'; scale=2 }
) }
$forms = $S
$formIds = @{}
foreach ($entry in $forms.GetEnumerator()) {
  $key = $entry.Key
  $id = $null
  try {
    $created = Invoke-Api Post "/api/v1/form-definitions?name=$key&key=$key&type=BUSINESS" $null $token
    $id = $created.id
  } catch {
    # 幂等：key 已存在 → by-key 找回
    $id = (Invoke-Api Get "/api/v1/form-definitions/by-key/$key" $null $token).id
    Write-Host "[seed] form $key exists, reuse $id"
  }
  $colJson = $entry.Value.columns | ConvertTo-Json -Depth 10 -Compress
  $putBody = @{ name = $key; key = $key; schema = $entry.Value.schema; columnConfig = $colJson; processKey = $null }
  Invoke-Api Put "/api/v1/form-definitions/$id" $putBody $token | Out-Null
  Invoke-Api Post "/api/v1/form-definitions/$id/publish" $null $token | Out-Null
  $formIds[$key] = $id
  Write-Host "[seed] form $key published ($id)"
}

# 3. 逻辑流：先子流程（主流程 DSL 引用其 id）
function New-Flow($key, $name, $dslPath) {
  $f = $null
  try {
    $f = Invoke-Api Post '/api/v1/logic-flows' @{ key = $key; name = $name; description = "E2E $key" } $token
  } catch {
    $list = Invoke-Api Get "/api/v1/logic-flows?keyword=$key" $null $token
    $rows = if ($list.content) { $list.content } else { $list }
    $f = $rows | Where-Object { $_.flowKey -eq $key -or $_.key -eq $key } | Select-Object -First 1
    if (-not $f) { throw "流 $key 创建失败且未找到: $_" }
    Write-Host "[seed] flow $key exists, reuse $($f.id)"
  }
  $dsl = (Get-Content -Raw -Encoding UTF8 $dslPath)
  Invoke-Api Put "/api/v1/logic-flows/$($f.id)" @{ name = $name; description = "E2E $key"; dsl = $dsl } $token | Out-Null
  Invoke-Api Post "/api/v1/logic-flows/$($f.id)/publish" $null $token | Out-Null
  Write-Host "[seed] flow $key published ($($f.id))"
  return $f.id
}
$FlowsDir = Join-Path $Dir 'flows'
$subId = New-Flow 'purchase_subflow' '采购子流程-审计' (Join-Path $FlowsDir 'purchase_subflow.json')
$tickId = New-Flow 'audit_tick' '审计触达' (Join-Path $FlowsDir 'audit_tick.json')

# 主流程：替换子流程 id 占位
$mainPath = Join-Path $FlowsDir 'purchase_main.json'
$mainRaw = (Get-Content -Raw -Encoding UTF8 $mainPath) -replace '__SUBFLOW_ID__', $subId
$tmpMain = Join-Path $Dir 'flows/_purchase_main.resolved.json'
[System.IO.File]::WriteAllText($tmpMain, $mainRaw, (New-Object System.Text.UTF8Encoding($false)))
$mainId = New-Flow 'purchase_main' '采购主流程' $tmpMain

# 4. 表单触发绑定：audit_log AFTER_CREATE → audit_tick（幂等：存在即跳过）
try {
  Invoke-Api Post '/api/v1/form-logic-bindings' @{
    formType = 'BUSINESS'; formKey = 'audit_log'; triggerType = 'AFTER_CREATE'
    flowKey = 'audit_tick'; executionMode = 'SYNC_IN_TX'; enabled = $true
    description = 'E2E 表单触发'
  } $token | Out-Null
  Write-Host "[seed] binding audit_log/AFTER_CREATE -> audit_tick"
} catch {
  Write-Host "[seed] binding exists, skip"
}

@{ backend = $Base; forms = $formIds; flows = @{ purchase_main = $mainId; purchase_subflow = $subId; audit_tick = $tickId } } |
  ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $StatePath
Write-Host "[seed] state -> $StatePath"
