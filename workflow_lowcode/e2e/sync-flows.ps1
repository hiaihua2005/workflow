# ============================================================
# 逻辑流 DSL 同步脚本：把 flows/*.json 推送到后端并发布
# - 幂等：按 flowKey 复用/创建
# - purchase_main.json 中的 __SUBFLOW_ID__ 会被替换为 purchase_subflow 的实际 id
# 前置：后端 8080 已运行
# ============================================================
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api'
$dir = Join-Path $PSScriptRoot 'flows'

function Api {
  param($Method, $Path, $Body, $Token)
  $headers = @{ 'X-Tenant-Id' = 'default' }
  if ($Token) { $headers.Authorization = "Bearer $Token" }
  if ($null -ne $Body) {
    $json = $Body | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Method $Method -Uri "$base$Path" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $json
  } else {
    Invoke-RestMethod -Method $Method -Uri "$base$Path" -Headers $headers
  }
}

$login = Api POST '/auth/login' @{ username = 'admin'; password = 'admin123' } $null
$token = $login.data.accessToken
Write-Host '[OK] 登录成功'

$flows = (Api GET '/v1/logic-flows?page=1&size=200' $null $token).data.content

function Get-Id($key) { ($flows | Where-Object { $_.flowKey -eq $key } | Select-Object -First 1).id }

function Push-Flow {
  param($Key, $Name, $File)
  $dsl = Get-Content -Path $File -Raw -Encoding utf8
  $id = Get-Id $Key
  if ($id) {
    Write-Host "[复用] 逻辑流 $Name -> $id"
  } else {
    $id = (Api POST '/v1/logic-flows' @{ key = $Key; name = $Name; description = '' } $token).data.id
    Write-Host "[新建] 逻辑流 $Name -> $id"
  }
  Api PUT "/v1/logic-flows/$id" @{ name = $Name; dsl = $dsl } $token | Out-Null
  Api POST "/v1/logic-flows/$id/publish" $null $token | Out-Null
  Write-Host "[OK] 已更新并发布 $Name ($Key) -> $id"
  return $id
}

Push-Flow 'audit_tick' '审计触达' (Join-Path $dir 'audit_tick.json') | Out-Null
Push-Flow 'purchase_subflow' '采购子流程-审计' (Join-Path $dir 'purchase_subflow.json') | Out-Null

# 主流程需要子流程 id：先取再做占位符替换
$subId = Get-Id 'purchase_subflow'
$mf = Join-Path $dir 'purchase_main.json'
$dsl = (Get-Content -Path $mf -Raw -Encoding utf8).Replace('__SUBFLOW_ID__', $subId)
$mainId = Get-Id 'purchase_main'
if (-not $mainId) {
  $mainId = (Api POST '/v1/logic-flows' @{ key = 'purchase_main'; name = '采购主流程'; description = '' } $token).data.id
}
Api PUT "/v1/logic-flows/$mainId" @{ name = '采购主流程'; dsl = $dsl } $token | Out-Null
Api POST "/v1/logic-flows/$mainId/publish" $null $token | Out-Null
Write-Host "[OK] 已更新并发布 采购主流程 (purchase_main) -> $mainId (subflow=$subId)"

$subId | Out-File -FilePath (Join-Path $PSScriptRoot 'subflow.id') -Encoding utf8 -NoNewline
$mainId | Out-File -FilePath (Join-Path $PSScriptRoot 'main.id') -Encoding utf8 -NoNewline
Write-Host '[完成] flow id 已写入 subflow.id / main.id'
