# ============================================================
# E2E 资产一键创建脚本（幂等尽力：key 冲突时报错并继续其余资产）
# 前置：后端 8080 已运行；数据库已迁移（admin/admin123）
# 产物：5 表单 + 2 逻辑流 + 4 流程（部署）
# ============================================================
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api'
$script:results = [ordered]@{}

function Api {
  param($Method, $Path, $Body, $Token)
  $headers = @{ 'X-Tenant-Id' = 'default' }
  if ($Token) { $headers.Authorization = "Bearer $Token" }
  if ($null -ne $Body) {
    $json = $Body | ConvertTo-Json -Depth 14
    Invoke-RestMethod -Method $Method -Uri "$base$Path" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $json
  } else {
    Invoke-RestMethod -Method $Method -Uri "$base$Path" -Headers $headers
  }
}

# ---------- 登录 ----------
$login = Api POST '/auth/login' @{ username = 'admin'; password = 'admin123' } $null
$token = $login.data.access_token
if (-not $token) { $token = $login.data.accessToken }
if (-not $token) { throw "登录失败: $($login | ConvertTo-Json -Depth 5)" }
Write-Host '[OK] 登录成功'

# ---------- 用户 ID ----------
$users = (Api GET '/users?page=1&size=50' $null $token).data.rows
$adminId = ($users | Where-Object { $_.username -eq 'admin' }).id
$testId  = ($users | Where-Object { $_.username -eq 'test' }).id
if (-not $adminId -or -not $testId) { throw "找不到种子用户 admin/test" }
Write-Host "[OK] 用户: admin=$adminId test=$testId"

# ---------- 表单 ----------
function New-Form {
  param($Name, $Key, $SchemaJson)
  $existing = (Api GET '/v1/form-definitions?page=1&size=200' $null $token).data.content | Where-Object { $_.key -eq $Key } | Select-Object -First 1
  if ($existing) {
    $id = $existing.id
    Write-Host "[复用] 表单 $Name -> $id"
  } else {
    $r = Api POST "/v1/form-definitions?name=$Name&key=$Key" $null $token
    $id = $r.data.id
  }
  $body = @{ name = $Name; key = $Key; schema = $SchemaJson; processKey = $null; columnConfig = $null }
  Api PUT "/v1/form-definitions/$id" $body $token | Out-Null
  Api POST "/v1/form-definitions/$id/publish" $null $token | Out-Null
  Write-Host "[OK] 表单 $Name -> $id"
  return $id
}

$f1 = New-Form 'E2E差旅申请表' 'e2e_form_expense' @'
{"rule":[
 {"type":"input","field":"tripReason","title":"出差事由","props":{"placeholder":"请输入出差事由"},"validate":[{"required":true,"message":"请填写出差事由"}]},
 {"type":"inputNumber","field":"amount","title":"报销金额","props":{"placeholder":"请输入报销金额"},"validate":[{"required":true,"message":"请填写报销金额"}]},
 {"type":"select","field":"city","title":"出差城市","props":{"placeholder":"请选择城市"},"options":[{"label":"北京","value":"北京"},{"label":"上海","value":"上海"},{"label":"深圳","value":"深圳"}]},
 {"type":"datePicker","field":"departDate","title":"出发日期","props":{"placeholder":"请选择出发日期","type":"date"}}
],"option":{"labelWidth":"110px"}}
'@

$f2 = New-Form 'E2E打款执行表' 'e2e_form_pay' @'
{"rule":[
 {"type":"input","field":"payAccount","title":"收款账户","props":{"placeholder":"请输入收款账号"}},
 {"type":"inputNumber","field":"payAmount","title":"打款金额","props":{"placeholder":"请输入打款金额"}},
 {"type":"select","field":"payStatus","title":"打款状态","props":{"placeholder":"请选择打款状态"},"options":[{"label":"已打款","value":"已打款"},{"label":"暂缓打款","value":"暂缓"}]}
],"option":{"labelWidth":"110px"}}
'@

$f3 = New-Form 'E2E合同评审表' 'e2e_form_contract' @'
{"rule":[
 {"type":"input","field":"contractName","title":"合同名称","props":{"placeholder":"请输入合同名称"},"validate":[{"required":true,"message":"请填写合同名称"}]},
 {"type":"input","field":"contractSummary","title":"合同要点","props":{"type":"textarea","placeholder":"请输入合同要点","rows":3}},
 {"type":"select","field":"contractType","title":"合同类型","props":{"placeholder":"请选择合同类型"},"options":[{"label":"采购","value":"采购"},{"label":"销售","value":"销售"},{"label":"服务","value":"服务"}]},
 {"type":"inputNumber","field":"contractAmount","title":"合同金额","props":{"placeholder":"请输入合同金额"}}
],"option":{"labelWidth":"110px"}}
'@

