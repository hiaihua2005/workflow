#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
export_demo_sql.py — 将「演示例子」资产从 workflow 库导出为幂等 SQL 脚本包

产物目录：sql/demo-examples/
  01-dict.sql                    字典：service_type / customer_level
  02-demo-cust-rel.sql           客户档案源表单 + 3 条客户数据 + FORM 数据源
  03-demo-onsite-service.sql     现场服务工单（7 扩展组件演示）+ 3 条工单数据 + 数据源 + 流水号进度
  04-demo-visit.sql              客户拜访工单演示表单（空表，供自行录入）
  05-demo-ai-device-repair.sql   AI 助手（小智）生成的设备报修登记表单（DRAFT）
  06-demo-page-detail-steps.sql  页面演示：数据详情 + 步骤条（含菜单挂载与角色授权）

设计要点：
  - 有自然唯一键的表：INSERT ... ON DUPLICATE KEY UPDATE（可重复执行）
  - 无自然唯一键的表（sys_dict_data/sys_menu/sys_role_menu）：事务内 DELETE+INSERT 实现幂等
  - auto_increment 表（字典/菜单/角色菜单）主键重映射到 9xxx 段，避免与全新环境的 flyway 种子数据冲突
  - 跨表引用（数据源 form_id、角色菜单 menu_id）按重映射自动改写
  - 导出使用 mariadb --xml 通道，转义/NULL 处理由数据库保证，脚本只做拼装
  - wf_data_source.onsite_service_demo 的 form_id 修正为 default 租户表单（线上行误指 tenant=1 孤儿行）

用法：python3 scripts/export_demo_sql.py   （幂等，覆盖输出目录）
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

MDB = '/home/z/my-project/mariadb-user/root/bin/mariadb'
SOCK = '/home/z/my-project/mariadb-user/mysql.sock'
OUT_DIR = '/home/z/my-project/sql/demo-examples'
DB = 'workflow'
ENV = {
    **os.environ,
    'LD_LIBRARY_PATH': ('/home/z/my-project/mariadb-user/sysroot/usr/lib/x86_64-linux-gnu'
                        ':/home/z/my-project/mariadb-user/root/usr/lib/x86_64-linux-gnu'),
}


def query_xml(sql: str) -> list[dict]:
    """mariadb --xml 批处理查询，返回 [{col: value|None}]（值全部为 str 或 None）"""
    proc = subprocess.run(
        [MDB, '-u', 'root', '-p740130', '-S', SOCK, DB, '--xml', '--batch', '-e', sql],
        capture_output=True, text=True, env=ENV,
    )
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        raise RuntimeError(f'query failed: {sql[:120]}')
    root = ET.fromstring(proc.stdout)
    rows = []
    for row in root.iter('row'):
        d = {}
        for f in row.iter('field'):
            d[f.get('name')] = None if f.get('{http://www.w3.org/2001/XMLSchema-instance}nil') == 'true' else (f.text or '')
        rows.append(d)
    return rows


def esc(v) -> str:
    """MySQL 字符串字面量转义"""
    if v is None:
        return 'NULL'
    s = str(v)
    s = s.replace('\\', '\\\\').replace("'", "\\'")
    s = s.replace('\n', '\\n').replace('\r', '\\r').replace('\x1a', '\\Z')
    return f"'{s}'"


def build_insert(table: str, rows: list[dict], pk: str, skip_pk_in_update: bool = True,
                 id_remap: dict | None = None, col_rewrite: dict | None = None,
                 omit_cols: list[str] | None = None) -> list[str]:
    """生成幂等 INSERT ... ON DUPLICATE KEY UPDATE 语句列表"""
    stmts = []
    for r in rows:
        row = dict(r)
        if omit_cols:
            for c in omit_cols:
                row.pop(c, None)
        if id_remap and pk in row and row[pk] is not None:
            row[pk] = str(id_remap.get(str(row[pk]), row[pk]))
        if col_rewrite:
            for col, mapping in col_rewrite.items():
                if col in row and row[col] is not None:
                    row[col] = str(mapping.get(str(row[col]), row[col]))
        cols = list(row.keys())
        collist = ', '.join(f'`{c}`' for c in cols)
        vallist = ', '.join(esc(row[c]) for c in cols)
        upd = ', '.join(f'`{c}`=VALUES(`{c}`)' for c in cols if not (skip_pk_in_update and c == pk))
        stmts.append(f"INSERT INTO `{table}` ({collist}) VALUES ({vallist}) ON DUPLICATE KEY UPDATE {upd};")
    return stmts


