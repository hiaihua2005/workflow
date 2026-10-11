# 平台演示例子 · SQL 脚本包

将本仓库演示的 7 组「例子资产」一键导入任意装有本平台（Flyway 迁移后的 `workflow` 库结构）的 MariaDB 环境。
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
| `07-demo-leave-approval.sql` | **逻辑流场景 1：审批联动台账** | WORKFLOW 表单 `leave_apply_demo` + 业务表单 `leave_ledger_demo` + 流程 `leave_approval_demo`（含 Flowable 部署态 v9）+ 逻辑流 `leave_ledger_sync` + 绑定（AFTER_PROCESS_FINISH）+ 台账种子×3 | 流程中心 → 发起「请假审批-逻辑流演示」→ 待办审批通过 → 业务数据 → 员工年假台账-逻辑流演示 |
| `08-demo-material-pickup.sql` | **逻辑流场景 2：领用联动库存与流水** | 业务表单 `material_pickup_demo` + `material_stock_demo` + `pickup_log_demo` + 逻辑流 `pickup_stock_sync` + 绑定（AFTER_CREATE）+ 种子（库存×3/领用×1/流水×1） | 业务数据 → 新增「物料领用单-逻辑流演示」→ 物料库存/领用流水自动联动 |

**例子 2 组件覆盖**：自动编号（AutoNumber，SVD 前缀按日重置）、字典下拉（dictSelect→service_type）、
地址三级联动（areaPicker）、地图定位（LocationPicker）、关联选择器（dataPicker→例子 1）、
关联记录卡片（RelationCard→例子 1 数据源，实时档案）、分步表单（StepsForm 三步）、抽屉容器（DrawerContainer）。

**例子 5 组件说明**：`page-steps` 步骤条（客户服务流程 4 步，当前第 2 步高亮）、`page-table` 客户档案表格、
`page-detail` 数据详情（点击表格行 → 下方 KV 详情实时联动，无需刷新）。

**逻辑流场景链路**：

- 场景 1（07）：流程结束（AFTER_PROCESS_FINISH）→ 逻辑流 `leave_ledger_sync`：查询台账是否已建档 →
  CONDITION 分支：无建档先 `DATA_INSERT` 自动建档（年假总额 10 天）→ `DATA_UPDATE ADD` 累加已用天数。
  演示种子：张三 1/10、李四 3/10、王五 6/10 —— 多次审批同一人可看到跨次累计。
- 场景 2（08）：领用单新增（AFTER_CREATE，同步事务）→ 逻辑流 `pickup_stock_sync`：
  `DATA_UPDATE SUB` 扣减库存（productCode 匹配）+ `DATA_INSERT` 写入领用流水（含「来源领用单 {{dataId}}」回填）——
  **一个动作联动两个业务表单**。
- 逻辑流 DSL 全部按 form_key/表名引用（不依赖 UUID），跨环境可移植；触发绑定按 (tenant, formType, formKey, triggerType) 解析。

## 导入方法

```bash
# 建议按编号顺序执行（03 依赖 02 的数据源，06 依赖 02 的客户数据；07/08 相互独立、不依赖 02~06）
mariadb -u root -p workflow < 01-dict.sql
mariadb -u root -p workflow < 02-demo-cust-rel.sql
mariadb -u root -p workflow < 03-demo-onsite-service.sql
mariadb -u root -p workflow < 04-demo-visit.sql
mariadb -u root -p workflow < 05-demo-ai-device-repair.sql
mariadb -u root -p workflow < 06-demo-page-detail-steps.sql
mariadb -u root -p workflow < 07-demo-leave-approval.sql
mariadb -u root -p workflow < 08-demo-material-pickup.sql
```

> 数据库名不是 `workflow` 时：`mariadb -u root -p <你的库名> < 01-dict.sql`。
> 前提：目标库已执行过本平台 Flyway 迁移（平台固定表已存在）。含业务数据的脚本（02/03/07/08）
> 内置 `CREATE TABLE IF NOT EXISTS` DDL（MariaDB 方言），全新环境也能直接导入。

## 幂等与安全策略

- **有自然唯一键的表**（`wf_form_def`、`wf_page_def`、`wf_data_source`、`wf_serial_number`、`wf_logic_flow`、
  `wf_form_logic_binding`、`wf_process_draft`、`wf_node_config`、`wf_biz_*`、Flowable `act_re_*`/`act_ge_bytearray`）：
  `INSERT ... ON DUPLICATE KEY UPDATE`，同 key 已存在则整行更新为演示版本。
- **无自然唯一键的表**（`sys_dict_data`、`sys_menu`、`sys_role_menu`）：事务内先 `DELETE` 同范围旧行再写入，
  重复执行不产生重复数据。
- **主键重映射**：auto_increment 表（字典/菜单/角色菜单）主键统一映射到 9xxx 段（9001~9007、9319、9110），
  避免与目标环境 Flyway 种子数据冲突；跨表引用（角色菜单→菜单）已按映射改写。
- **字典导入会覆盖**：`01-dict.sql` 会先删除目标库中同 `dict_code`（service_type/customer_level）的既有项再写入。
- **Flowable 部署态还原（07）**：`act_re_deployment`/`act_re_procdef`/`act_ge_bytearray` 主键均为 UUID（无 auto_increment），
  只导出最新部署版本（v9），BLOB 字节列以 `HEX()` 导出、`UNHEX()` 写入；插入顺序按外键
  （deployment → procdef → bytearray）。导入后流程可直接发起，无需重新部署；
  后续在设计器再次发布会生成 v10+，与快照互不影响。运行实例/历史（act_ru_/act_hi_）不在导出范围。
- **bit(1) 列**（绑定 enabled）以 `b'1'`/`b'0'` 字面量写入。
- 已知修正：`wf_data_source.onsite_service_demo.form_id` 在导出时统一改写为 default 租户的表单 id
  （线上历史行误指 tenant=1 孤儿行，运行时按 form_key 匹配故未暴露）。

## 重新生成

```bash
python3 scripts/export_demo_sql.py   # 从当前 workflow 库重新导出（覆盖本目录）
```

导出数据快照日期：2026-10-11（演示工单号 SVD202610110001~0003，导入后流水号自动从 0004 续排；
场景 2 演示态：库存 P001=195（200-5）+ 领用单/流水各 1 行）。

> 全新环境的补充说明：业务数据表由发布表单时动态建表（`ensureTable`），本包 02/03/07/08 已内置对应 DDL；
> 若你在 MySQL（非 MariaDB）导入遇到 `utf8mb4_uca1400_ai_ci` 排序规则不识别，可先在表单设计器中
> 重新发布一次表单（后端会以本地方言建表），再执行脚本的数据部分。
