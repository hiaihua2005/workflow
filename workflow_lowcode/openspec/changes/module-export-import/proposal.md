## Why

平台已具备业务表单、页面、逻辑流、工作流、数据源五大设计器能力，但资产无法跨应用（租户）复用：换一个环境要手工重建整套表单/页面/流程，既慢又易漏配依赖。需要"模块管理"能力——把选中的元素连同其依赖闭包打包为一个模块文件（zip + manifest 描述），在目标应用中导入复用；导入前可预览描述并处理同名冲突。

## What Changes

1. **依赖闭包分析**：新增 `ModuleDependencyAnalyzer`，扫描五类元素的引用边（表单 schema 组件引用、页面 form_key/refId、数据源 form 绑定、逻辑流 DSL 的 DATA_*/SUBFLOW 节点、流程 BPMN formKey + 节点配置、表单×逻辑流触发绑定），勾选任一元素自动补全依赖闭包，识别"无法打包的外部依赖"（HTTP 地址、内置数据源、字典等）并提示。
2. **模块导出**：`POST /api/v1/modules/analyze` 返回闭包与警告供 UI 预选；`POST /api/v1/modules/export` 生成 zip：`manifest.json`（模块名/版本/导出时间/元素清单+key+类型+依赖图+说明）+ `entities/<type>/<key>.json`。只导出**已发布快照**（草稿不导出）。
3. **两阶段导入**：`POST /api/v1/modules/inspect` 上传包后只解析比对、不落库，返回元素清单、同名冲突（按各实体真实唯一键判定）与依赖缺口；`POST /api/v1/modules/import` 携带逐元素策略（覆盖/跳过）执行。**覆盖 = 写入新版本行并推进发布版本，绝不触碰运行数据**；重命名冲突解法不在本期范围。
4. **导入语义**：按依赖拓扑序导入；业务表单导入复用发布链路建物理表（`wf_biz_<key>`/子表）；页面 schema 的数据源 `refId`、`data_source_id` 与数据源 `form_id` 做 **id 重映射**；逻辑流 upsert 后置为已发布；工作流导入为**草稿态**（含节点配置），由用户自行部署；导入响应明确提示"菜单/权限不随模块导入，请手工配置"。
5. **前端**：新增"模块管理"页面——导出向导（分类多选 → 闭包预览 → 下载）、导入向导（上传 → 描述预览/冲突处理 → 执行结果）。

## Capabilities

### New Capabilities

- `module-manager`: 模块打包导出与导入——元素选择与依赖闭包分析、manifest 描述文件、导入预检（冲突/缺口/外部依赖提示）、覆盖/跳过策略执行、物理表同步与 id 重映射。

## Impact

**后端（Java，`com.workflow.engine.module` 新包）：**
- `ModuleElementType` / `ModuleManifest` / `ModuleElementPayload` — 包格式模型（manifestVersion=1）
- `ModuleDependencyAnalyzer` — 引用边扫描 + 闭包计算 + 外部依赖识别
- `ModuleExportService` — 已发布快照导出、zip 打包
- `ModuleInspectService` / `ModuleImportService` — 预检与导入（冲突判定、拓扑序、物理表同步、id 重映射、事务）
- `ModuleController` — analyze / export / inspect / import 四个端点
- Flyway 迁移 — 模块管理菜单

**前端（`frontend/src`）：**
- `ModuleListPage.vue` — 模块管理页（导出/导入入口）
- `ModuleExportDialog.vue` / `ModuleImportDialog.vue` — 导出向导与导入向导
- `api/module.ts` — API 封装

**数据库：** 无新业务表（包文件即载体）；仅菜单迁移。

**明确不做（非目标）：** 重命名式冲突解决、菜单/权限/运行数据打包、"应用"抽象层、跨平台版本升级转换。
