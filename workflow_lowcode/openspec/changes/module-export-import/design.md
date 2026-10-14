# Design: module-export-import

## 1. 领域事实（实现依据，均已从代码确认）

| 实体 | 主表 | 唯一键 | 引用方式 |
|---|---|---|---|
| 表单 | `wf_form_def` | (tenant_id, key, version)，**每版本一行** | 发布建物理表 `wf_biz_<key>`（含子表 `wf_biz_<key>_<field>`），由 `DynamicTableManager.ensureTable/ensureSubTable` |
| 页面 | `wf_page_def` | (tenant_id, key, version) | `form_key`（key 引用表单）、`data_source_id`（**id 引用**数据源）、schema 内 `dataSources[].refId`（**id 引用**） |
| 数据源 | `wf_data_source` | (tenant_id, name) + (tenant_id, source_key) | `form_key` + `form_id`（双写）；`type=FORM/SYSTEM/API/WORKFLOW` |
| 逻辑流 | `wf_logic_flow` | (tenant_id, flow_key)，dsl_json | DSL 节点 config：`DATA_*` 节点 `formKey`（可传物理表名→视为外部依赖）、`SUBFLOW` 节点 flowKey |
| 表单×逻辑流 | `wf_form_logic_binding` | (tenant, form_key, trigger_type, flow_key) | 双 key 引用 |
| 流程 | `wf_process_draft` + `wf_node_config` | (tenant, key) | BPMN `formKey` 属性、节点配置 JSON |

发布语义（`FormDefinitionService`）：草稿直接发布（同一记录 status→PUBLISHED，旧 PUBLISHED→ARCHIVED）。

**关键推论**：
- 除"页面/数据源之间走 id"外，其余引用均为租户内唯一 key 字符串 → key 引用天然跨租户可移植；**id 引用必须重映射**。
- 表单导入不是纯元数据：必须同步 `ensureTable`，否则逻辑流 DATA_* 节点与页面数据源一跑就崩。

## 2. 包格式（manifestVersion=1）

```
module.zip
├── manifest.json
└── entities/
    ├── form/<key>.json          # {name,key,formType,schema,columnConfig,subTables…}
    ├── page/<key>.json          # {name,key,type,formKey,dataSourceId,schema}
    ├── datasource/<key>.json    # {name,type,formKey,formId,sourceKey,params}
    ├── logicflow/<key>.json     # {name,description,dslJson} + bindings[]
    └── workflow/<key>.json      # {name,key,category,bpmnXml,nodeConfigs[]}
```

```json
{
  "manifestVersion": 1,
  "module": {"name":"请假管理","description":"…","exportedAt":"…","exportedBy":"…","sourceTenant":"default"},
  "elements": [
    {"type":"FORM","key":"leave-apply","name":"请假申请",
     "file":"entities/form/leave-apply.json",
     "dependsOn":["DATA_SOURCE:ds-leave"],
     "externalDeps":["HTTP:https://api.example.com"]}
  ]
}
```

- `dependsOn` 边写进 manifest，导入端据此二次校验（不重新静态分析，防两侧逻辑漂移）。
- zip 用 `java.util.zip`，无需新依赖；manifest 人可读，导入前 UI 直接渲染。

## 3. 依赖边清单（DependencyAnalyzer 的唯一事实来源）

```
FORM        → FORM（schema 内 dataPicker/LookupPicker props.sourceFormKey）
            → DATA_SOURCE（无直接边，随页面走）
            ⇄ LOGIC_FLOW（wf_form_logic_binding 行，随表单导出）
PAGE        → FORM（form_key）
            → DATA_SOURCE（data_source_id + schema.dataSources[].refId，id 引用）
DATA_SOURCE → FORM（form_key/form_id；SYSTEM 内置类型不导出，只提示）
LOGIC_FLOW  → FORM（DATA_* 节点 config.formKey；formKey 为物理表名 → externalDep）
            → LOGIC_FLOW（SUBFLOW 节点）
            → external：HTTP 节点 url
WORKFLOW    → FORM（BPMN flowable:formKey + 节点配置）
            → LOGIC_FLOW（serviceTask 委托配置）
```