$f4 = New-Form 'E2E数据同步表' 'e2e_form_sync' @'
{"rule":[
 {"type":"inputNumber","field":"inputValue","title":"输入值","props":{"placeholder":"请输入数值"},"validate":[{"required":true,"message":"请填写输入值"}]},
 {"type":"select","field":"syncTarget","title":"同步目标","props":{"placeholder":"请选择同步目标"},"options":[{"label":"ERP","value":"ERP"},{"label":"CRM","value":"CRM"}]},
 {"type":"input","field":"remark","title":"备注","props":{"placeholder":"请输入备注"}}
],"option":{"labelWidth":"110px"}}
'@

$f5 = New-Form 'E2E复核表' 'e2e_form_check' @'
{"rule":[
 {"type":"input","field":"checkComment","title":"复核说明","props":{"placeholder":"请输入复核说明"}}
],"option":{"labelWidth":"110px"}}
'@

# F7：项目结算表（TC-08 流程E：逻辑流节点替代监听器做变量绑定）
$f7 = New-Form 'E2E项目结算表' 'e2e_form_settle' @'
{"rule":[
 {"type":"input","field":"projectName","title":"项目名称","props":{"placeholder":"请输入项目名称"},"validate":[{"required":true,"message":"请填写项目名称"}]},
 {"type":"inputNumber","field":"baseAmount","title":"结算基数","props":{"placeholder":"请输入结算基数"},"validate":[{"required":true,"message":"请填写结算基数"}]}
],"option":{"labelWidth":"110px"}}
'@
$script:results.forms = @{ expense = $f1; pay = $f2; contract = $f3; sync = $f4; check = $f5; settle = $f7 }

# ---------- 逻辑流 ----------
$okDsl = @'
{"nodes":[
 {"id":"s","type":"START","name":"开始"},
 {"id":"sc","type":"SCRIPT","name":"双倍计算","config":{"language":"groovy","source":"inputValue * 2"},"results":[{"name":"doubled","mode":"WHOLE","type":"number"}],"errorAction":"FAIL_FLOW"},
 {"id":"e","type":"END","name":"结束"}
],"edges":[{"id":"s->sc","source":"s","target":"sc"},{"id":"sc->e","source":"sc","target":"e"}]}
'@
$failDsl = @'
{"nodes":[
 {"id":"s","type":"START","name":"开始"},
 {"id":"sc","type":"SCRIPT","name":"失败模拟","config":{"language":"groovy","source":"throw new RuntimeException('模拟同步失败')"},"errorAction":"FAIL_FLOW"},
 {"id":"e","type":"END","name":"结束"}
],"edges":[{"id":"s->sc","source":"s","target":"sc"},{"id":"sc->e","source":"sc","target":"e"}]}
'@

function New-LogicFlow {
  param($Key, $Name, $Dsl)
  $existing = (Api GET '/v1/logic-flows?page=1&size=200' $null $token).data.content | Where-Object { $_.flowKey -eq $Key } | Select-Object -First 1
  if ($existing) {
    $id = $existing.id
    Write-Host "[复用] 逻辑流 $Name -> $id"
  } else {
    $r = Api POST '/v1/logic-flows' @{ key = $Key; name = $Name } $token
    $id = $r.data.id
  }
  Api PUT "/v1/logic-flows/$id" @{ name = $Name; dsl = $Dsl } $token | Out-Null
  Api POST "/v1/logic-flows/$id/publish" $null $token | Out-Null
  Write-Host "[OK] 逻辑流 $Name -> $id"
  return $id
}
$lfOk = New-LogicFlow 'e2e_demo_ok' 'E2E双倍计算' $okDsl
$lfFail = New-LogicFlow 'e2e_demo_fail' 'E2E失败模拟' $failDsl

# TC-08 流程E：结算计算逻辑流（SCRIPT 可视化低码节点，替代 Java 监听器写法）
$settleDsl = @'
{"nodes":[
 {"id":"s","type":"START","name":"开始"},
 {"id":"sc","type":"SCRIPT","name":"含税结算","config":{"language":"groovy","source":"baseAmount * 1.06"},"results":[{"name":"settleResult","mode":"WHOLE","type":"number"}],"errorAction":"FAIL_FLOW"},
 {"id":"e","type":"END","name":"结束"}
],"edges":[{"id":"s->sc","source":"s","target":"sc"},{"id":"sc->e","source":"sc","target":"e"}]}
'@
$lfSettle = New-LogicFlow 'e2e_settle_flow' 'E2E结算计算' $settleDsl
$script:results.logicFlows = @{ ok = $lfOk; fail = $lfFail; settle = $lfSettle }

