/**
 * BPMN 流程表达式（UEL）常用上下文变量表。
 * 与引擎 ProcessInstanceService / TaskCreateBehaviorListener 注入的表达式根一致：
 * 仅列后端真实支持的路径，避免补全误导（initiator 目前仅暴露 deptManager）。
 */
import type { FlowVarItem } from '@/views/logicflow/utils/flowVars'

export const UEL_FLOW_VARS: FlowVarItem[] = [
  {
    name: 'initiator',
    group: 'upstream',
    groupLabel: '流程',
    detail: '发起人',
    children: [
      {
        name: 'initiator.deptManager',
        group: 'upstream',
        groupLabel: '流程',
        short: 'deptManager',
        detail: '发起人部门经理（审批人表达式常用）',
      },
    ],
  },
  {
    name: 'execution',
    group: 'upstream',
    groupLabel: '流程',
    detail: '流程执行上下文',
  },
]
