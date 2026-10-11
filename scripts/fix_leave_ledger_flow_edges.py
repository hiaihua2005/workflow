#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fix_leave_ledger_flow_edges.py — 修复 leave_ledger_sync 逻辑流 v9 的分支反接 bug

Bug（v9 实况）：
  CONDITION cond_empty「台账不存在?」(variable=total EQ 0)
    - branch=true  → du_add   （错：不存在时应建档）
    - branch=false → di_create（错：已存在时不应 INSERT → 唯一键冲突 500）
  另有一条画布残留无 branch 出边（id=vueflow__edge-di_createout-du_add，source 误为 cond_empty）。

修复后语义：
  - true  （total=0，未建档）→ di_create 首次建档(10天/已用0) → du_add 累加
  - false （total≥1，已建档）→ du_add 直接累加
  - di_create → du_add 恢复连线

用法：python3 scripts/fix_leave_ledger_flow_edges.py   （对 8080 线上库执行，幂等）
"""
import json
import sys
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8080/api'
FLOW_KEY = 'leave_ledger_sync'


def call(method, path, body=None, token=None, ok_codes=(200,)):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('X-Tenant-Id', 'default')
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            text = resp.read().decode()
    except urllib.error.HTTPError as e:
        text = e.read().decode()
    r = json.loads(text)
    if r.get('code') not in ok_codes:
        print(f'API FAIL {method} {path} -> {json.dumps(r, ensure_ascii=False)[:300]}')
        sys.exit(1)
    return r


def main():
    token = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})['data']['accessToken']
    lst = call('GET', f'/v1/logic-flows?size=50&key={FLOW_KEY}', token=token)
    rows = (lst.get('data') or {})
    rows = rows.get('records') or rows.get('content') or (rows if isinstance(rows, list) else [])
    flow = next((x for x in rows if x.get('flowKey') == FLOW_KEY), None)
    assert flow, f'逻辑流未找到: {FLOW_KEY}'
    fid = flow['id']
    dsl = json.loads(flow['dslJson']) if flow.get('dslJson') else None
    if not dsl:
        # 列表接口可能不返回 dsl，改取详情（字段名 dsl）
        dsl = json.loads(call('GET', f'/v1/logic-flows/{fid}', token=token)['data']['dsl'])

    edges = dsl.get('edges', [])
    by_id = {}
    for e in edges:
        by_id[e['id']] = e

    e_true = by_id.get('vueflow__edge-cond_emptytrue-du_add')
    e_false = by_id.get('vueflow__edge-cond_emptyfalse-di_createin')
    e_stray = by_id.get('vueflow__edge-di_createout-du_add')
    # 半应用状态识别（首次运行曾误删 e2 后保存）：新边名已存在
    e_true_new = by_id.get('e_true_create')
    e_false_new = by_id.get('e_false_add')
    e_create_add = by_id.get('e_create_add')
    if e_true_new and e_false_new and e_create_add and e_create_add.get('source') == 'di_create':
        if not any(e.get('source') == 'dq_ledger' and e.get('target') == 'cond_empty' for e in edges):
            edges.append({'id': 'e2', 'source': 'dq_ledger', 'target': 'cond_empty'})
            print('repairing half-applied state: re-adding e2 (dq_ledger→cond_empty)')
        else:
            print('already fixed, nothing to do')
            return
    else:
        assert e_true and e_false, f'未找到预期分支边: {list(by_id)}'
        # ① true 分支 → di_create（建档）
        e_true['target'] = 'di_create'
        e_true['id'] = 'e_true_create'
        # ② false 分支 → du_add（直接累加）
        e_false['target'] = 'du_add'
        e_false['id'] = 'e_false_add'
        # ③ 建档后接累加（恢复 di_create→du_add 连线；source 原误为 cond_empty）
        if e_stray:
            e_stray['source'] = 'di_create'
            e_stray['target'] = 'du_add'
            e_stray['id'] = 'e_create_add'
            e_stray.pop('branch', None)
        else:
            edges.append({'id': 'e_create_add', 'source': 'di_create', 'target': 'du_add'})
        # 注：e2 (dq_ledger→cond_empty) 是正常主干边，保留

    name = flow.get('name') or '请假批准同步年假台账'
    desc = flow.get('description') or ''
    r = call('PUT', f'/v1/logic-flows/{fid}', {'name': name, 'description': desc,
                                               'dsl': json.dumps(dsl, ensure_ascii=False)}, token=token)
    pub = call('POST', f'/v1/logic-flows/{fid}/publish', token=token)
    print(f'fixed & published: {FLOW_KEY} v{pub["data"].get("version")}')
    print('edges now:', json.dumps(
        [{k: e.get(k) for k in ('id', 'source', 'target', 'branch')} for e in dsl['edges']],
        ensure_ascii=False))


if __name__ == '__main__':
    main()
