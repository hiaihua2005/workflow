# 平台演示例子 · SQL 脚本包

将本仓库演示的 5 组「例子资产」一键导入任意装有本平台（Flyway 迁移后的 `workflow` 库结构）的 MariaDB/MySQL 环境。
所有脚本**幂等可重复执行**，导入后即可在界面上看到与仓库演示完全一致的例子。

## 资产清单

| 文件 | 演示例子 | 内容 | 界面入口 |
|---|---|---|---|
| `01-dict.sql` | 字典基础数据 | `service_type` 服务类型×4、`customer_level` 客户等级×3 | 字典管理 |
| `02-demo-cust-rel.sql` | 例子 1：客户档案 | 表单 `cust_rel_demo`（PUBLISHED）+ 3 条客户数据（华东石化/长安物流/鄂尔多斯矿业）+ FORM 数据源 | 业务数据 → 客户档案-关联演示 |
| `03-demo-onsite-service.sql` | 例子 2：现场服务工单（7 扩展组件演示） | 表单 `onsite_service_demo`（PUBLISHED）+ 3 条工单数据 + 数据源 + 流水号进度 | 业务数据 → 现场服务工单-组件演示 |
| `04-demo-visit.sql` | 例子 3：客户拜访工单 | 表单 `visit_demo`（PUBLISHED，数据为空可自行录入） | 业务数据 → 客户拜访工单-组件演示 |
| `05-demo-ai-device-repair.sql` | 例子 4：AI 生成表单 | 表单 `ai_mv3ybmpz77` 设备报修登记（DRAFT，由小智 AI 助手 create_form 工具生成） | 表单设计器 → 搜索「设备报修」 |
| `06-demo-page-detail-steps.sql` | 例子 5：数据详情 + 步骤条页面 | 页面 `page_detail_steps_demo`（PUBLISHED）+ 顶级菜单（id=9319）+ admin 角色授权 | 左侧菜单「组件演示·数据详情与步骤条」 |

**例子 2 组件覆盖**：自动编号（AutoNumber，SVD 前缀按日重置）、字典下拉（dictSelect→service_type）、
地址三级联动（areaPicker）、地图定位（LocationPicker）、关联选择器（dataPicker→例子 1）、
关联记录卡片（RelationCard→例子 1 数据源，实时档案）、分步表单（StepsForm 三步）、抽屉容器（DrawerContainer）。

**例子 5 组件说明**：`page-steps` 步骤条（客户服务流程 4 步，当前第 2 步高亮）、`page-table` 客户档案表格、
`page-detail` 数据详情（点击表格行 → 下方 KV 详情实时联动，无需刷新）。

## 导入方法

```bash
# 建议按编号顺序执行（03 依赖 02 的数据源，06 依赖 02 的客户数据）
mariadb -u root -p workflow < 01-dict.sql
mariadb -u root -p workflow < 02-demo-cust-rel.sql
mariadb -u root -p workflow < 03-demo-onsite-service.sql
mariadb -u root -p workflow < 04-demo-visit.sql
mariadb -u root -p workflow < 05-demo-ai-device-repair.sql
mariadb -u root -p workflow < 06-demo-page-detail-steps.sql
```

> 数据库名不是 `workflow` 时：`mariadb -u root -p <你的库名> < 01-dict.sql`。
> 前提：目标库已执行过本平台 Flyway 迁移（即表结构已存在），本包只写数据、不含 DDL。

## 幂等与安全策略

- **有自然唯一键的表**（`wf_form_def`、`wf_page_def`、`wf_data_source`、`wf_serial_number`）：
  `INSERT ... ON DUPLICATE KEY UPDATE`，同 key 已存在则整行更新为演示版本。
- **无自然唯一键的表**（`sys_dict_data`、`sys_menu`、`sys_role_menu`）：事务内先 `DELETE` 同范围旧行再写入，
  重复执行不产生重复数据。
- **主键重映射**：auto_increment 表（字典/菜单/角色菜单）主键统一映射到 9xxx 段（9001~9007、9319、9110），
  避免与目标环境 Flyway 种子数据冲突；跨表引用（角色菜单→菜单）已按映射改写。
- **字典导入会覆盖**：`01-dict.sql` 会先删除目标库中同 `dict_code`（service_type/customer_level）的既有项再写入。
- 已知修正：`wf_data_source.onsite_service_demo.form_id` 在导出时统一改写为 default 租户的表单 id
  （线上历史行误指 tenant=1 孤儿行，运行时按 form_key 匹配故未暴露）。

## 重新生成

```bash
python3 scripts/export_demo_sql.py   # 从当前 workflow 库重新导出（覆盖本目录）
```

导出数据快照日期：2026-10-11（演示工单号 SVD202610110001~0003，导入后流水号自动从 0004 续排）。
