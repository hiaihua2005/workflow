#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""e2e_sql_restore.py — 验证 07/08 SQL 脚本在「纯 SQL 还原 + 冷缓存」环境下的完整触发链路

目标库：workflow_sqltest（schema-only 初始化 + 8 个演示脚本导入 + Flowable 属性表对齐）
后端：8081 独立实例（全新 JVM = Flowable 流程定义缓存为空，定义只能来自 SQL 还原的 act_re_* 行）
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8081/api'
TENANT = {'X-Tenant-Id': 'default', 'Content-Type': 'application/json'}

FAILS = []


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
        FAILS.append(f'NON-JSON {method} {path}: {text[:200]}')
        return {}
    if r.get('code') not in ok_codes:
        FAILS.append(f'API FAIL {method} {path} -> {json.dumps(r, ensure_ascii=False)[:200]}')
    return r


def login():
    r = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})
    tok = (r.get('data') or {}).get('accessToken')
    if not tok:
        print('LOGIN FAILED:', json.dumps(r, ensure_ascii=False)[:300])
        sys.exit(1)
    return tok


def biz_rows(token, form_key, size=200):
    r = call('GET', f'/v1/biz-data/{form_key}?page=1&size={size}', token=token)
    return (r.get('data') or {}).get('records') or []


def ok(cond, label, detail=''):
    mark = '✓' if cond else '✗'
    print(f'  {mark} {label}' + (f'（{detail}）' if detail else ''))
    if not cond:
        FAILS.append(label)


def main():
    token = login()
    print('== 前置：SQL 还原的流程部署可被引擎解析 ==')
    pds = call('GET', '/v1/deployed-processes?page=1&size=50', token=token)
    defs = ((pds.get('data') or {}).get('records')
            or (pds.get('data') or {}).get('content')
            or (pds.get('data') if isinstance(pds.get('data'), list) else [])) or []
    leave_def = next((d for d in defs if d.get('key') == 'leave_approval_demo'), None)
    ok(leave_def is not None, '流程定义 leave_approval_demo 可见（act_re_procdef SQL 还原生效）',
       json.dumps({k: leave_def.get(k) for k in ('id', 'key', 'version')}, ensure_ascii=False) if leave_def else f'defs={len(defs)}')

    print('== 场景 1：请假审批 → 年假台账（AFTER_PROCESS_FINISH 逻辑流） ==')
    ledger_before = biz_rows(token, 'leave_ledger_demo')
    wu0 = next((x for x in ledger_before if x.get('data', {}).get('employeeName') == '王五'), None)
    prev_used = int(wu0['data'].get('usedDays', 0)) if wu0 else 0
    ok(wu0 is not None, '种子台账可见', f'王五 已用={prev_used}')
    expected = prev_used + 2
    inst = call('POST', '/v1/process-instances',
                {'processKey': 'leave_approval_demo', 'formDefId': '757332f798b545efa453fa37669a9193',
                 'variables': {'applicant': '王五', 'department': '研发部', 'days': 2,
                               'startDate': '2026-10-15', 'reason': 'SQL还原E2E-年假'}}, token=token)
    instance_id = (inst.get('data') or {}).get('id')
    ok(inst.get('code') == 200 and instance_id, '流程发起成功（SQL 还原部署可直接 start）', f'instance={instance_id}')
    if instance_id:
        time.sleep(1)
        tasks = call('GET', '/v1/tasks?assignee=1&size=50', token=token)
        rows = ((tasks.get('data') or {}).get('content')
                or (tasks.get('data') or {}).get('records') or [])
        task = next((t for t in rows if t.get('processInstanceId') == instance_id), None)
        ok(task is not None, '主管待办出现（assignee=userId 1）', f'taskId={task.get("taskId") if task else None}')
        if task:
            done = call('POST', f'/v1/tasks/{task["taskId"]}/complete',
                        {'comment': '同意（SQL还原E2E）', 'variables': {}}, token=token)
            ok(done.get('code') == 200, '审批通过（任务完成）')
            time.sleep(1.5)
            wu1 = next((x for x in biz_rows(token, 'leave_ledger_demo')
                        if x.get('data', {}).get('employeeName') == '王五'), None)
            used = int((wu1 or {}).get('data', {}).get('usedDays', -1))
            ok(used == expected, '逻辑流联动：台账 usedDays 跨次累加', f'{prev_used}+2→{used}')

    print('== 场景 2：物料领用 → 库存扣减 + 流水写入（AFTER_CREATE 逻辑流） ==')
    stock_before = {x['data']['productCode']: int(x['data']['stockQty'])
                    for x in biz_rows(token, 'material_stock_demo') if x.get('data', {}).get('productCode')}
    log_before = len(biz_rows(token, 'pickup_log_demo'))
    ok(stock_before.get('P002') == 50, '种子库存可见', f'P002={stock_before.get("P002")}')
    ok(log_before == 1, '种子流水可见', f'{log_before} 行')
    created = call('POST', '/v1/biz-data/material_pickup_demo',
                   {'productCode': 'P002', 'productName': '直流减速电机 24V', 'qty': 3,
                    'pickupBy': '测试员', 'remark': 'SQL还原E2E-领用'}, token=token)
    ok(created.get('code') == 200, '新增领用单成功', f'id={(created.get("data") or {}).get("id")}')
    time.sleep(1.5)
    stock_after = {x['data']['productCode']: int(x['data']['stockQty'])
                   for x in biz_rows(token, 'material_stock_demo') if x.get('data', {}).get('productCode')}
    log_after = biz_rows(token, 'pickup_log_demo')
    ok(stock_after.get('P002') == stock_before.get('P002', 0) - 3, '逻辑流联动：库存自动扣减',
       f'P002 {stock_before.get("P002")}→{stock_after.get("P002")}')
    ok(len(log_after) == log_before + 1, '逻辑流联动：流水自动写入', f'{log_before}→{len(log_after)} 行')
    new_log = max(log_after, key=lambda x: str(x.get('createdAt') or '')) if log_after else {}
    note = (new_log.get('data') or {}).get('sourceNote', '')
    ok('来源领用单' in note, '流水含来源回填', f'sourceNote={note[:50]}')

    print()
    if FAILS:
        print(f'E2E FAILED — {len(FAILS)} 项:')
        for f in FAILS:
            print(' -', f)
        sys.exit(1)
    print('E2E ALL GREEN — SQL 还原环境两场景触发链路完整可用')


if __name__ == '__main__':
    main()
