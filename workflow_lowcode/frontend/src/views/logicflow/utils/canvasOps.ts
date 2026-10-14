/**
 * 画布批量操作（框选布局 / 复制粘贴）的纯函数层：不依赖 vue-flow 实例，可单测。
 *
 * 设计器只负责「取当前图元 → 调这里 → 把结果写回画布」，几何与 id 重映射逻辑集中在此，
 * 避免把易错的排布/复制规则散落在组件里。
 */
import { createNodeId } from './dsl'
import type { FlowEdge, FlowNode, FlowNodeData, LogicNodeType } from './dsl'

/** 排列方向：horizontal = 横向排成一行，vertical = 纵向排成一列 */
export type ArrangeDirection = 'horizontal' | 'vertical'

/** 排列后相邻图元的间距（画布网格 10px 的整数倍，保持吸附手感） */
export const ARRANGE_GAP = 40

/** 粘贴相对原位的偏移：错开原件，连续粘贴呈右下阶梯 */
export const PASTE_OFFSET = 40

/** 参与排列的图元几何（id + 左上角坐标 + 尺寸） */
export interface ArrangeBox {
  id: string
  x: number
  y: number
  w: number
  h: number
}

/** 深拷贝（DSL 数据均为纯 JSON 结构；undefined 原样透传，避免 JSON 往返丢失） */
function clone<T>(value: T): T {
  if (value === undefined || value === null) return value
  return JSON.parse(JSON.stringify(value)) as T
}

/**
 * 计算排列后的坐标：按当前位置保序（横向按 x、纵向按 y），高矮/宽窄不一的图元
 * 以选中区域的中线（横向排列取纵向中线、纵向排列取横向中线）对齐，视觉居中不贴边。
 *
 * @returns id → 新坐标（仅包含传入的图元）
 */
export function arrangePositions(
  boxes: ArrangeBox[],
  direction: ArrangeDirection,
  gap: number = ARRANGE_GAP
): Record<string, { x: number; y: number }> {
  const out: Record<string, { x: number; y: number }> = {}
  if (!boxes.length) return out
  const horizontal = direction === 'horizontal'
  // 保序：沿排列轴的原坐标升序，同列/同行再按另一轴，最后按 id 兜底（保证结果稳定可测）
  const ordered = [...boxes].sort((a, b) =>
    horizontal
      ? a.x - b.x || a.y - b.y || a.id.localeCompare(b.id)
      : a.y - b.y || a.x - b.x || a.id.localeCompare(b.id)
  )
  const start = horizontal
    ? Math.min(...ordered.map((b) => b.x))
    : Math.min(...ordered.map((b) => b.y))
  // 中线 = 跨轴中心的最小/最大值的均值（比平均中心更稳：不受图元数量分布影响）
  const centers = ordered.map((b) => (horizontal ? b.y + b.h / 2 : b.x + b.w / 2))
  const axis = (Math.min(...centers) + Math.max(...centers)) / 2

  let cursor = start
  for (const box of ordered) {
    out[box.id] = horizontal
      ? { x: Math.round(cursor), y: Math.round(axis - box.h / 2) }
      : { x: Math.round(axis - box.w / 2), y: Math.round(cursor) }
    cursor += (horizontal ? box.w : box.h) + gap
  }
  return out
}

/** 剪贴板内的节点（不含画布 id：粘贴时重新生成，避免与现画布撞 id） */
export interface ClipboardNode {
  nodeType: LogicNodeType
  x: number
  y: number
  data: FlowNodeData
}

/** 剪贴板内的连线：source/target 为 ClipboardNode 数组下标（内部边，随节点一起复制） */
export interface ClipboardEdge {
  source: number
  target: number
  sourceHandle?: string | null
  targetHandle?: string | null
  data?: FlowEdge['data']
}

export interface ClipboardPayload {
  nodes: ClipboardNode[]
  edges: ClipboardEdge[]
}

/**
 * 由当前选中节点构造剪贴板：
 * - START 是流程锚点（全局唯一），不参与复制；
 * - 只复制「两端都在选中集合内」的内部连线（外部连线不复制，避免粘贴出悬空边）；
 * - 节点 data 深拷贝，复制后对原节点的编辑不影响剪贴板内容。
 *
 * @returns 无可复制节点时返回 null（如只选中了 START）
 */
export function buildClipboard(
  nodes: FlowNode[],
  edges: FlowEdge[],
  selectedIds: Iterable<string>
): ClipboardPayload | null {
  const picked = new Set(selectedIds)
  const pickedNodes = nodes.filter((n) => picked.has(n.id) && n.data.nodeType !== 'START')
  if (!pickedNodes.length) return null
  const indexOf = new Map(pickedNodes.map((n, i) => [n.id, i]))
  const innerEdges: ClipboardEdge[] = edges
    .filter((e) => indexOf.has(e.source) && indexOf.has(e.target))
    .map((e) => ({
      source: indexOf.get(e.source)!,
      target: indexOf.get(e.target)!,
      sourceHandle: e.sourceHandle ?? null,
      targetHandle: e.targetHandle ?? null,
      data: e.data ? clone(e.data) : undefined,
    }))
  return {
    nodes: pickedNodes.map((n) => ({
      nodeType: n.data.nodeType,
      x: n.position.x,
      y: n.position.y,
      data: clone(n.data),
    })),
    edges: innerEdges,
  }
}

/** 粘贴产物：id 由 createNodeId 重新生成（不与现画布冲突），边 id 交给 vue-flow 生成 */
export interface PastePlan {
  nodes: FlowNode[]
  edges: Omit<FlowEdge, 'id'>[]
  /** 新生成节点 id（顺序与 nodes 一致），供粘贴后选中 */
  ids: string[]
}

/**
 * 规划一次粘贴：重生成节点 id（避开 existingIds）、整体平移 offset、把内部连线的
 * 端点下标映射回新 id。不改动入参，可安全重复调用（连续粘贴）。
 */
export function planPaste(
  payload: ClipboardPayload,
  existingIds: Iterable<string>,
  offset: number = PASTE_OFFSET
): PastePlan {
  const taken = new Set(existingIds)
  const ids = payload.nodes.map((n) => {
    const id = createNodeId(n.nodeType, taken)
    taken.add(id)
    return id
  })
  const nodes: FlowNode[] = payload.nodes.map((n, i) => ({
    id: ids[i],
    type: 'logic',
    position: { x: n.x + offset, y: n.y + offset },
    data: clone(n.data),
  }))
  const edges: Omit<FlowEdge, 'id'>[] = payload.edges.map((e) => ({
    source: ids[e.source],
    target: ids[e.target],
    sourceHandle: e.sourceHandle ?? undefined,
    targetHandle: e.targetHandle ?? undefined,
    data: e.data ? clone(e.data) : undefined,
  }))
  return { nodes, edges, ids }
}
