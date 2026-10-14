# E2E 测试场景 — 工作流全节点覆盖

> **测试用例全集：`[TESTCASES.md](./TESTCASES.md)`**（逻辑流全节点场景 + UI E2E + 回归 + 问题修复；文末附 BPMN 审批用例索引）
> 4 个流程 + 5 个表单 + 2 个逻辑流，组合覆盖工作流引擎全部节点类型与主要属性。
> `setup.ps1` 一键创建/部署全部数据；`cases.md` 为审批流程（BPMN）agent-browser E2E 用例。

## 资产总览

| 资产 | key | 覆盖能力 |
|---|---|---|
| 流程A 差旅报销审批 | `e2e_expense` | 发起节点表单绑定、排他网关 + 表单条件 + **默认流**、或签（2人）、办理节点、意见必填（范围 ALL/REJECT_RETURN）、任务优先级 + 办理时限、退回「选择重审起始节点」 |
| 流程B 合同评审审批 | `e2e_contract` | **会签通过比例**（50%）、**票签权重投票**（admin 权重2 / test 权重1）、依次审批、手写签名、流程级意见策略 ALL、审批人可调操作 |
| 流程C 数据同步审批 | `e2e_sync` | **ServiceTask 调用逻辑流**（成功+失败双路）、**错误边界事件**（BpmnError→兜底分支）、**定时边界事件**（非中断 PT1M 升级分支）、并行网关、**内嵌子流程变量绑定**、**调用活动（CallActivity）** |
| 流程D 简单复核 | `e2e_simple` | 被 P3 CallActivity 调用的子流程；独立发起/审批链路 |
| 流程E 项目结算审批 | `e2e_settle` | **低码对照用例**：内嵌子流程内放**逻辑流服务任务节点**，出入参映射为属性面板可视化配置（替代流程C 的监听器变量绑定），业务零 Java 代码 |
| 逻辑流 成功链 | `e2e_demo_ok` | SCRIPT 节点：`inputValue * 2` → 输出 `doubled`（WHOLE 声明） |
| 逻辑流 失败链 | `e2e_demo_fail` | SCRIPT 节点：抛 RuntimeException → 引擎判定 FAILED → 服务任务抛 BpmnError |
| 逻辑流 结算链 | `e2e_settle_flow` | SCRIPT 节点：`baseAmount * 1.06` → 输出 `settleResult`（TC-07 用） |
| 表单 F1 差旅申请表 | `e2e_form_expense` | input / inputNumber / select / datePicker（发起节点绑定） |
| 表单 F2 打款执行表 | `e2e_form_pay` | 办理节点差异化表单 |
| 表单 F3 合同评审表 | `e2e_form_contract` | textarea / select / inputNumber（发起节点绑定） |
| 表单 F4 数据同步表 | `e2e_form_sync` | inputNumber / select / input（发起节点绑定） |
| 表单 F5 复核表 | `e2e_form_check` | input（P4 发起节点绑定） |
| 表单 F7 项目结算表 | `e2e_form_settle` | input / inputNumber（流程E 发起节点绑定） |

## 流程拓扑

### 流程A e2e_expense（差旅报销）
```
发起填报(admin, F1) → [金额>10000?] ─是→ 总监审批(admin, 加急P100, 可选起始节点退回) ─┐
                                    └默认─────────────────────→ 财务初审(或签 admin+test) → 出纳打款(办理, 意见ALL必填) → 结束
```

### 流程B e2e_contract（合同评审）
```
发起填报(admin, F3) → 法务会签(admin+test, 通过比例50%) → 评审投票(票签 admin权重2/test权重1, 50%) → 依次审批(admin→test, 签名) → 结束
```

### 流程C e2e_sync（数据同步）
```
                          ┌→ 同步计算(逻辑流e2e_demo_ok, 出参calcResult) → 合规审批(test)
发起填报(admin, F4) → 并行 ┤        └⏰ 定时边界PT1M(非中断) → 升级审批(admin) ┐
                          └→ 失败同步(逻辑流e2e_demo_fail) ⚠错误边界 → 异常处理(admin) ┘→ 并行汇聚
                          → 内嵌子流程(入参calcResult→innerVal, 出参→subEcho, 子任务:子流程复核 test) → 调用活动(e2e_simple) → 结束
```

### 流程D e2e_simple（简单复核）
```
发起填报(${initiator}, F5) → 复核审批(test) → 结束
```

### 流程E e2e_settle（项目结算，低码对照）
```
发起填报(admin, F7) → 部门审批(test)
  → 内嵌子流程[ 结算计算(逻辑流e2e_settle_flow: 入参baseAmount, 出参settleResult) ]
  → 汇总确认(test) → 结束
```

## 测试账号

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | ROLE_ADMIN（发起人 + 多数审批人） |
| test | admin123* | ROLE_USER（次审批人/办理人） |

> *种子脚本注释写 test123，但 test 与 admin 使用同一 BCrypt 哈希，实际密码为 admin123。

## 环境前置

- 后端 8080（已运行，MySQL `workflow` 库，Flyway 自动迁移）
- 前端 vite 3000（`npm run dev`，/api 代理 8080）
- 运行 `.\setup.ps1` 创建并部署全部资产（幂等：重复执行会因 key 冲突提示跳过/更新）
