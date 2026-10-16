import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/category', () => ({ categoryApi: { list: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('@/api/role', () => ({ getRoleList: vi.fn().mockResolvedValue({ data: [] }) }))

import ProcessProperty from '../ProcessProperty.vue'
import { useDesignerStore } from '@/stores/designerStore'

beforeEach(() => setActivePinia(createPinia()))

describe('ProcessProperty dirty guard', () => {
  it('挂载流程属性面板（未做任何修改）不应把 isDirty 置为 true', async () => {
    const store = useDesignerStore()
    store.setDraft('d1', '流程', 'key')
    store.setBpmnXml('<x/>')
    store.setNodeConfigs({})
    store.setDraftBasicInfo({ categoryId: null, description: '' })
    store.setSavedSnapshot('<x/>', {})
    store.markClean()
    store.selectNode(null, 'Process')
    expect(store.isDirty).toBe(false)

    mount(ProcessProperty, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()

    expect(store.isDirty).toBe(false)
  })
})
