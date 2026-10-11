#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构造「扩展表单组件」演示资产（用户指令：演示 地址/字典下拉/关联记录卡片/定位/自动编号/分步表单/抽屉容器）
背景：环境重供给后业务表单与演示数据已丢失（仅剩 9 个内置数据源），从零重建。

阶段 A 前置资产：
  A1 service_type 字典 + 4 项（安装调试/定期巡检/故障维修/培训支持）
  A2 客户源表单 cust_demo（发布）+ 3 条客户数据 → DataSourceSyncListener 自动生成 FORM 数据源
阶段 B 主演示表单：
  B1 onsite_service_demo：StepsForm 三步（AutoNumber/input/dictSelect/areaPicker + LocationPicker/input-textarea
      + dataPicker/RelationCard）+ DrawerContainer（input/checkbox）
  B2 columnConfig（对齐 ColumnConfigDialog 草案格式；RelationCard 不生成列=新规）+ publish
幂等：按 formKey 查存在则复用 id；字典按 dictCode 幂等。
"""
import json, sys, time, urllib.request

BASE = 'http://127.0.0.1:8080/api'
TENANT = {'X-Tenant-Id': 'default', 'Content-Type': 'application/json'}

def call(method, path, body=None, token=None, expect_json=True):
    req = urllib.request.Request(BASE + path, method=method)
    for k, v in TENANT.items():
        req.add_header(k, v)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            text = resp.read().decode()
    except urllib.error.HTTPError as e:
        text = e.read().decode()
    try:
        return json.loads(text)
    except Exception:
        print('NON-JSON', method, path, text[:200]); sys.exit(1)

def login():
    r = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})
    assert r.get('data', {}).get('accessToken'), r
    return r['data']['accessToken']

def ensure_dict(token):
    # 字典类型幂等
    r = call('GET', '/dict-types?page=1&size=50', token=token)
    rows = (r.get('data') or {})
    rows = rows.get('rows') or rows.get('list') or (rows if isinstance(rows, list) else [])
    if not any(x.get('dictCode') == 'service_type' for x in rows):
        r2 = call('POST', '/dict-types', {'dictName': '服务类型', 'dictCode': 'service_type', 'status': 1}, token=token)
        print('字典类型 created:', r2.get('code'))
    items = call('GET', '/dict-data/service_type', token=token)
    existing = {x.get('value') for x in (items.get('data') or [])}
    for i, (label, value) in enumerate([('安装调试', 'install'), ('定期巡检', 'patrol'), ('故障维修', 'repair'), ('培训支持', 'training')]):
        if value not in existing:
            r3 = call('POST', '/dict-data', {'dictCode': 'service_type', 'label': label, 'value': value, 'sortOrder': i, 'status': 1}, token=token)
            print('字典项', value, r3.get('code'))
    print('字典 service_type 就绪')

def ensure_form(token, name, key, schema, column_config=None):
    lst = call('GET', f'/v1/form-definitions?size=200&key={key}', token=token)
    found = None
    rows = (lst.get('data') or {})
    rows = rows.get('list') or rows.get('records') or rows.get('content') or (rows if isinstance(rows, list) else [])
    for x in rows:
        if x.get('key') == key:
            found = x
            break
    if not found:
        r = call('POST', f'/v1/form-definitions?name={urllib.request.quote(name)}&key={key}&type=BUSINESS', token=token)
        assert r.get('code') == 200, r
        fid = r['data']['id']
        print('表单 created:', key, fid)
    else:
        fid = found['id']
        print('表单已存在:', key, fid)
    body = {'name': name, 'key': key, 'schema': json.dumps(schema, ensure_ascii=False), 'columnConfig': None}
    if column_config is not None:
        body['columnConfig'] = json.dumps(column_config, ensure_ascii=False)
    r = call('PUT', f'/v1/form-definitions/{fid}', body, token=token)
    assert r.get('code') == 200, r
    pub = call('POST', f'/v1/form-definitions/{fid}/publish', token=token)
    print('publish:', key, pub.get('code'), (pub.get('data') or {}).get('status'))
    return fid

CUST_SCHEMA = {'rule': [
    {'type': 'input', 'field': 'customerName', 'title': '客户名称', 'props': {'placeholder': '请输入客户名称'}},
    {'type': 'select', 'field': 'customerStatus', 'title': '客户状态',
     'options': [{'label': '运行中', 'value': '运行中'}, {'label': '筹建中', 'value': '筹建中'}, {'label': '已停用', 'value': '已停用'}]},
    {'type': 'input', 'field': 'owner', 'title': '负责人'},
    {'type': 'input', 'field': 'phone', 'title': '联系电话'},
], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '100px'}, 'submitBtn': True, 'resetBtn': False}}

def col(key, label, ctype, length=None, component=None, hidden=False, picker=None):
    d = {'key': key, 'label': label, 'columnType': ctype, 'length': length, 'scale': None,
         'required': False, 'unique': False, 'indexed': False}
    if component: d['componentType'] = component
    if hidden: d['hidden'] = True
    if picker: d['pickerConfig'] = json.dumps(picker, ensure_ascii=False)
    return d

CUST_ROWS = [
    {'customerName': '苏州精密机械有限公司', 'customerStatus': '运行中', 'owner': '张伟', 'phone': '0512-66881234'},
    {'customerName': '杭州智联科技', 'customerStatus': '筹建中', 'owner': '李静', 'phone': '0571-88992211'},
    {'customerName': '南京重工装备', 'customerStatus': '已停用', 'owner': '王强', 'phone': '025-87654321'},
]

CUST_COLS = [
    col('customerName', '客户名称', 'VARCHAR', 255, 'input'),
    col('customerStatus', '客户状态', 'VARCHAR', 255, 'select'),
    col('owner', '负责人', 'VARCHAR', 255, 'input'),
    col('phone', '联系电话', 'VARCHAR', 255, 'input'),
]


def main_schema(ds_id, src_key='cust_rel_demo', src_display='Fyczmv3ks22dabc', disp_fields=None):
    disp_fields = disp_fields or ['Fyczmv3ks22dabc', 'Ffxemv3ksch9aec', 'Fzgimv3kskiaahc', 'Fqgomv3ksnqrakc']
    return {'rule': [
        {'type': 'StepsForm',
         'props': {'steps': [
             {'title': '基本信息', 'description': '工单编号与服务内容'},
             {'title': '现场信息', 'description': '定位与现场纪要'},
             {'title': '关联客户', 'description': '选择客户查看实时档案'}],
             'active': 0, 'direction': 'horizontal'},
         'style': {'width': '100%'},
         'children': [
             {'type': 'elCard', 'props': {'header': '第一步：基本信息'}, 'children': [
                 {'type': 'AutoNumber', 'field': 'orderNo', 'title': '工单编号',
                  'props': {'prefix': 'SVD', 'dateFormat': 'yyyyMMdd', 'resetPolicy': 'day', 'seqDigits': 4},
                  'info': '提交后由后端自动生成'},
                 {'type': 'input', 'field': 'topic', 'title': '服务主题', 'props': {'placeholder': '如：2号产线传感器更换'}},
                 {'type': 'dictSelect', 'field': 'serviceType', 'title': '服务类型',
                  'props': {'dictTypeCode': 'service_type', 'placeholder': '请选择服务类型', 'clearable': True}},
                 {'type': 'areaPicker', 'field': 'serviceArea', 'title': '服务地址',
                  'props': {'placeholder': '请选择省/市/区', 'clearable': True, 'showAllLevels': True}},
             ]},
             {'type': 'elCard', 'props': {'header': '第二步：现场信息'}, 'children': [
                 {'type': 'LocationPicker', 'field': 'siteLocation', 'title': '现场定位',
                  'props': {'placeholder': '点击选择位置', 'clearable': True}},
                 {'type': 'input', 'field': 'siteNote', 'title': '现场纪要',
                  'props': {'type': 'textarea', 'rows': 3, 'placeholder': '现场情况记录（多行）'}},
             ]},
             {'type': 'elCard', 'props': {'header': '第三步：关联客户'}, 'children': [
                 {'type': 'dataPicker', 'field': 'customerId', 'title': '关联客户',
                  'props': {'sourceFormKey': src_key, 'displayField': src_display, 'maxCount': 1}},
                 {'type': 'RelationCard', 'field': 'relCard', 'title': '客户实时档案',
                  'props': {'title': '客户实时档案', 'globalDataSourceId': ds_id, 'recordIdField': 'customerId',
                            'displayFields': disp_fields, 'clickable': True,
                            'description': '数据实时来自客户数据源，源数据变更后自动展示最新值'}},
             ]},
         ]},
        {'type': 'DrawerContainer',
         'props': {'buttonText': '扩展信息', 'buttonType': 'primary', 'plain': True, 'title': '扩展信息', 'size': '40%'},
         'style': {'width': '100%'},
         'children': [
             {'type': 'input', 'field': 'internalNote', 'title': '内部备注', 'props': {'placeholder': '仅内部可见（存于抽屉容器）'}},
             {'type': 'checkbox', 'field': 'revisit', 'title': '回访计划',
              'options': [{'label': '已确认回访计划', 'value': 'confirmed'}, {'label': '需要技术支持跟进', 'value': 'tech'}]},
         ]},
    ], 'option': {'form': {'labelPosition': 'right', 'labelWidth': '110px'}, 'submitBtn': True, 'resetBtn': False}}

def main_cols(src_key='cust_rel_demo'):
    return [
    col('orderNo', '工单编号', 'VARCHAR', 64, 'AutoNumber'),
    col('topic', '服务主题', 'VARCHAR', 255, 'input'),
    col('serviceType', '服务类型', 'VARCHAR', 255, 'dictSelect'),
    col('serviceArea', '服务地址', 'VARCHAR', 255, 'areaPicker'),
    col('siteLocation', '现场定位', 'VARCHAR', 255, 'LocationPicker'),
    col('siteNote', '现场纪要', 'VARCHAR', 255, 'input'),
    col('customerId', '关联客户', 'TEXT', None, 'dataPicker',
        picker={'sourceFormKey': src_key, 'dataSourceId': None, 'displayField': 'Fyczmv3ks22dabc', 'maxCount': 1, 'pickerType': 'dataPicker'}),
    col('customerId_text', '关联客户（显示）', 'TEXT', None, 'dataPickerText', hidden=True),
    col('internalNote', '内部备注', 'VARCHAR', 255, 'input'),
    col('revisit', '回访计划', 'JSON', None, 'checkbox'),
    col('revisit_text', '回访计划（显示）', 'VARCHAR', 255, 'checkboxText', hidden=True),
]

def main():
    token = login()
    print('== A1 字典 ==')
    ensure_dict(token)
    print('== A2 复用已发布源表单 cust_rel_demo（旧演示资产，default 租户）==')
    ds_id = None
    for _ in range(10):
        lst = call('GET', '/v1/data-sources?size=100', token=token)
        for x in ((lst.get('data') or {}).get('content') or []):
            if x.get('type') == 'FORM' and x.get('sourceKey') == 'cust_rel_demo':
                ds_id = x['id']; break
        if ds_id: break
        time.sleep(2)
    assert ds_id, 'cust_rel_demo FORM 数据源未找到'
    print('cust_rel_demo FORM 数据源:', ds_id)
    print('== B 主表单（default 租户）==')
    ensure_form(token, '现场服务工单-组件演示', 'onsite_service_demo', main_schema(ds_id), main_cols())
    print('DONE')

if __name__ == '__main__':
    main()