# ---------- 流程 ----------
function New-Process {
  param($Name, $Key, $BpmnXml, $NodeConfigs)
  $xml = $BpmnXml.Replace('__ADMIN__', "$adminId").Replace('__TEST__', "$testId").Replace('__F1__', $f1).Replace('__F2__', $f2).Replace('__F3__', $f3).Replace('__F4__', $f4).Replace('__F5__', $f5).Replace('__F7__', $f7)
  $cfgs = [ordered]@{}
  foreach ($k in $NodeConfigs.Keys) {
    $cfgs[$k] = $NodeConfigs[$k].Replace('__ADMIN__', "$adminId").Replace('__TEST__', "$testId").Replace('__F1__', $f1).Replace('__F2__', $f2).Replace('__F3__', $f3).Replace('__F4__', $f4).Replace('__F5__', $f5).Replace('__F7__', $f7)
  }
  $NodeConfigs = $cfgs
  $existing = (Api GET '/v1/process-definitions/drafts?page=1&size=200' $null $token).data.content | Where-Object { $_.key -eq $Key } | Select-Object -First 1
  if ($existing) {
    $id = $existing.id
    Write-Host "[复用] 流程 $Name -> $id"
  } else {
    $draft = Api POST "/v1/process-definitions/drafts?name=$Name&key=$Key" $null $token
    $id = $draft.data.id
  }
  Api PUT "/v1/process-definitions/$id/design" @{ name = $Name; key = $Key; description = "E2E 自动创建"; bpmnXml = $xml; nodeConfigs = $NodeConfigs } $token | Out-Null
  $dep = Api POST "/v1/process-definitions/$id/deploy" $null $token
  Write-Host "[OK] 流程 $Name 部署完成 -> draft=$id procDefId=$($dep.data.processDefinitionId)"
  return @{ draftId = $id; procDefId = $dep.data.processDefinitionId }
}

# --- 流程级配置模板 ---
$procCfgBase = @'
{"approvalPolicy":{"deduplication":{"enabled":false,"scope":"GLOBAL","mode":"CONSECUTIVE","action":"AUTO_PASS","skipSameAsInitiator":false},"operations":{"allowReject":true,"allowAddSign":true,"allowTransfer":true,"allowDelegate":true},"commentPolicy":{"enabled":__COMMENT_ALL__,"scope":"ALL"},"signaturePolicy":{"enabled":false,"useLast":false,"allowUpload":false,"required":false},"comment":{"disabled":false,"disallowDelete":false,"disallowAttachment":false},"approveRecall":true,"retakeSkipApproved":false},"titleRule":{"enabled":false,"pattern":""},"summaryRule":{"enabled":false,"fields":[],"showInSms":false},"dynamicProcess":false,"timeoutRules":[],"starterScope":{"mode":"ALL","userIds":[],"roleIds":[]},"adminUserIds":["__ADMIN__"],"numberRule":{"enabled":false,"pattern":""},"variableMappings":__VARMAP__}
'@

# ============ 流程A：差旅报销 e2e_expense ============
$p1xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:wf="http://workflow.com/schema/bpmn/wf" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://workflow.com/e2e">
  <bpmn:process id="e2e_expense" name="E2E差旅报销审批" isExecutable="true">
    <bpmn:startEvent id="start_e1" name="开始" />
    <bpmn:userTask id="start_init" name="发起填报" wf:nodeRole="initiator" flowable:assignee="${initiator}" />
    <bpmn:exclusiveGateway id="gw_amount" name="金额判断" default="f4" />
    <bpmn:userTask id="dir_approve" name="总监审批" />
    <bpmn:userTask id="fin_or" name="财务初审" />
    <bpmn:userTask id="cashier_pay" name="出纳打款" />
    <bpmn:endEvent id="end_e1" name="结束" />
    <bpmn:sequenceFlow id="f1" sourceRef="start_e1" targetRef="start_init" />
    <bpmn:sequenceFlow id="f2" sourceRef="start_init" targetRef="gw_amount" />
    <bpmn:sequenceFlow id="f3" name="大额" sourceRef="gw_amount" targetRef="dir_approve">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${amount &gt; 10000}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="f4" name="小额" sourceRef="gw_amount" targetRef="fin_or" />
    <bpmn:sequenceFlow id="f5" sourceRef="dir_approve" targetRef="fin_or" />
    <bpmn:sequenceFlow id="f6" sourceRef="fin_or" targetRef="cashier_pay" />
    <bpmn:sequenceFlow id="f7" sourceRef="cashier_pay" targetRef="end_e1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="di_1">
    <bpmndi:BPMNPlane id="plane_1" bpmnElement="e2e_expense">
      <bpmndi:BPMNShape id="sh_start_e1" bpmnElement="start_e1"><dc:Bounds x="150" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_start_init" bpmnElement="start_init"><dc:Bounds x="240" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_gw" bpmnElement="gw_amount"><dc:Bounds x="390" y="193" width="50" height="50" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_dir" bpmnElement="dir_approve"><dc:Bounds x="510" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_fin" bpmnElement="fin_or"><dc:Bounds x="690" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_cashier" bpmnElement="cashier_pay"><dc:Bounds x="860" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_end" bpmnElement="end_e1"><dc:Bounds x="1020" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed_f1" bpmnElement="f1"><di:waypoint x="186" y="218" /><di:waypoint x="240" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f2" bpmnElement="f2"><di:waypoint x="340" y="218" /><di:waypoint x="390" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f3" bpmnElement="f3"><di:waypoint x="440" y="218" /><di:waypoint x="510" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f4" bpmnElement="f4"><di:waypoint x="415" y="243" /><di:waypoint x="415" y="330" /><di:waypoint x="740" y="330" /><di:waypoint x="740" y="258" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f5" bpmnElement="f5"><di:waypoint x="610" y="218" /><di:waypoint x="690" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f6" bpmnElement="f6"><di:waypoint x="790" y="218" /><di:waypoint x="860" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_f7" bpmnElement="f7"><di:waypoint x="960" y="218" /><di:waypoint x="1020" y="218" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'@

