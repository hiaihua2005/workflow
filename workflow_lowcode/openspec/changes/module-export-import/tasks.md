# Tasks: module-export-import

## 1. 后端：包格式模型与依赖分析

- [x] 1.1 `ModuleElementType`（FORM/PAGE/DATA_SOURCE/LOGIC_FLOW/WORKFLOW）+ `ModuleManifest`/`ModuleElement`/`ElementPayload`（含 sourceId 与五类静态工厂）— `engine/module/ModuleTypes.java`
- [x] 1.2 `ModuleDependencyAnalyzer`：FORM schema（props.sourceFormKey）、PAGE（form_key + data_source_id + schema.dataSources[].refId）、DATA_SOURCE（form_key）、LOGIC_FLOW DSL（DATA_* formKey 含 entries[]、SUBFLOW flowId/flowKey、HTTP url→external、BATCH body 递归）、WORKFLOW（BPMN formKey 正则 + 编辑态 nodeConfigs 中 formKey/flowKey 递归扫描）、`wf_form_logic_binding` 双向边
- [x] 1.3 闭包计算（BFS）+ 外部依赖识别（SYSTEM/API 数据源不导出、HTTP、物理表名直连 TABLE:）
- [x] 1.4 单测 + 集成测试：闭包双向语义（正向导流不带触发方/反向导表单带全部依赖）

## 2. 后端：导出

- [x] 2.1 `ModuleExportService`：只导已发布快照；entities/*.json
- [x] 2.2 `POST /api/v1/modules/analyze`（AnalyzeVO：selected/autoAdded/missing/externalDeps）
- [x] 2.3 `POST /api/v1/modules/export`：zip 流（UTF-8 文件名）
- [x] 2.4 单测：zip 往返、非法包拒绝（manifest 缺失/版本不符/载荷缺失/路径穿越）— 6/6 绿

## 3. 后端：导入预检

- [x] 3.1 包解析与校验（manifestVersion、条目路径穿越、64MB/16MB/2000 条上限）
- [x] 3.2 `ModuleInspectService` + `POST /api/v1/modules/inspect`：真实唯一键冲突判定（数据源 source_key/name 双线）、依赖缺口、警告
- [x] 3.3 集成测试覆盖：t2 全不存在 / t1 全冲突（冒烟 + 集成测试双验证）

## 4. 后端：导入执行

- [x] 4.1 `ModuleImportService.importModule`：固定大类序 + 组内 manifest 依赖拓扑（循环依赖报错）；OVERWRITE/SKIP
- [x] 4.2 FORM：新版本行（PUBLISHED，旧→ARCHIVED）+ ensureTable/ensureSubTable + 触发绑定 upsert
- [x] 4.3 id 重映射：sourceId→"ID:<oldId>" 映射；数据源 form_id、页面 data_source_id 与 schema refId、**SUBFLOW flowId**（冒烟阶段发现补修）
- [x] 4.4 LOGIC_FLOW upsert+PUBLISHED；PAGE 新版本行；WORKFLOW 草稿态 upsert（清部署痕迹，不自动部署）
- [x] 4.5 `POST /api/v1/modules/import`（multipart + actions），@Transactional
- [x] 4.6 集成测试：跨租户全链路（id 重映射四类断言全过）、SKIP 零写入、源租户不受影响

## 5. 前端

- [x] 5.1 `api/module.ts`（blob 拦截器适配：响应整体放行 + JSON 错误识别 + 120s 超时）
- [x] 5.2 `ModuleListPage.vue` + `V56__add_module_menu.sql` + 路由 `/module`
- [x] 5.3 `ModuleExportDialog.vue`（分页结构统一兼容 content/records/rows；闭包预览）
- [x] 5.4 `ModuleImportDialog.vue`（三步向导；footer 插槽统一规避 Vue codegen bug）
- [x] 5.5 vue-tsc 本次改动文件零错误；vite build 全绿

## 6. 验证

- [x] 6.1 全量编译通过；ModulePackageCodecTest 6/6 + ModuleExportImportIntegrationTest 3/3 全绿
- [x] 6.2 端到端冒烟（API 驱动，真实库）：analyze 闭包（含 SUBFLOW/触发绑定两级链）→ 导出 5 元素 zip → manifest 结构验证 → inspect 冲突预检（5/5 exists + targetVersion）→ 全 SKIP 零写入 → 单元素覆盖（v1→v2，旧版 ARCHIVED）→ 业务数据 3 行完好 + 触发绑定保留

## 冒烟修复的 bug

1. `ModuleDependencyAnalyzer.flowIdToKey`：SUBFLOW flowId 是主键 id，误用 findByFlowKey → 改 findById+租户过滤
2. 前端 `exportModule`：http 拦截器对 blob 整体放行（返回 Blob 本身），取 `resp.data` 恒 undefined；且需识别 blob 通道里的 JSON 错误响应
3. 前端取数：五类列表分页结构不统一（Spring Page=content / PageResult=rows），统一 rowsOf 兼容
4. `ModuleExportDialog.vue` vue 导入笔误（computed/ref 误从 element-plus 导入）、processDefinitionApi 导出名错误
5. 存量 CSS（designer/logicflow/bpmn-canvas 三主题文件注释）触发 lightningcss minify 崩溃 → 注释改写，vite build 解锁

## 剩余建议（非阻塞）

- 前端 Vitest 组件冒烟（导出/导入对话框交互）
- 重命名式冲突解决（二期，"整包 key 前缀"方案，见 design.md 非目标说明）
