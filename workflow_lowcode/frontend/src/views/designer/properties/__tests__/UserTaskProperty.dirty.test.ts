import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

const fakeEl = vi.hoisted(() => ({
  id: 'UserTask_1',
  type: 'bpmn:UserTask',
  businessObject: {
    name: '部门经理审批',
    get: (k: string) => (k === 'wf:nodeRole' ? 'approver' : undefined),
  },
}))

vi.mock('../../utils/bpmnModeler', () => ({
  getModeler: () => ({
    get: (name: string) => {
      if (name === 'elementRegistry') return { get: (id: string) => (id === 'UserTask_1' ? fakeEl : null) }
      if (name === 'modeling') return { updateProperties: vi.fn() }
      return null
    },
  }),
}))

vi.mock('@/api/user', () => ({ getUserBatch: vi.fn().mockResolvedValue({ data: [] }) }))

import UserTaskProperty from '../UserTaskProperty.vue'
import { useDesignerStore } from '@/stores/designerStore'

beforeEach(() => setActivePinia(createPinia()))

describe('UserTaskProperty dirty guard', () => {
  it('选择节点（加载配置）不应把 isDirty 置为 true', async () => {
    const store = useDesignerStore()
    store.selectNode('UserTask_1', 'bpmn:UserTask', 'approver')
    store.setNodeConfig('UserTask_1', {
      basic: { name: '部门经理审批' },
      approval: { type: 'user', userIds: [2] },
    })
    store.markClean()
    expect(store.isDirty).toBe(false)

    mount(UserTaskProperty, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()

    expect(store.isDirty).toBe(false)
  })
})
