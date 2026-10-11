#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_logicflow_demo.py — 构造「逻辑流联动」两个业务场景演示资产（幂等）

场景 1（工作流审批 → 逻辑流 → 业务表单）：
  员工在流程中心发起「请假申请」→ 主管(admin)审批通过、流程结束后，
  AFTER_PROCESS_FINISH 触发逻辑流 leave_ledger_sync：
    DATA_UPSERT 员工年假台账（不存在自动建档：总额 10 天）→ DATA_UPDATE 累加已用天数。

场景 2（业务表单操作 → 逻辑流 → 更新另外 2 个业务表单）：
  在「物料领用单」登记一条领用（AFTER_CREATE），触发逻辑流 pickup_stock_sync：
    DATA_UPDATE 物料库存扣减数量（SUB）+ DATA_INSERT 领用流水写入一条明细。

资产清单：
  表单   leave_ledger_demo(业务/employeeName 唯一) leave_apply_demo(工作流)
         material_stock_demo(业务/productCode 唯一) material_pickup_demo(业务) pickup_log_demo(业务)
  逻辑流 leave_ledger_sync / pickup_stock_sync（均发布）
  绑定   WORKFLOW/leave_apply_demo/AFTER_PROCESS_FINISH → leave_ledger_sync (SYNC_IN_TX)
         BUSINESS/material_pickup_demo/AFTER_CREATE → pickup_stock_sync (SYNC_IN_TX)
  流程   leave_approval_demo（开始→发起节点(自动完成)→主管审批(assignee=1 即 admin)→结束，__PROCESS__ 绑定发起表单）
  种子   物料库存 3 行（P001×200 / P002×50 / P003×80）

自测（API 级，浏览器 E2E 由 agent-browser 另行执行）：
  场景 2：领用 P001×5 → 校验库存 195 + 流水 +1 行 → 清理测试痕迹（恢复 200/删流水/删领用单）
  场景 1：发起 王五 2 天 → admin 审批通过 → 校验台账 王五 usedDays=2（结果保留作演示态）