导出选择 = 闭包自动全选 + 用户可取消（取消时 UI 给出"将缺失依赖"的显式警告）；analyze 响应区分 `inPackage` / `missingInTarget(导入时)` / `external`。

## 4. 冲突判定（按各实体真实唯一键）

| 类型 | 判定 |
|---|---|
| FORM | (tenant, key) 存在任意 version 行 |
| PAGE | (tenant, key) |
| DATA_SOURCE | source_key 命中 **或** name 命中（两条唯一线都查）；V39 内置数据源排除且不覆盖 |
| LOGIC_FLOW | flow_key |
| WORKFLOW | key |

## 5. 导入执行

**API**：
- `POST /api/v1/modules/inspect`（multipart）→ 清单 + 每元素 {exists, conflictDetail, defaultStrategy} + 缺失依赖 + 警告（外部依赖/菜单不随包/流程为草稿态）
- `POST /api/v1/modules/import`（multipart + actions:[{type,key,strategy:OVERWRITE|SKIP}]）

**拓扑序**：FORM → DATA_SOURCE → LOGIC_FLOW → PAGE → WORKFLOW。

**各类型覆盖语义**（覆盖=新版本，绝不碰 `wf_form_data` 等运行数据）：
- FORM：insert 新行（version=max+1, status=PUBLISHED，旧 PUBLISHED→ARCHIVED）→ DDL `ensureTable/ensureSubTable`（幂等）；新建时 id 用新 UUID，`form_id` 类引用经映射表替换。表单×逻辑流绑定 upsert。
- DATA_SOURCE：按 source_key upsert；`form_id` 重映射为新表单 id。
- LOGIC_FLOW：不存在则 create→publish（version+1），存在则更新 dslJson 后 publish。
- PAGE：insert 新版本行（version=max+1, PUBLISHED，旧→ARCHIVED）；`data_source_id` 与 schema 内 `refId` 经映射表重写。
- WORKFLOW：upsert 草稿（status=DRAFT，**不自动部署**）+ nodeConfigs 重插；inspect 结果中明示"需自行部署"。

**DDL 与事务**：MySQL DDL 隐式提交——沿用平台现状（publish 同事务调 ensureTable），导入整体 @Transactional，DDL 失败即整体失败；元数据失败时已建的空物理表无害（幂等 IF NOT EXISTS）。

**安全约束**：包内 JSON 解析大小/深度限制；key 白名单正则（对齐各实体既有约束，如 flowKey `[a-zA-Z][a-zA-Z0-9_-]{1,63}`）；全程 tenant 隔离（目标租户 = 当前 X-Tenant-Id，包内 sourceTenant 仅作来源说明）。

## 6. 前端

- 菜单：系统管理下"模块管理"（Flyway 迁移插菜单，角色授权同既有模式）。
- `ModuleListPage.vue`：两个卡片入口（导出/导入）。
- `ModuleExportDialog.vue`：模块名/描述 → 五类元素分 tab 多选（复用既有列表 API）→ analyze 显示闭包预览（自动勾选 + 缺失/外部警告）→ export 下载。
- `ModuleImportDialog.vue`：上传 → manifest 预览 + 冲突表（每行 覆盖/跳过 radio，默认：新元素=导入、冲突=跳过）→ 执行 → 逐元素结果。

## 7. 测试策略

- `ModuleDependencyAnalyzerTest`：各类型引用边扫描（DSL/BPMN/schema JSON 样例）、闭包、externalDep 识别。
- `ModuleImportServiceTest`：冲突判定矩阵、覆盖=新版本、id 重映射（refId/data_source_id/form_id）、跳过策略、拓扑序、租户隔离、物理表 ensure 调用（mock DynamicTableManager）。
- `ModuleExportServiceTest`：manifest 结构、只导已发布、zip 可回读。
- 前端： Vitest 组件冒烟 + build。
