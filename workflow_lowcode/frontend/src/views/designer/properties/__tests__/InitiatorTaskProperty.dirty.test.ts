import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

const modelingSpy = vi.hoisted(() => ({ updateProperties: vi.fn() }))
const fakeEl = vi.hoisted(() => ({
  id: 'Init_1',
  type: 'bpmn:UserTask',
  businessObject: {
    name: '',
    documentation: [],
    get: (k: string) => (k === 'wf:nodeRole' ? 'initiator' : undefined),
  },
}))

vi.mock('../../utils/bpmnModeler', () => ({
  getModeler: () => ({
    get: (name: string) => {
      if (name === 'elementRegistry') return { get: (id: string) => (id === 'Init_1' ? fakeEl : null) }
      if (name === 'modeling') return modelingSpy
      return null
    },
  }),
}))

vi.mock('@/api/form', () => ({
  formApi: {
    getFormDefinitions: vi.fn().mockResolvedValue({ data: { content: [] } }),
    getFormDefinition: vi.fn().mockResolvedValue({ data: { schema: '[]' } }),
  },
}))

import InitiatorTaskProperty from '../InitiatorTaskProperty.vue'
import { useDesignerStore } from '@/stores/designerStore'

beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
})

describe('InitiatorTaskProperty — 加载期不得回写 BPMN', () => {
  it('选中未命名的发起节点不应触发 modeling.updateProperties（否则点选即置「未保存」）', async () => {
    const store = useDesignerStore()
    store.selectNode('Init_1', 'bpmn:UserTask', 'initiator')
    store.markClean()

    mount(InitiatorTaskProperty, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()

    expect(modelingSpy.updateProperties).not.toHaveBeenCalled()
    expect(store.isDirty).toBe(false)
  })
})
