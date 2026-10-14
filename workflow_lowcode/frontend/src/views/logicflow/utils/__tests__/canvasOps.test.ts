import { describe, it, expect } from 'vitest'
import { ARRANGE_GAP, PASTE_OFFSET, arrangePositions, buildClipboard, planPaste } from '../canvasOps'
import type { ArrangeBox } from '../canvasOps'
import type { FlowEdge, FlowNode } from '../dsl'

function node(id: string, nodeType: FlowNode['data']['nodeType'], x: number, y: number): FlowNode {
  return { id, type: 'logic', position: { x, y }, data: { nodeType, name: id } }
}

function edge(id: string, source: string, target: string, extra: Partial<FlowEdge> = {}): FlowEdge {
  return { id, source, target, ...extra }
}

function box(id: string, x: number, y: number, w = 160, h = 60): ArrangeBox {
  return { id, x, y, w, h }
}

describe('arrangePositions', () => {
  it('横向排列：按原 x 保序、等间距铺开、纵向按中线对齐', () => {
    const out = arrangePositions([box('c', 300, 40), box('a', 100, 120), box('b', 200, 0, 160, 100)], 'horizontal')
    // 保序：a(100) → b(200) → c(300)
    expect(out.a.x).toBe(100)
    expect(out.b.x).toBe(100 + 160 + ARRANGE_GAP)
    expect(out.c.x).toBe(100 + (160 + ARRANGE_GAP) * 2)
    // 中线 = (min center, max center) 均值：a 中心 150、b 中心 50、c 中心 70 → (50+150)/2 = 100
    expect(out.a.y).toBe(100 - 30) // h=60
    expect(out.b.y).toBe(100 - 50) // h=100
    expect(out.c.y).toBe(100 - 30)
  })

  it('纵向排列：按原 y 保序、等间距堆叠、横向按中线对齐', () => {
    const out = arrangePositions([box('b', 400, 200, 200, 60), box('a', 0, 0, 160, 60)], 'vertical')
    expect(out.a.y).toBe(0)
    expect(out.b.y).toBe(0 + 60 + ARRANGE_GAP)
    // 中线 = (a 中心 80, b 中心 500) 均值 290
    expect(out.a.x).toBe(290 - 80)
    expect(out.b.x).toBe(290 - 100)
  })

  it('单图元排列：起点不变，仅按中线对齐（整图排一行时锚定原左上角）', () => {
    const out = arrangePositions([box('only', 123, 45, 160, 60)], 'horizontal')
    expect(out.only).toEqual({ x: 123, y: 45 })
  })

  it('坐标为整数（画布 10px 吸附同量级，避免小数抖动）', () => {
    const out = arrangePositions([box('a', 10.4, 3.3, 161.5, 61.5), box('b', 300, 0)], 'vertical')
    Object.values(out).forEach((p) => {
      expect(Number.isInteger(p.x)).toBe(true)
      expect(Number.isInteger(p.y)).toBe(true)
    })
  })

  it('空集合返回空结果', () => {
    expect(arrangePositions([], 'horizontal')).toEqual({})
  })
})

describe('buildClipboard', () => {
  const nodes = [node('start_1', 'START', 0, 0), node('http_1', 'HTTP', 100, 100), node('end_1', 'END', 300, 100)]
  const edges = [
    edge('e1', 'start_1', 'http_1'),
    edge('e2', 'http_1', 'end_1', { sourceHandle: 'out' }),
    edge('e3', 'http_1', 'start_1', { data: { branch: 'error' } }),
  ]

  it('排除 START，只收选中集合内部的连线并按下标引用', () => {
    const cb = buildClipboard(nodes, edges, ['start_1', 'http_1', 'end_1'])
    expect(cb).not.toBeNull()
    expect(cb!.nodes.map((n) => n.nodeType)).toEqual(['HTTP', 'END'])
    // e1/e3 有一端是 START（未入剪贴板）→ 不复制；e2 两端都在 → 复制（下标 0→1）
    expect(cb!.edges).toHaveLength(1)
    expect(cb!.edges[0]).toMatchObject({ source: 0, target: 1, sourceHandle: 'out' })
  })

  it('只选中 START 时返回 null（无可复制内容）', () => {
    expect(buildClipboard(nodes, edges, ['start_1'])).toBeNull()
  })

  it('节点 data 深拷贝：复制后编辑原件不影响剪贴板', () => {
    const src = [node('http_1', 'HTTP', 0, 0)]
    src[0].data.config = { url: 'http://a' } as never
    const cb = buildClipboard(src, [], ['http_1'])
    src[0].data.name = '改名了'
    ;(src[0].data.config as { url: string }).url = 'http://b'
    expect(cb!.nodes[0].data.name).toBe('http_1')
    expect((cb!.nodes[0].data.config as { url: string }).url).toBe('http://a')
  })

  it('几何与端点句柄原样保留（branch 边随节点一起复制）', () => {
    const cb = buildClipboard(
      [node('cond_1', 'CONDITION', 10, 20), node('end_1', 'END', 30, 40)],
      [edge('e1', 'cond_1', 'end_1', { sourceHandle: 'true', data: { branch: 'true' } })],
      ['cond_1', 'end_1']
    )
    expect(cb!.nodes[0]).toMatchObject({ x: 10, y: 20 })
    expect(cb!.edges[0]).toMatchObject({ sourceHandle: 'true', data: { branch: 'true' } })
  })
})

describe('planPaste', () => {
  const payload = buildClipboard(
    [node('http_1', 'HTTP', 100, 100), node('end_1', 'END', 300, 100)],
    [edge('e1', 'http_1', 'end_1', { sourceHandle: 'out' })],
    ['http_1', 'end_1']
  )!

  it('重生成 id（不与现画布冲突）、整体偏移、端点映射到新 id', () => {
    const plan = planPaste(payload, ['http_1', 'end_1'])
    expect(plan.nodes).toHaveLength(2)
    plan.nodes.forEach((n, i) => {
      expect(n.id).not.toBe(payload.nodes[i].nodeType === 'HTTP' ? 'http_1' : 'end_1')
      expect(n.id.startsWith(payload.nodes[i].nodeType.toLowerCase() + '_')).toBe(true)
      expect(n.position).toEqual({ x: payload.nodes[i].x + PASTE_OFFSET, y: payload.nodes[i].y + PASTE_OFFSET })
    })
    expect(plan.edges[0].source).toBe(plan.nodes[0].id)
    expect(plan.edges[0].target).toBe(plan.nodes[1].id)
    expect(plan.edges[0].sourceHandle).toBe('out')
  })

  it('连续粘贴 id 互不冲突（第二轮避开第一轮生成的 id）', () => {
    const first = planPaste(payload, ['http_1', 'end_1'])
    const second = planPaste(payload, ['http_1', 'end_1', ...first.ids])
    const all = [...first.ids, ...second.ids]
    expect(new Set(all).size).toBe(all.length)
  })

  it('入参不被修改（可重复粘贴）', () => {
    const snapshot = JSON.stringify(payload)
    planPaste(payload, [])
    expect(JSON.stringify(payload)).toBe(snapshot)
  })
})