$p1cfg = @{
  start_init = (@'
{"basic":{"name":"发起填报"},"form":{"formDefId":"__F1__"},"initiator":{"reInitiate":true,"disallowRecall":false}}
'@)
  dir_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__"]},"operations":{"allowPass":true,"allowRefuse":true,"allowReturn":true,"allowAddSign":true,"allowTransfer":true,"allowDelegate":true},"returnOptions":{"restartFromHere":false,"chooseStartNode":true,"mustAddSign":false},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN","priority":100,"dueHours":48,"timeout":{"enabled":true,"duration":24,"action":"remind"}}
'@)
  fin_or = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__","__TEST__"],"multiMode":"or_sign"},"operations":{"allowPass":true,"allowRefuse":true,"allowReturn":true},"commentRequired":false,"priority":50,"dueHours":24}
'@)
  cashier_pay = (@'
{"taskRole":"handler","approval":{"type":"user","userIds":["__ADMIN__"]},"form":{"formDefId":"__F2__"},"operations":{"allowPass":true,"allowReturn":true,"allowAddSign":true},"commentRequired":true,"commentRequiredScope":"ALL","priority":75,"dueHours":8}
'@)
  __PROCESS__ = ($procCfgBase.Replace('__COMMENT_ALL__','false').Replace('__VARMAP__','[{"variable":"amount","source":"form:initiator","sourceField":"amount"}]'))
}
$p1 = New-Process 'E2E差旅报销审批' 'e2e_expense' $p1xml $p1cfg

# ============ 流程B：合同评审 e2e_contract ============
$p2xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:wf="http://workflow.com/schema/bpmn/wf" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://workflow.com/e2e">
  <bpmn:process id="e2e_contract" name="E2E合同评审审批" isExecutable="true">
    <bpmn:startEvent id="start_e2" name="开始" />
    <bpmn:userTask id="start_init2" name="发起填报" wf:nodeRole="initiator" flowable:assignee="${initiator}" />
    <bpmn:userTask id="legal_cs" name="法务会签" />
    <bpmn:userTask id="review_vote" name="评审投票" />
    <bpmn:userTask id="seq_approve" name="依次审批" />
    <bpmn:endEvent id="end_e2" name="结束" />
    <bpmn:sequenceFlow id="g1" sourceRef="start_e2" targetRef="start_init2" />
    <bpmn:sequenceFlow id="g2" sourceRef="start_init2" targetRef="legal_cs" />
    <bpmn:sequenceFlow id="g3" sourceRef="legal_cs" targetRef="review_vote" />
    <bpmn:sequenceFlow id="g4" sourceRef="review_vote" targetRef="seq_approve" />
    <bpmn:sequenceFlow id="g5" sourceRef="seq_approve" targetRef="end_e2" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="di_2">
    <bpmndi:BPMNPlane id="plane_2" bpmnElement="e2e_contract">
      <bpmndi:BPMNShape id="sh_start2" bpmnElement="start_e2"><dc:Bounds x="140" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_init2" bpmnElement="start_init2"><dc:Bounds x="240" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_legal" bpmnElement="legal_cs"><dc:Bounds x="420" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_vote" bpmnElement="review_vote"><dc:Bounds x="600" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_seq" bpmnElement="seq_approve"><dc:Bounds x="780" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_end2" bpmnElement="end_e2"><dc:Bounds x="950" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed_g1" bpmnElement="g1"><di:waypoint x="176" y="218" /><di:waypoint x="240" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_g2" bpmnElement="g2"><di:waypoint x="340" y="218" /><di:waypoint x="420" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_g3" bpmnElement="g3"><di:waypoint x="520" y="218" /><di:waypoint x="600" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_g4" bpmnElement="g4"><di:waypoint x="700" y="218" /><di:waypoint x="780" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_g5" bpmnElement="g5"><di:waypoint x="880" y="218" /><di:waypoint x="950" y="218" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'@

$p2cfg = @{
  start_init2 = (@'
{"basic":{"name":"发起填报"},"form":{"formDefId":"__F3__"},"initiator":{"reInitiate":true}}
'@)
  legal_cs = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__","__TEST__"],"multiMode":"countersign","passRatio":50},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN","priority":50}
'@)
  review_vote = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__","__TEST__"],"multiMode":"vote_sign","votePassRatio":50,"weights":{"__ADMIN__":2,"__TEST__":1}},"operations":{"allowPass":true,"allowRefuse":true},"signature":{"enabled":true,"useLast":false,"allowUpload":true,"required":false},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN"}
'@)
  seq_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__","__TEST__"],"multiMode":"sequential"},"operations":{"allowPass":true,"allowRefuse":true},"signature":{"enabled":true,"useLast":false,"allowUpload":false,"required":false},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN"}
