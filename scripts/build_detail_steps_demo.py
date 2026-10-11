#!/usr/bin/env python3
"""构造「页面设计器组件演示」页面（幂等）：数据详情（page-detail）+ 步骤条（page-steps）。

页面结构：
  1. page-steps  顶部流程步骤条（纯 props 展示：items/active/direction）
  2. page-table  客户档案列表（绑 cust_rel_demo FORM 数据源，行点击联动）
  3. page-detail 客户档案详情 KV（同源绑定；点击列表行后 el-descriptions 渲染选中记录）

用法：python3 scripts/build_detail_steps_demo.py
幂等：按 key 查找既有页面 → 有则 PUT 更新 + 重发布；无则 POST 创建 + 发布。
"""
import json
import urllib.request

BASE = "http://127.0.0.1:8080/api"
TENANT = "default"
PAGE_KEY = "page_detail_steps_demo"
PAGE_NAME = "组件演示·数据详情与步骤条"
CUST_DS_ID = "a4187314fe3342c1ac5d6d52cc099a18"  # 客户档案-关联演示（cust_rel_demo，3 条数据）

# cust_rel_demo 物理列（by-key 接口 columnConfig 查得；行数据在 page-table 内已拍平）
F_NAME = "Fyczmv3ks22dabc"        # 客户名称
F_STATUS = "Ffxemv3ksch9aec_text"  # 客户状态（_text 显示列，直显文本）
F_OWNER = "Fzgimv3kskiaahc"       # 负责人
F_PHONE = "Fqgomv3ksnqrakc"       # 联系电话

SCHEMA = {
    "rule": [
        {
            "type": "page-steps",
            "field": "stepsDemo" + "1",
            "title": "客户服务流程",
            "props": {
                "items": [
                    {"title": "登记受理", "description": "创建客户档案"},
                    {"title": "需求确认", "description": "确认服务方案"},
                    {"title": "现场实施", "description": "工程师上门服务"},
                    {"title": "验收归档", "description": "客户签收归档"},
                ],
                "active": 1,
                "direction": "horizontal",
            },
        },
        {
            "type": "page-table",
            "field": "custTable1",
            "title": "客户档案",
            "props": {
                "dataSourceId": "ds_cust",
                "columns": [
                    {"prop": F_NAME, "label": "客户名称"},
                    {"prop": F_STATUS, "label": "客户状态"},
                    {"prop": F_OWNER, "label": "负责人"},
                    {"prop": F_PHONE, "label": "联系电话"},
                ],
            },
        },
        {
            "type": "page-detail",
            "field": "custDetail1",
            "title": "客户档案详情",
            "props": {
                "dataSourceId": "ds_cust",
                "title": "客户档案详情（点击上方列表行联动）",
                "columns": [
                    {"key": F_NAME, "label": "客户名称"},
                    {"key": F_STATUS, "label": "客户状态"},
                    {"key": F_OWNER, "label": "负责人"},
                    {"key": F_PHONE, "label": "联系电话"},
                ],
            },
        },
    ],
    "option": {},
    "dataSources": [
        {"id": "ds_cust", "refId": CUST_DS_ID, "name": "客户档案-关联演示"}
    ],
    "actions": [],
}


def http(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": TENANT,
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
        method=method,
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        out = json.loads(resp.read())
    if out.get("code") not in (200, "200"):
        raise RuntimeError(f"{method} {path} -> {out}")
    return out["data"]


def main() -> None:
    token = http("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
    schema_str = json.dumps(SCHEMA, ensure_ascii=False)

    # 幂等查找
    listing = http("GET", "/v1/pages?page=1&size=100", token=token)
    existing = next((p for p in listing.get("content", []) if p.get("key") == PAGE_KEY), None)

    payload = {"name": PAGE_NAME, "key": PAGE_KEY, "type": "PAGE", "schema": schema_str}
    if existing:
        page = http("PUT", f"/v1/pages/{existing['id']}", payload, token)
        print(f"[update] {PAGE_KEY} id={page['id']} version={page.get('version')}")
    else:
        # create 接口不接收 schema：先创建，再 PUT 写入 schema
        page = http("POST", "/v1/pages",
                    {"name": PAGE_NAME, "key": PAGE_KEY, "type": "PAGE"}, token)
        print(f"[create] {PAGE_KEY} id={page['id']}")
        page = http("PUT", f"/v1/pages/{page['id']}", payload, token)
        print(f"[schema] 写入 rule={len(SCHEMA['rule'])} 组件")

    published = http("POST", f"/v1/pages/{page['id']}/publish", {}, token)
    print(f"[publish] {PAGE_KEY} publishedVersion={published.get('publishedVersion')}")
    print("运行态地址: /page/" + PAGE_KEY)


if __name__ == "__main__":
    main()
