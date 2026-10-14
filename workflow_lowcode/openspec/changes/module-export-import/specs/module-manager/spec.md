# module-manager 规格

## ADDED Requirements

### Requirement: 模块导出

平台 SHALL 支持将用户选择的业务表单、页面、逻辑流、工作流、数据源打包导出为一个模块文件（zip）。

- 导出内容只含**已发布快照**；未发布元素 SHALL 被拒绝导出并提示。
- 导出前系统 SHALL 分析选择集的依赖闭包并自动补选缺失依赖；用户取消勾选依赖时 SHALL 收到缺失后果警告。
- 模块文件 MUST 包含 `manifest.json` 描述文件：模块名、描述、导出时间、导出人、来源租户、manifestVersion、元素清单（类型/key/名称/文件路径/依赖边/外部依赖）。
- 无法打包的外部依赖（HTTP 调用地址、系统内置数据源、字典等）SHALL 在 manifest 与导出预览中作为警告列出。

#### Scenario: 导出带依赖的流程

- **WHEN** 用户仅勾选一条工作流（其节点绑定表单 A、表单 A 挂触发逻辑流 L、页面引用数据源 D）
- **THEN** analyze 返回闭包 {工作流, 表单A, 逻辑流L, 页面, 数据源D} 且自动全选
- **AND** 导出的 zip 中 manifest.elements 含全部五个元素，dependsOn 边完整

### Requirement: 导入预检

平台 SHALL 提供导入预检：上传模块文件后仅解析比对、不落库，返回元素清单、同名冲突与依赖缺口。

- 同名冲突 SHALL 按各实体真实唯一键判定：表单/页面/流程按 (租户, key)，逻辑流按 flow_key，数据源按 source_key **或** name 任一命中；系统内置数据源不参与冲突与覆盖。
- 预检结果 SHALL 支持前端直接渲染 manifest 描述（模块说明与元素清单）。
- 包内依赖既不在包内、目标应用也无同名元素时 SHALL 判定为依赖缺口并阻止默认导入。

#### Scenario: 同名表单冲突提示

- **WHEN** 目标应用已存在 key 相同的表单且用户上传包含该表单的模块
- **THEN** inspect 返回该元素 conflictDetail（目标当前版本信息），UI 提示用户选择覆盖或跳过

### Requirement: 导入执行

平台 SHALL 按依赖拓扑序（表单→数据源→逻辑流→页面→工作流）执行导入，逐元素策略为覆盖（OVERWRITE）或跳过（SKIP）。

- 覆盖表单/页面 SHALL 写入新版本行并推进发布版本，旧发布版本归档，**不得修改任何运行数据**。
- 导入已发布业务表单 SHALL 同步创建/更新物理表（`wf_biz_<key>` 与子表）。
- 数据源 id 引用（`form_id`、页面 `data_source_id`、页面 schema `dataSources[].refId`）SHALL 重映射到目标环境新建实体的 id。
- 逻辑流导入后 SHALL 处于已发布状态；工作流导入后 SHALL 处于草稿态并提示用户自行部署。
- 导入完成响应 SHALL 逐元素返回结果，并提示"菜单与权限不随模块导入，请手工配置"。

#### Scenario: 覆盖导入后引用不断链

- **WHEN** 覆盖导入的表单被分配新 id，且页面 schema 内 refId 指向其数据源
- **THEN** 页面 schema 中 refId 与 data_source_id 均已重写为新 id，页面发布校验通过

#### Scenario: 跳过策略

- **WHEN** 用户对冲突元素选择跳过
- **THEN** 该元素及其仅被其使用的下游依赖均不写入，导入响应中标记为 skipped