'@)
  __PROCESS__ = ($procCfgBase.Replace('__COMMENT_ALL__','true').Replace('__VARMAP__','[]'))
}
$p2 = New-Process 'E2E合同评审审批' 'e2e_contract' $p2xml $p2cfg

# ============ 流程C：数据同步 e2e_sync ============
$p3xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:wf="http://workflow.com/schema/bpmn/wf" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://workflow.com/e2e">
  <bpmn:process id="e2e_sync" name="E2E数据同步审批" isExecutable="true">
    <!-- v4: end listener moved to internal endEvent so subprocess-scoped vars are readable -->
    <bpmn:startEvent id="start_e3" name="开始" />
    <bpmn:userTask id="start_init3" name="发起填报" wf:nodeRole="initiator" flowable:assignee="${initiator}" />
    <bpmn:parallelGateway id="gw_split" name="并行拆分" />
    <bpmn:serviceTask id="svc_ok" name="同步计算" />
    <bpmn:serviceTask id="svc_fail" name="失败同步" />
    <bpmn:boundaryEvent id="err_e3" name="同步失败" attachedToRef="svc_fail" cancelActivity="true">
      <bpmn:errorEventDefinition />
    </bpmn:boundaryEvent>
    <bpmn:userTask id="fallback_fix" name="异常处理" />
    <bpmn:userTask id="comp_approve" name="合规审批" />
    <bpmn:boundaryEvent id="timer_e3" name="超时升级" attachedToRef="comp_approve" cancelActivity="false">
      <bpmn:timerEventDefinition>
        <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT1M</bpmn:timeDuration>
      </bpmn:timerEventDefinition>
    </bpmn:boundaryEvent>
    <bpmn:userTask id="escal_approve" name="升级审批" />
    <bpmn:parallelGateway id="gw_join" name="并行汇聚" />
    <bpmn:subProcess id="sub_flow" name="内嵌子流程">
      <bpmn:startEvent id="sub_start" name="子开始" />
      <bpmn:userTask id="sub_review" name="子流程复核" />
      <bpmn:endEvent id="sub_end" name="子结束" />
      <bpmn:sequenceFlow id="sf1" sourceRef="sub_start" targetRef="sub_review" />
      <bpmn:sequenceFlow id="sf2" sourceRef="sub_review" targetRef="sub_end" />
    </bpmn:subProcess>
    <bpmn:callActivity id="call_check" name="转复核流程" calledElement="e2e_simple" flowable:inheritVariables="true" />
    <bpmn:endEvent id="end_e3" name="结束" />
    <bpmn:sequenceFlow id="h1" sourceRef="start_e3" targetRef="start_init3" />
    <bpmn:sequenceFlow id="h2" sourceRef="start_init3" targetRef="gw_split" />
    <bpmn:sequenceFlow id="h3" sourceRef="gw_split" targetRef="svc_ok" />
    <bpmn:sequenceFlow id="h4" sourceRef="gw_split" targetRef="svc_fail" />
    <bpmn:sequenceFlow id="h5" sourceRef="svc_ok" targetRef="comp_approve" />
    <bpmn:sequenceFlow id="h6" sourceRef="comp_approve" targetRef="gw_join" />
    <bpmn:sequenceFlow id="h7" sourceRef="timer_e3" targetRef="escal_approve" />
    <bpmn:sequenceFlow id="h8" sourceRef="escal_approve" targetRef="gw_join" />
    <bpmn:sequenceFlow id="h9" sourceRef="err_e3" targetRef="fallback_fix" />
    <bpmn:sequenceFlow id="h10" sourceRef="fallback_fix" targetRef="gw_join" />
    <bpmn:sequenceFlow id="h11" sourceRef="gw_join" targetRef="sub_flow" />
    <bpmn:sequenceFlow id="h12" sourceRef="sub_flow" targetRef="call_check" />
    <bpmn:sequenceFlow id="h13" sourceRef="call_check" targetRef="end_e3" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="di_3">
    <bpmndi:BPMNPlane id="plane_3" bpmnElement="e2e_sync">
      <bpmndi:BPMNShape id="sh_start3" bpmnElement="start_e3"><dc:Bounds x="110" y="242" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_init3" bpmnElement="start_init3"><dc:Bounds x="190" y="220" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_gwsplit" bpmnElement="gw_split"><dc:Bounds x="360" y="235" width="50" height="50" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_svcok" bpmnElement="svc_ok"><dc:Bounds x="490" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_svcfail" bpmnElement="svc_fail"><dc:Bounds x="490" y="370" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_err" bpmnElement="err_e3"><dc:Bounds x="522" y="452" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_fallback" bpmnElement="fallback_fix"><dc:Bounds x="650" y="370" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_comp" bpmnElement="comp_approve"><dc:Bounds x="660" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_timer" bpmnElement="timer_e3"><dc:Bounds x="692" y="106" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_escal" bpmnElement="escal_approve"><dc:Bounds x="830" y="60" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_gwjoin" bpmnElement="gw_join"><dc:Bounds x="850" y="235" width="50" height="50" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_sub" bpmnElement="sub_flow" isExpanded="true"><dc:Bounds x="980" y="180" width="330" height="180" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_substart" bpmnElement="sub_start"><dc:Bounds x="1005" y="252" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_subreview" bpmnElement="sub_review"><dc:Bounds x="1085" y="230" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_subend" bpmnElement="sub_end"><dc:Bounds x="1235" y="252" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_call" bpmnElement="call_check"><dc:Bounds x="1380" y="230" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_end3" bpmnElement="end_e3"><dc:Bounds x="1540" y="252" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed_h1" bpmnElement="h1"><di:waypoint x="146" y="260" /><di:waypoint x="190" y="260" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h2" bpmnElement="h2"><di:waypoint x="290" y="260" /><di:waypoint x="360" y="260" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h3" bpmnElement="h3"><di:waypoint x="385" y="235" /><di:waypoint x="385" y="190" /><di:waypoint x="490" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h4" bpmnElement="h4"><di:waypoint x="385" y="285" /><di:waypoint x="385" y="410" /><di:waypoint x="490" y="410" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h5" bpmnElement="h5"><di:waypoint x="590" y="190" /><di:waypoint x="660" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h6" bpmnElement="h6"><di:waypoint x="710" y="230" /><di:waypoint x="710" y="260" /><di:waypoint x="850" y="260" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h7" bpmnElement="h7"><di:waypoint x="728" y="106" /><di:waypoint x="728" y="100" /><di:waypoint x="830" y="100" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h8" bpmnElement="h8"><di:waypoint x="930" y="100" /><di:waypoint x="975" y="100" /><di:waypoint x="975" y="235" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h9" bpmnElement="h9"><di:waypoint x="540" y="488" /><di:waypoint x="540" y="520" /><di:waypoint x="620" y="520" /><di:waypoint x="620" y="450" /><di:waypoint x="650" y="450" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h10" bpmnElement="h10"><di:waypoint x="750" y="410" /><di:waypoint x="800" y="410" /><di:waypoint x="800" y="315" /><di:waypoint x="875" y="315" /><di:waypoint x="875" y="285" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h11" bpmnElement="h11"><di:waypoint x="900" y="260" /><di:waypoint x="940" y="260" /><di:waypoint x="940" y="270" /><di:waypoint x="980" y="270" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h12" bpmnElement="h12"><di:waypoint x="1310" y="270" /><di:waypoint x="1380" y="270" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_h13" bpmnElement="h13"><di:waypoint x="1480" y="270" /><di:waypoint x="1540" y="270" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_sf1" bpmnElement="sf1"><di:waypoint x="1041" y="270" /><di:waypoint x="1085" y="270" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_sf2" bpmnElement="sf2"><di:waypoint x="1185" y="270" /><di:waypoint x="1235" y="270" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'@

