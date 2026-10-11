// ----- RelationFieldSelect：ds 模式旧版 sourceFormKey 自动迁移单测 -----
// npx vitest run src/components/business/__tests__/RelationFieldSelect.test.ts
//
// 覆盖：v4 面板单一数据源绑定后，存量卡片（仅存 sourceFormKey）打开面板时
// 自动迁移为对应 FORM 数据源（emit 与用户手选同路径）。

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RelationFieldSelect from '../RelationFieldSelect.vue'

vi.mock('../../../api/data-source', () => ({
  dataSourceApi: {
    getEnabledDataSources: vi.fn(),
    getMetadata: vi.fn(),
  },
}))
vi.mock('../../../api/form', () => ({
  formApi: {
    getFormDefinitions: vi.fn(),
    getFormDefinitionByKey: vi.fn(),
  },
}))

import { dataSourceApi } from '../../../api/data-source'

const dsEnabledMock = vi.mocked(dataSourceApi.getEnabledDataSources)

const DS_ROWS = [
  { id: 'ds-form-1', name: '客户档案-关联演示 数据源', type: 'FORM', formKey: 'cust_rel_demo' },
  { id: 'ds-user', name: '系统用户', type: 'SYSTEM', formKey: null },
]

function mountDs(props: Record<string, unknown>) {
  return mount(RelationFieldSelect, {
    props: { mode: 'ds', ...props },
    global: { plugins: [ElementPlus] },
  })
}

beforeEach(() => {
  dsEnabledMock.mockReset()
  dsEnabledMock.mockResolvedValue({ data: DS_ROWS } as any)
})

describe('RelationFieldSelect ds 模式 — 旧版 sourceFormKey 自动迁移', () => {
  it('存量卡片：有 sourceFormKey 且未选数据源 → 自动迁移为对应 FORM 数据源（emit update:modelValue）', async () => {
    const canvasRule = { props: { sourceFormKey: 'cust_rel_demo', globalDataSourceId: '' } }
    const wrapper = mountDs({ canvasRule, modelValue: '', migrateFromField: 'sourceFormKey' })
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')![0][0]).toBe('ds-form-1')
    expect(wrapper.text()).toContain('已自动迁移')
    expect(wrapper.text()).toContain('客户档案-关联演示 数据源')
  })

  it('已选择数据源 / 无旧配置 → 不迁移不 emit', async () => {
    const canvasRule = { props: { sourceFormKey: 'cust_rel_demo', globalDataSourceId: 'ds-user' } }
    const w1 = mountDs({ canvasRule, modelValue: 'ds-user', migrateFromField: 'sourceFormKey' })
    await flushPromises()
    expect(w1.emitted('update:modelValue')).toBeUndefined()

    const w2 = mountDs({
      canvasRule: { props: { sourceFormKey: '', globalDataSourceId: '' } },
      modelValue: '',
      migrateFromField: 'sourceFormKey',
    })
    await flushPromises()
    expect(w2.emitted('update:modelValue')).toBeUndefined()
  })

  it('旧表单无对应数据源 → 提示未找到，不写值', async () => {
    const canvasRule = { props: { sourceFormKey: 'gone_form', globalDataSourceId: '' } }
    const wrapper = mountDs({ canvasRule, modelValue: '', migrateFromField: 'sourceFormKey' })
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).toContain('未找到对应 FORM 数据源')
  })

  it('未传 migrateFromField → 纯下拉行为（不迁移）', async () => {
    const canvasRule = { props: { sourceFormKey: 'cust_rel_demo', globalDataSourceId: '' } }
    const wrapper = mountDs({ canvasRule, modelValue: '', migrateFromField: '' })
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).toContain('共 2 个已启用数据源')
  })
})