用法：python3 scripts/build_logicflow_demo.py
"""
import json
import sys
import time
import urllib.error
import urllib.request
import urllib.parse

BASE = 'http://127.0.0.1:8080/api'
TENANT = {'X-Tenant-Id': 'default', 'Content-Type': 'application/json'}


def call(method, path, body=None, token=None, ok_codes=(200,)):
    req = urllib.request.Request(BASE + path, method=method)
    for k, v in TENANT.items():
        req.add_header(k, v)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            text = resp.read().decode()
    except urllib.error.HTTPError as e:
        text = e.read().decode()
    try:
        r = json.loads(text)
    except Exception:
        print('NON-JSON', method, path, text[:300])
        sys.exit(1)
    if r.get('code') not in ok_codes:
        print(f'API FAIL {method} {path} -> {r}')
    return r


def login():
    r = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})
    assert r.get('data', {}).get('accessToken'), r
    return r['data']['accessToken']


# ---------------------------------------------------------------- 表单
def col(key, label, ctype, length=None, unique=False, component=None):
    d = {'key': key, 'label': label, 'columnType': ctype, 'length': length, 'scale': None,
         'required': False, 'unique': unique, 'indexed': False}
    if component:
        d['componentType'] = component
    return d


def ensure_form(token, name, key, ftype, schema, column_config=None):
    lst = call('GET', f'/v1/form-definitions?size=200&key={urllib.parse.quote(key)}', token=token)
    rows = (lst.get('data') or {})
    rows = rows.get('list') or rows.get('records') or rows.get('content') or (rows if isinstance(rows, list) else [])
    found = next((x for x in rows if x.get('key') == key), None)
    if not found:
        r = call('POST', f'/v1/form-definitions?name={urllib.parse.quote(name)}&key={key}&type={ftype}', token=token)
        assert r.get('code') == 200, r
        fid = r['data']['id']
        print(f'  表单 created: {key} {fid}')
    else:
        fid = found['id']
        print(f'  表单已存在: {key} {fid} ({found.get("status")})')
    body = {'name': name, 'key': key, 'schema': json.dumps(schema, ensure_ascii=False),
            'columnConfig': json.dumps(column_config, ensure_ascii=False) if column_config else None}
    r = call('PUT', f'/v1/form-definitions/{fid}', body, token=token)
    assert r.get('code') == 200, r
    if found and found.get('status') == 'PUBLISHED':
        pub = call('POST', f'/v1/form-definitions/{fid}/publish', token=token)
        print(f'  publish(重发): {key} -> {pub.get("code")}')
    else:
        pub = call('POST', f'/v1/form-definitions/{fid}/publish', token=token)
        assert pub.get('code') == 200, pub
        print(f'  publish: {key} OK')
    return fid


# ---------------------------------------------------------------- 逻辑流
def ensure_logic_flow(token, key, name, description, dsl):
    lst = call('GET', f'/v1/logic-flows?size=100&key={urllib.parse.quote(key)}', token=token)
    rows = (lst.get('data') or {})
    rows = rows.get('records') or rows.get('list') or rows.get('content') or (rows if isinstance(rows, list) else [])
    found = next((x for x in rows if x.get('flowKey') == key), None)
    if not found:
        r = call('POST', '/v1/logic-flows', {'key': key, 'name': name, 'description': description}, token=token)
        assert r.get('code') == 200, r
        fid = r['data']['id']
        print(f'  逻辑流 created: {key} {fid}')
    else:
        fid = found['id']
        print(f'  逻辑流已存在: {key} {fid}')
    r = call('PUT', f'/v1/logic-flows/{fid}', {'name': name, 'description': description,
                                               'dsl': json.dumps(dsl, ensure_ascii=False)}, token=token)
    assert r.get('code') == 200, r
    pub = call('POST', f'/v1/logic-flows/{fid}/publish', token=token)
    assert pub.get('code') == 200, pub
    print(f'  逻辑流 published: {key} v{pub["data"].get("version")}')
    return fid


def ensure_binding(token, form_type, form_key, trigger, flow_key, mode, desc):
    lst = call('GET', f'/v1/form-logic-bindings?formType={form_type}&formKey={urllib.parse.quote(form_key)}', token=token)
    rows = lst.get('data') or []
    found = next((x for x in rows if x.get('triggerType') == trigger and x.get('flowKey') == flow_key), None)
    if found:
        print(f'  绑定已存在: {form_key}.{trigger} -> {flow_key}')
        return found['id']
    r = call('POST', '/v1/form-logic-bindings',
             {'formType': form_type, 'formKey': form_key, 'triggerType': trigger, 'flowKey': flow_key,
              'executionMode': mode, 'enabled': True, 'description': desc}, token=token)
    assert r.get('code') == 200, r
    print(f'  绑定 created: {form_key}.{trigger} -> {flow_key} ({mode})')
    return r['data']['id']


# ---------------------------------------------------------------- 流程
def ensure_process(token, key, name, bpmn_xml, form_def_id):
    lst = call('GET', '/v1/process-definitions/drafts?size=100', token=token)
    rows = ((lst.get('data') or {}).get('content')) or []
    found = next((x for x in rows if x.get('processKey') == key), None)
    if not found:
        r = call('POST', f'/v1/process-definitions/drafts?name={urllib.parse.quote(name)}&key={key}', token=token)
        assert r.get('code') == 200, r
        pid = r['data']['id']
        print(f'  流程草稿 created: {key} {pid}')
    else:
        pid = found['id']
        print(f'  流程草稿已存在: {key} {pid} ({found.get("status")})')
    save = call('PUT', f'/v1/process-definitions/{pid}/design',
                {'name': name, 'key': key, 'bpmnXml': bpmn_xml,
                 'nodeConfigs': {'__PROCESS__': json.dumps(
                     {'form': {'formDefId': form_def_id, 'fieldPermissions': {}}}, ensure_ascii=False)}},
                token=token)
    assert save.get('code') == 200, save
    dep = call('POST', f'/v1/process-definitions/{pid}/deploy', token=token)
    if dep.get('code') == 200:
        print(f'  流程 deployed: {key}')
    elif '未变化' in str(dep.get('message', '')):
        print(f'  流程已部署(内容未变化): {key}')
    else:
        print(f'  流程 deploy 异常: {dep}')
        sys.exit(1)
    return pid


def find_deployed_definition(token, proc_key):
    lst = call('GET', '/v1/deployed-processes?size=100', token=token)
    rows = (lst.get('data') or {})
    rows = rows.get('records') or rows.get('content') or rows.get('list') or (rows if isinstance(rows, list) else [])
    for x in rows:
        if x.get('key') == proc_key:
            return x
    return None


# ---------------------------------------------------------------- 业务数据
def biz_rows(token, form_key, size=200):
    r = call('GET', f'/v1/biz-data/{form_key}?page=1&size={size}', token=token)
    assert r.get('code') == 200, r
    return (r.get('data') or {}).get('records') or []


def biz_create(token, form_key, data):
    r = call('POST', f'/v1/biz-data/{form_key}', data, token=token)
    assert r.get('code') == 200, r
    return r['data']


def biz_delete(token, form_key, data_id):
    r = call('DELETE', f'/v1/biz-data/{form_key}/{data_id}', token=token, ok_codes=(200, 500))
    print(f'  清理 {form_key} {data_id} -> {r.get("code")}')


# ---------------------------------------------------------------- DSL
LEDGER_DSL = {
    'nodes': [
        {'id': 'start1', 'type': 'START', 'name': '开始', 'x': 100, 'y': 160},
        {'id': 'dq_ledger', 'type': 'DATA_QUERY', 'name': '查询台账是否已建档', 'x': 300, 'y': 160,
         'config': {
             'formKey': 'leave_ledger_demo',
             'filter': [{'column': 'employeeName', 'value': '{{formData.applicant}}'}],
             'size': 1,
         },
         # KEY 拆包：输出 Map 的 total(命中行数)写入扁平变量 total，供 CONDITION 比较
         'results': [{'name': 'total', 'mode': 'KEY', 'type': 'number', 'desc': '命中行数'}]},
        {'id': 'cond_empty', 'type': 'CONDITION', 'name': '台账不存在?', 'x': 520, 'y': 160,
         'config': {'variable': 'total', 'operator': 'EQ', 'value': '0'}},
        {'id': 'di_create', 'type': 'DATA_INSERT', 'name': '首次自动建档(总额10天)', 'x': 700, 'y': 60,
         'config': {
             'formKey': 'leave_ledger_demo',
             'data': [
                 {'column': 'employeeName', 'value': '{{formData.applicant}}'},
                 {'column': 'department', 'value': '{{formData.department}}'},
                 {'column': 'annualDays', 'value': '10'},
                 {'column': 'usedDays', 'value': '0'},
             ],
         }},
        {'id': 'du_add', 'type': 'DATA_UPDATE', 'name': '累加已用天数', 'x': 900, 'y': 160,
         'config': {
             'table': 'wf_biz_leave_ledger_demo',
             'setOps': [{'column': 'usedDays', 'mode': 'ADD', 'value': '{{formData.days}}'}],
             'where': [{'column': 'employeeName', 'op': 'EQ', 'value': '{{formData.applicant}}'}],
         }},
        {'id': 'end1', 'type': 'END', 'name': '结束', 'x': 1100, 'y': 160},
    ],
    'edges': [
        {'id': 'e1', 'source': 'start1', 'target': 'dq_ledger'},
        {'id': 'e2', 'source': 'dq_ledger', 'target': 'cond_empty'},
        {'id': 'e3', 'source': 'cond_empty', 'target': 'di_create', 'branch': 'true'},
        {'id': 'e4', 'source': 'cond_empty', 'target': 'du_add', 'branch': 'false'},
        {'id': 'e5', 'source': 'di_create', 'target': 'du_add'},
        {'id': 'e6', 'source': 'du_add', 'target': 'end1'},
    ],
}

PICKUP_DSL = {
    'nodes': [
        {'id': 'start1', 'type': 'START', 'name': '开始', 'x': 120, 'y': 160},
        {'id': 'du_stock', 'type': 'DATA_UPDATE', 'name': '扣减物料库存', 'x': 350, 'y': 160,
         'config': {
             'table': 'wf_biz_material_stock_demo',
             'setOps': [{'column': 'stockQty', 'mode': 'SUB', 'value': '{{formData.qty}}'}],
             'where': [{'column': 'productCode', 'op': 'EQ', 'value': '{{formData.productCode}}'}],
         }},
        {'id': 'di_log', 'type': 'DATA_INSERT', 'name': '写入领用流水', 'x': 590, 'y': 160,
         'config': {
             'formKey': 'pickup_log_demo',
             'data': [
                 {'column': 'productCode', 'value': '{{formData.productCode}}'},
                 {'column': 'productName', 'value': '{{formData.productName}}'},
                 {'column': 'qty', 'value': '{{formData.qty}}'},
                 {'column': 'pickupBy', 'value': '{{formData.pickupBy}}'},
                 {'column': 'sourceNote', 'value': '来源领用单 {{dataId}}'},
             ],
         }},
        {'id': 'end1', 'type': 'END', 'name': '结束', 'x': 820, 'y': 160},
    ],
    'edges': [
        {'id': 'e1', 'source': 'start1', 'target': 'du_stock'},
        {'id': 'e2', 'source': 'du_stock', 'target': 'di_log'},
        {'id': 'e3', 'source': 'di_log', 'target': 'end1'},
    ],
}

LEAVE_BPMN = '''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  targetNamespace="http://workflow.local/demo">
  <bpmn:process id="leave_approval_demo" name="请假审批-逻辑流演示" isExecutable="true">
    <bpmn:startEvent id="start_1" name="提交请假申请" />
    <bpmn:sequenceFlow id="flow_0" sourceRef="start_1" targetRef="apply_1" />
    <bpmn:userTask id="apply_1" name="提交请假申请" flowable:assignee="${initiator}" />
    <bpmn:sequenceFlow id="flow_1" sourceRef="apply_1" targetRef="approve_1" />
    <bpmn:userTask id="approve_1" name="主管审批" flowable:assignee="1" />
    <!-- assignee 用 userId（平台待办按 user.id 查询），1=admin；发起节点 apply_1 为第一个 UserTask（启动时自动完成） -->
    <bpmn:sequenceFlow id="flow_2" sourceRef="approve_1" targetRef="end_1" />
    <bpmn:endEvent id="end_1" name="审批完成" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_1">
    <bpmndi:BPMNPlane id="Plane_1" bpmnElement="leave_approval_demo">
      <bpmndi:BPMNShape id="start_1_di" bpmnElement="start_1">
        <dc:Bounds x="160" y="180" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="apply_1_di" bpmnElement="apply_1">
        <dc:Bounds x="250" y="158" width="110" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="approve_1_di" bpmnElement="approve_1">
        <dc:Bounds x="430" y="158" width="110" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_1_di" bpmnElement="end_1">
        <dc:Bounds x="610" y="180" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_0_di" bpmnElement="flow_0">
        <di:waypoint x="196" y="198" />
        <di:waypoint x="250" y="198" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_1_di" bpmnElement="flow_1">
        <di:waypoint x="360" y="198" />
        <di:waypoint x="430" y="198" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_2_di" bpmnElement="flow_2">
        <di:waypoint x="540" y="198" />
        <di:waypoint x="610" y="198" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'''


# ---------------------------------------------------------------- 表单 schema
LEDGER_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'employeeName', 'title': '员工姓名', 'props': {'placeholder': '请输入员工姓名'}},
    {'type': 'input', 'field': 'department', 'title': '所属部门'},
    {'type': 'inputNumber', 'field': 'annualDays', 'title': '年假总额(天)', 'props': {'min': 0, 'precision': 0}},
    {'type': 'inputNumber', 'field': 'usedDays', 'title': '已用天数', 'props': {'min': 0, 'precision': 0}},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': True, 'resetBtn': False}}
LEDGER_COLS = [
    col('employeeName', '员工姓名', 'VARCHAR', 64, unique=True, component='input'),
    col('department', '所属部门', 'VARCHAR', 255, component='input'),
    col('annualDays', '年假总额', 'INT', None, component='inputNumber'),
    col('usedDays', '已用天数', 'INT', None, component='inputNumber'),
]

LEAVE_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'applicant', 'title': '请假人', 'props': {'placeholder': '请输入请假人姓名'}},
    {'type': 'input', 'field': 'department', 'title': '所属部门'},
    {'type': 'inputNumber', 'field': 'days', 'title': '请假天数', 'props': {'min': 1, 'max': 30, 'precision': 0},
     'info': '审批通过后逻辑流自动累加到年假台账'},
    {'type': 'datePicker', 'field': 'startDate', 'title': '开始日期', 'props': {'type': 'date', 'placeholder': '请选择开始日期'}},
    {'type': 'input', 'field': 'reason', 'title': '请假事由', 'props': {'type': 'textarea', 'rows': 3}},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': True, 'resetBtn': False}}

STOCK_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'productCode', 'title': '物料编码', 'props': {'placeholder': '如 P001'}},
    {'type': 'input', 'field': 'productName', 'title': '物料名称'},
    {'type': 'inputNumber', 'field': 'stockQty', 'title': '库存数量', 'props': {'min': 0, 'precision': 0}},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': True, 'resetBtn': False}}
STOCK_COLS = [
    col('productCode', '物料编码', 'VARCHAR', 64, unique=True, component='input'),
    col('productName', '物料名称', 'VARCHAR', 255, component='input'),
    col('stockQty', '库存数量', 'INT', None, component='inputNumber'),
]

PICKUP_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'productCode', 'title': '物料编码', 'props': {'placeholder': '如 P001'},
     'info': '提交后逻辑流自动扣减物料库存并写入领用流水'},
    {'type': 'input', 'field': 'productName', 'title': '物料名称'},
    {'type': 'inputNumber', 'field': 'qty', 'title': '领用数量', 'props': {'min': 1, 'precision': 0}},
    {'type': 'input', 'field': 'pickupBy', 'title': '领用人'},
    {'type': 'input', 'field': 'remark', 'title': '用途说明', 'props': {'type': 'textarea', 'rows': 2}},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': True, 'resetBtn': False}}
PICKUP_COLS = [
    col('productCode', '物料编码', 'VARCHAR', 64, component='input'),
    col('productName', '物料名称', 'VARCHAR', 255, component='input'),
    col('qty', '领用数量', 'INT', None, component='inputNumber'),
    col('pickupBy', '领用人', 'VARCHAR', 64, component='input'),
    col('remark', '用途说明', 'VARCHAR', 255, component='input'),
]

LOG_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'productCode', 'title': '物料编码'},
    {'type': 'input', 'field': 'productName', 'title': '物料名称'},
    {'type': 'inputNumber', 'field': 'qty', 'title': '数量', 'props': {'min': 0, 'precision': 0}},
    {'type': 'input', 'field': 'pickupBy', 'title': '领用人'},
    {'type': 'input', 'field': 'sourceNote', 'title': '来源单据'},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': False, 'resetBtn': False}}
LOG_COLS = [
    col('productCode', '物料编码', 'VARCHAR', 64, component='input'),
    col('productName', '物料名称', 'VARCHAR', 255, component='input'),
    col('qty', '数量', 'INT', None, component='inputNumber'),
    col('pickupBy', '领用人', 'VARCHAR', 64, component='input'),
    col('sourceNote', '来源单据', 'VARCHAR', 255, component='input'),
]

STOCK_SEED = [
    {'productCode': 'P001', 'productName': '深沟球轴承 6204', 'stockQty': 200},
    {'productCode': 'P002', 'productName': '直流减速电机 24V', 'stockQty': 50},
    {'productCode': 'P003', 'productName': '温度传感器 DS18B20', 'stockQty': 80},
]


def seed_stock(token):
    existing = biz_rows(token, 'material_stock_demo')
    have = {row['data'].get('productCode'): row for row in existing if isinstance(row.get('data'), dict)}
    for s in STOCK_SEED:
        if s['productCode'] in have:
            print(f"  库存已存在: {s['productCode']} ({have[s['productCode']]['data'].get('stockQty')})")
        else:
            biz_create(token, 'material_stock_demo', s)
            print(f"  库存 seeded: {s['productCode']} {s['stockQty']}")


# ---------------------------------------------------------------- 自测
def selftest_scenario2(token):
    """领用 P001×5 → 库存 200→195 + 流水 +1 行；随后清理测试痕迹恢复初始态。"""
    print('== 自测 场景 2：物料领用联动 ==')
    stock = {row['data'].get('productCode'): row for row in biz_rows(token, 'material_stock_demo')
             if isinstance(row.get('data'), dict)}
    before = stock['P001']['data'].get('stockQty')
    logs_before = len(biz_rows(token, 'pickup_log_demo'))
    print(f'  前置: P001={before}, 流水行数={logs_before}')
    pickup = biz_create(token, 'material_pickup_demo',
                        {'productCode': 'P001', 'productName': '深沟球轴承 6204', 'qty': 5,
                         'pickupBy': '李四', 'remark': '逻辑流自测-装配线领用'})
    time.sleep(1)
    stock2 = {row['data'].get('productCode'): row for row in biz_rows(token, 'material_stock_demo')
              if isinstance(row.get('data'), dict)}
    after = stock2['P001']['data'].get('stockQty')
    logs2 = biz_rows(token, 'pickup_log_demo')
    matched = [x for x in logs2 if isinstance(x.get('data'), dict)
               and x['data'].get('sourceNote') == f'来源领用单 {pickup["id"]}']
    assert after == before - 5, f'库存扣减失败: {before} -> {after}'
    assert len(logs2) == logs_before + 1 and matched, f'流水未生成: {len(logs2)} vs {logs_before}'
    print(f'  ✓ 库存 {before}→{after}，流水 +1 行（sourceNote 关联领用单 {pickup["id"][:8]}…）')
    # 清理测试痕迹：恢复库存(到测试前值)、删流水、删领用单（供用户现场演示干净初始态）
    biz_delete(token, 'pickup_log_demo', matched[0]['id'])
    biz_delete(token, 'material_pickup_demo', pickup['id'])
    stock_row = next(x for x in biz_rows(token, 'material_stock_demo')
                     if isinstance(x.get('data'), dict) and x['data'].get('productCode') == 'P001')
    rst = call('PUT', f'/v1/biz-data/material_stock_demo/{stock_row["id"]}',
               {'version': stock_row['version'], **stock_row['data'], 'stockQty': before}, token=token)
    assert rst.get('code') == 200, rst
    stock3 = {row['data'].get('productCode'): row for row in biz_rows(token, 'material_stock_demo')
              if isinstance(row.get('data'), dict)}
    restored = stock3['P001']['data'].get('stockQty')
    assert restored == before, f'库存恢复失败: 期望 {before} 实际 {restored}'
    print(f'  清理后: P001={restored}(已还原), 流水行数={len(biz_rows(token, "pickup_log_demo"))}')


def selftest_scenario1(token, proc_key, form_def_id):
    """发起 王五 2 天 → admin 审批 → 台账 王五 usedDays=之前+2（首验收建档，结果保留作演示态）。"""
    print('== 自测 场景 1：请假审批联动年假台账 ==')
    prev = next((x for x in biz_rows(token, 'leave_ledger_demo')
                 if isinstance(x.get('data'), dict) and x['data'].get('employeeName') == '王五'), None)
    prev_used = int(prev['data'].get('usedDays', 0)) if prev else 0
    expected = prev_used + 2
    inst = call('POST', '/v1/process-instances',
                {'processKey': proc_key, 'formDefId': form_def_id,
                 'variables': {'applicant': '王五', 'department': '研发部', 'days': 2,
                               'startDate': '2026-10-15', 'reason': '年假-逻辑流自测'}}, token=token)
    assert inst.get('code') == 200, inst
    instance_id = inst['data']['id']
    print(f'  流程已发起: {instance_id}')
    tasks = call('GET', '/v1/tasks?assignee=1&size=50', token=token)  # 平台任务办理人以 userId 为键（1=admin）
    rows = (tasks.get('data') or {}).get('records') or (tasks.get('data') or {}).get('content') or []
    task = next((t for t in rows if t.get('processInstanceId') == instance_id), None)
    assert task, f'未找到待办任务: {[t.get("processInstanceId") for t in rows][:5]}'
    done = call('POST', f'/v1/tasks/{task["taskId"]}/complete',
                {'comment': '同意，注意工作交接（逻辑流自测）', 'variables': {}}, token=token)
    assert done.get('code') == 200, done
    time.sleep(1)
    ledger = biz_rows(token, 'leave_ledger_demo')
    wu = next((x for x in ledger if isinstance(x.get('data'), dict)
               and x['data'].get('employeeName') == '王五'), None)
    assert wu, f'台账未建档: {[x.get("data", {}).get("employeeName") for x in ledger]}'
    used = int(wu['data'].get('usedDays', 0))
    print(f'  ✓ 台账联动: 王五 部门={wu["data"].get("department")} 总额={wu["data"].get("annualDays")} 已用={prev_used}→{used}')
    assert used == expected, f'累计异常: 期望 {expected} 实际 {used}'
    print(f'  ✓ 审批后已用天数 {prev_used}+2={used}（建档+累加语义正确，保留作演示态）')
    return instance_id


def main():
    token = login()
    print('== 场景 1：请假审批 → 年假台账 ==')
    ledger_id = ensure_form(token, '员工年假台账-逻辑流演示', 'leave_ledger_demo', 'BUSINESS',
                            LEDGER_SCHEMA, LEDGER_COLS)
    leave_id = ensure_form(token, '请假申请-逻辑流演示', 'leave_apply_demo', 'WORKFLOW', LEAVE_SCHEMA)
    ensure_logic_flow(token, 'leave_ledger_sync', '请假批准同步年假台账',
                      '演示：审批通过(流程结束)后自动建档并累加年假台账（DATA_UPSERT + DATA_UPDATE）', LEDGER_DSL)
    ensure_binding(token, 'WORKFLOW', 'leave_apply_demo', 'AFTER_PROCESS_FINISH', 'leave_ledger_sync',
                   'SYNC_IN_TX', '流程结束后自动同步年假台账')
    ensure_process(token, 'leave_approval_demo', '请假审批-逻辑流演示', LEAVE_BPMN, leave_id)

    print('== 场景 2：物料领用 → 库存扣减 + 流水登记 ==')
    stock_id = ensure_form(token, '物料库存-逻辑流演示', 'material_stock_demo', 'BUSINESS',
                           STOCK_SCHEMA, STOCK_COLS)
    log_id = ensure_form(token, '领用流水-逻辑流演示', 'pickup_log_demo', 'BUSINESS', LOG_SCHEMA, LOG_COLS)
    pickup_id = ensure_form(token, '物料领用单-逻辑流演示', 'material_pickup_demo', 'BUSINESS',
                            PICKUP_SCHEMA, PICKUP_COLS)
    ensure_logic_flow(token, 'pickup_stock_sync', '领用登记联动库存与流水',
                      '演示：登记领用单(新增)后自动扣减物料库存(DATA_UPDATE SUB)并写入领用流水(DATA_INSERT)', PICKUP_DSL)
    ensure_binding(token, 'BUSINESS', 'material_pickup_demo', 'AFTER_CREATE', 'pickup_stock_sync',
                   'SYNC_IN_TX', '领用登记后自动扣库存+写流水')
    seed_stock(token)

    print('== API 级自测 ==')
    selftest_scenario2(token)
    selftest_scenario1(token, 'leave_approval_demo', leave_id)

    print('DONE')


if __name__ == '__main__':
    main()