$p3cfg = @{
  start_init3 = (@'
{"basic":{"name":"发起填报"},"form":{"formDefId":"__F4__"},"initiator":{"reInitiate":true}}
'@)
  svc_ok = (@'
{"serviceTask":{"serviceType":"logicflow","logicFlow":{"flowKey":"e2e_demo_ok","inParams":[{"source":"inputValue","target":"inputValue"}],"outParams":[{"source":"doubled","target":"calcResult"}],"errorAction":"FAIL_FLOW"}}}
'@)
  svc_fail = (@'
{"serviceTask":{"serviceType":"logicflow","logicFlow":{"flowKey":"e2e_demo_fail","inParams":[],"outParams":[],"errorAction":"FAIL_FLOW"}}}
'@)
  comp_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__TEST__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN"}
'@)
  escal_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN"}
'@)
  fallback_fix = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__ADMIN__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":true,"commentRequiredScope":"REJECT_RETURN"}
'@)
  sub_review = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__TEST__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":false}
'@)
  sub_flow = (@'
{"basic":{"name":"内嵌子流程"},"subProcess":{"inParams":[{"source":"calcResult","target":"innerVal"}],"outParams":[{"source":"innerVal","target":"subEcho"}]}}
'@)
  call_check = (@'
{"basic":{"name":"转复核流程"},"callActivity":{"calledElement":"e2e_simple","inParams":[],"outParams":[]}}
'@)
  __PROCESS__ = ($procCfgBase.Replace('__COMMENT_ALL__','false').Replace('__VARMAP__','[{"variable":"inputValue","source":"form:initiator","sourceField":"inputValue"}]'))
}
$p3 = New-Process 'E2E数据同步审批' 'e2e_sync' $p3xml $p3cfg

# ============ 流程D：简单复核 e2e_simple ============
$p4xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:wf="http://workflow.com/schema/bpmn/wf" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://workflow.com/e2e">
  <bpmn:process id="e2e_simple" name="E2E简单复核" isExecutable="true">
    <bpmn:startEvent id="start_e4" name="开始" />
    <bpmn:userTask id="start_init4" name="发起填报" wf:nodeRole="initiator" flowable:assignee="${initiator}" />
    <bpmn:userTask id="check_approve" name="复核审批" />
    <bpmn:endEvent id="end_e4" name="结束" />
    <bpmn:sequenceFlow id="k1" sourceRef="start_e4" targetRef="start_init4" />
    <bpmn:sequenceFlow id="k2" sourceRef="start_init4" targetRef="check_approve" />
    <bpmn:sequenceFlow id="k3" sourceRef="check_approve" targetRef="end_e4" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="di_4">
    <bpmndi:BPMNPlane id="plane_4" bpmnElement="e2e_simple">
      <bpmndi:BPMNShape id="sh_start4" bpmnElement="start_e4"><dc:Bounds x="140" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_init4" bpmnElement="start_init4"><dc:Bounds x="240" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_check" bpmnElement="check_approve"><dc:Bounds x="420" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_end4" bpmnElement="end_e4"><dc:Bounds x="590" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed_k1" bpmnElement="k1"><di:waypoint x="176" y="218" /><di:waypoint x="240" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_k2" bpmnElement="k2"><di:waypoint x="340" y="218" /><di:waypoint x="420" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_k3" bpmnElement="k3"><di:waypoint x="520" y="218" /><di:waypoint x="590" y="218" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'@

$p4cfg = @{
  start_init4 = (@'
{"basic":{"name":"发起填报"},"form":{"formDefId":"__F5__"},"initiator":{"reInitiate":true}}
'@)
  check_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__TEST__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":false}
'@)
  __PROCESS__ = ($procCfgBase.Replace('__COMMENT_ALL__','false').Replace('__VARMAP__','[]'))
}
# 注意：P4 必须先部署（P3 CallActivity 引用其 key），调整顺序：先 P4 后 P3 已由脚本顺序保证？P3 在前 —— Flowable 部署 CallActivity 引用不校验被调流程存在性（运行时才解析），故顺序无碍
$p4 = New-Process 'E2E简单复核' 'e2e_simple' $p4xml $p4cfg

# ============ 流程E：项目结算 e2e_settle（TC-08） ============
# 对照用例：流程C 的子流程变量绑定靠 SubProcessVariableListener（引擎监听器）实现；
# 本流程改为「内嵌子流程内放逻辑流服务任务节点」，出入参映射全部是节点属性面板的可视化配置
# （serviceTask.logicFlow.inParams/outParams），数据加工在逻辑流设计器中编排——业务零 Java 代码
$p5xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:wf="http://workflow.com/schema/bpmn/wf" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://workflow.com/e2e">
  <bpmn:process id="e2e_settle" name="E2E项目结算审批" isExecutable="true">
    <bpmn:startEvent id="start_e5" name="开始" />
    <bpmn:userTask id="start_init5" name="发起填报" wf:nodeRole="initiator" flowable:assignee="${initiator}" />
    <bpmn:userTask id="dept_approve" name="部门审批" />
    <bpmn:subProcess id="sub_settle" name="结算子流程">
      <bpmn:startEvent id="start_sub" name="子开始" />
      <bpmn:serviceTask id="svc_settle" name="结算计算" />
      <bpmn:endEvent id="end_sub" name="子结束" />
      <bpmn:sequenceFlow id="s1" sourceRef="start_sub" targetRef="svc_settle" />
      <bpmn:sequenceFlow id="s2" sourceRef="svc_settle" targetRef="end_sub" />
    </bpmn:subProcess>
    <bpmn:userTask id="final_confirm" name="汇总确认" />
    <bpmn:endEvent id="end_e5" name="结束" />
    <bpmn:sequenceFlow id="m1" sourceRef="start_e5" targetRef="start_init5" />
    <bpmn:sequenceFlow id="m2" sourceRef="start_init5" targetRef="dept_approve" />
    <bpmn:sequenceFlow id="m3" sourceRef="dept_approve" targetRef="sub_settle" />
    <bpmn:sequenceFlow id="m4" sourceRef="sub_settle" targetRef="final_confirm" />
    <bpmn:sequenceFlow id="m5" sourceRef="final_confirm" targetRef="end_e5" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="di_5">
    <bpmndi:BPMNPlane id="plane_5" bpmnElement="e2e_settle">
      <bpmndi:BPMNShape id="sh_start5" bpmnElement="start_e5"><dc:Bounds x="140" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_init5" bpmnElement="start_init5"><dc:Bounds x="220" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_dept" bpmnElement="dept_approve"><dc:Bounds x="380" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_sub" bpmnElement="sub_settle" isExpanded="true"><dc:Bounds x="540" y="152" width="380" height="132" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_startsub" bpmnElement="start_sub"><dc:Bounds x="570" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_svc" bpmnElement="svc_settle"><dc:Bounds x="660" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_endsub" bpmnElement="end_sub"><dc:Bounds x="820" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_final" bpmnElement="final_confirm"><dc:Bounds x="980" y="178" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh_end5" bpmnElement="end_e5"><dc:Bounds x="1140" y="200" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed_m1" bpmnElement="m1"><di:waypoint x="176" y="218" /><di:waypoint x="220" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_m2" bpmnElement="m2"><di:waypoint x="320" y="218" /><di:waypoint x="380" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_m3" bpmnElement="m3"><di:waypoint x="480" y="218" /><di:waypoint x="540" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_s1" bpmnElement="s1"><di:waypoint x="606" y="218" /><di:waypoint x="660" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_s2" bpmnElement="s2"><di:waypoint x="760" y="218" /><di:waypoint x="820" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_m4" bpmnElement="m4"><di:waypoint x="920" y="218" /><di:waypoint x="980" y="218" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed_m5" bpmnElement="m5"><di:waypoint x="1080" y="218" /><di:waypoint x="1140" y="218" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'@

$p5cfg = @{
  start_init5 = (@'
{"basic":{"name":"发起填报"},"form":{"formDefId":"__F7__"},"initiator":{"reInitiate":true}}
'@)
  dept_approve = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__TEST__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":false}
'@)
  svc_settle = (@'
{"serviceTask":{"serviceType":"logicflow","logicFlow":{"flowKey":"e2e_settle_flow","inParams":[{"source":"baseAmount","target":"baseAmount"}],"outParams":[{"source":"settleResult","target":"settleResult"}],"errorAction":"FAIL_FLOW"}}}
'@)
  final_confirm = (@'
{"taskRole":"approver","approval":{"type":"user","userIds":["__TEST__"]},"operations":{"allowPass":true,"allowRefuse":true},"commentRequired":false}
'@)
  __PROCESS__ = ($procCfgBase.Replace('__COMMENT_ALL__','false').Replace('__VARMAP__','[{"variable":"baseAmount","source":"form:initiator","sourceField":"baseAmount"}]'))
}
$p5 = New-Process 'E2E项目结算审批' 'e2e_settle' $p5xml $p5cfg

# ---------- 汇总 ----------
$script:results.processes = @{ expense = $p1; contract = $p2; sync = $p3; simple = $p4; settle = $p5 }
$script:results.users = @{ admin = $adminId; test = $testId }
$script:results | ConvertTo-Json -Depth 6 | Out-File -FilePath (Join-Path $PSScriptRoot 'artifacts.json') -Encoding utf8
Write-Host "`n[完成] 全部资产已创建并部署，清单已写入 artifacts.json"
$script:results | ConvertTo-Json -Depth 6