def head(title: str, note: str = '') -> str:
    lines = [
        '-- ' + '=' * 66,
        f'-- {title}',
        '-- 平台演示资产 · 由 scripts/export_demo_sql.py 自动导出（勿手改）',
        '-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE，可重复执行',
        '-- 用法：mariadb -u root -p workflow < 本文件',
    ]
    if note:
        lines.append(f'-- 说明：{note}')
    lines.append('-- ' + '=' * 66)
    return '\n'.join(lines) + '\n'


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # ---------- 01 字典 ----------
    dt = query_xml("SELECT * FROM sys_dict_type WHERE dict_code IN ('service_type','customer_level')")
    dd = query_xml("SELECT * FROM sys_dict_data WHERE dict_code IN ('service_type','customer_level') AND is_deleted=0")
    dt_map = {r['id']: str(9000 + int(r['id'])) for r in dt}
    # sys_dict_data 无 (dict_code,value) 唯一键 → 事务内 DELETE+INSERT 幂等
    stmts = ['START TRANSACTION;',
             "DELETE FROM sys_dict_data WHERE dict_code IN ('service_type','customer_level');",
             "DELETE FROM sys_dict_type WHERE dict_code IN ('service_type','customer_level');"]
    stmts += build_insert('sys_dict_type', dt, pk='id', id_remap=dt_map)
    stmts += build_insert('sys_dict_data', dd, pk='id',
                          id_remap={r['id']: str(9000 + int(r['id'])) for r in dd})
    stmts.append('COMMIT;')
    write('01-dict.sql', head('字典数据：service_type 服务类型 / customer_level 客户等级',
                             '字典项按 dict_code 引用（组件不依赖主键 id）；导入将先清除同 dict_code 旧项再写入') + '\n'.join(stmts) + '\n')

    # ---------- 02 客户档案源表单 ----------
    cust = query_xml("SELECT * FROM wf_form_def WHERE tenant_id='default' AND `key`='cust_rel_demo'")
    cust_rows = query_xml("SELECT * FROM wf_biz_cust_rel_demo WHERE tenant_id='default'")
    ds_cust = query_xml("SELECT * FROM wf_data_source WHERE tenant_id='default' AND source_key='cust_rel_demo'")
    stmts = build_insert('wf_form_def', cust, pk='id')
    stmts += build_insert('wf_data_source', ds_cust, pk='id')
    stmts += build_insert('wf_biz_cust_rel_demo', cust_rows, pk='id')
    write('02-demo-cust-rel.sql', head('演示例子 1：客户档案表单（关联演示数据源）+ 3 条客户数据',
                                       '作为「关联记录卡片/关联选择器」的数据源被 03 号工单表单引用') + '\n'.join(stmts) + '\n')

    # ---------- 03 现场服务工单（7 组件演示） ----------
    onsite = query_xml("SELECT * FROM wf_form_def WHERE tenant_id='default' AND `key`='onsite_service_demo'")
    onsite_id = onsite[0]['id'] if onsite else 'e55a6bd0b9f043e1b79597caf2a4ef55'
    ds_onsite = query_xml("SELECT * FROM wf_data_source WHERE tenant_id='default' AND source_key='onsite_service_demo'")
    # 修正：线上数据源行 form_id 误指 tenant=1 孤儿表单 → 改指 default 租户表单
    ds_onsite = [{**r, 'form_id': onsite_id} for r in ds_onsite]
    onsite_rows = query_xml("SELECT * FROM wf_biz_onsite_service_demo WHERE tenant_id='default'")
    serial = query_xml("SELECT tenant_id, serial_key, period, seq, updated_at FROM wf_serial_number WHERE serial_key='onsite_service_demo.orderNo'")
    stmts = build_insert('wf_form_def', onsite, pk='id')
    stmts += build_insert('wf_data_source', ds_onsite, pk='id')
    stmts += build_insert('wf_serial_number', serial, pk='id', skip_pk_in_update=True)
    stmts += build_insert('wf_biz_onsite_service_demo', onsite_rows, pk='id')
    write('03-demo-onsite-service.sql', head('演示例子 2：现场服务工单（7 扩展组件演示）',
                                             '覆盖 自动编号/字典下拉/地址联动/地图定位/关联选择/关联卡片/分步表单/抽屉容器；'
                                             '含 3 条工单数据与流水号进度（SVD 序号将从 0004 续排）') + '\n'.join(stmts) + '\n')

    # ---------- 04 客户拜访工单 ----------
    visit = query_xml("SELECT * FROM wf_form_def WHERE tenant_id='default' AND `key`='visit_demo'")
    stmts = build_insert('wf_form_def', visit, pk='id')
    write('04-demo-visit.sql', head('演示例子 3：客户拜访工单表单（组件演示，数据为空可自行录入）') + '\n'.join(stmts) + '\n')

    # ---------- 05 AI 助手生成表单 ----------
    ai = query_xml("SELECT * FROM wf_form_def WHERE tenant_id='default' AND `key`='ai_mv3ybmpz77'")
    stmts = build_insert('wf_form_def', ai, pk='id')
    write('05-demo-ai-device-repair.sql', head('演示例子 4：设备报修登记表单（小智 AI 助手生成，DRAFT）',
                                               '由 AI 对话 create_form 工具真实落库的草稿，可在 /form/designer 查看') + '\n'.join(stmts) + '\n')

    # ---------- 06 页面演示：数据详情 + 步骤条 ----------
    page = query_xml("SELECT * FROM wf_page_def WHERE tenant_id='default' AND `key`='page_detail_steps_demo'")
    menu = query_xml("SELECT * FROM sys_menu WHERE id=319 AND is_deleted=0")
    role_menu = query_xml("SELECT * FROM sys_role_menu WHERE menu_id=319")
    menu_map = {'319': '9319'}
    # sys_menu/sys_role_menu 无自然唯一键 → 事务内 DELETE+INSERT 幂等
    stmts = ['START TRANSACTION;',
             "DELETE FROM sys_role_menu WHERE menu_id=9319;",
             "DELETE FROM sys_menu WHERE path='/page/page_detail_steps_demo';"]
    stmts += build_insert('wf_page_def', page, pk='id')
    stmts += build_insert('sys_menu', menu, pk='id', id_remap=menu_map)
    stmts += build_insert('sys_role_menu', role_menu, pk='id',
                          id_remap={r['id']: str(9000 + int(r['id'])) for r in role_menu},
                          col_rewrite={'menu_id': menu_map})
    stmts.append('COMMIT;')
    write('06-demo-page-detail-steps.sql', head('演示例子 5：页面「数据详情 + 步骤条」联动演示',
                                                'page-steps 步骤条 + page-table 表格 + page-detail 行点击联动详情；'
                                                '菜单重映射为 id=9319（顶级入口，已授权 admin 角色 role_id=1，导入时按 path 去重）') + '\n'.join(stmts) + '\n')

    counts = {
        '01-dict.sql': f'{len(dt)} 字典类型 + {len(dd)} 字典项',
        '02-demo-cust-rel.sql': f'1 表单 + 1 数据源 + {len(cust_rows)} 条客户',
        '03-demo-onsite-service.sql': f'1 表单 + 1 数据源 + 1 流水号 + {len(onsite_rows)} 条工单',
        '04-demo-visit.sql': '1 表单',
        '05-demo-ai-device-repair.sql': '1 表单(DRAFT)',
        '06-demo-page-detail-steps.sql': f'1 页面 + {len(menu)} 菜单 + {len(role_menu)} 角色授权',
    }
    for f, c in counts.items():
        print(f'  {f}: {c}')
    print(f'OK -> {OUT_DIR}')


def write(name: str, content: str):
    path = os.path.join(OUT_DIR, name)
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write(content)
    print(f'  written {name} ({len(content)} bytes)')


if __name__ == '__main__':
    main()
