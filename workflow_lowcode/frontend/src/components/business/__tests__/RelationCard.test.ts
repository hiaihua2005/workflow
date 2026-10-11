// ----- Task 3-g: RelationCard 组件测试 -----
// npx vitest run src/components/business/__tests__/RelationCard.test.ts
//
// 覆盖：渲染（字符串/对象/空值）、值摘要提取（displayField 深层取值 + 常用键回退）、
// clickable 交互（open-detail 事件）。

import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RelationCard from '../RelationCard.vue'

function createWrapper(props: any = {}) {
  return mount(RelationCard, {
    props: {
      modelValue: null,
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

function valueEl(wrapper: any) {
  return wrapper.find('.relation-card__value')
}

function placeholderEl(wrapper: any) {
  return wrapper.find('.relation-card__placeholder')
}

describe('RelationCard — 渲染', () => {
  it('空值显示「未选择关联记录」占位，不渲染值摘要', () => {
    const wrapper = createWrapper({ modelValue: '' })
    expect(placeholderEl(wrapper).exists()).toBe(true)
    expect(placeholderEl(wrapper).text()).toContain('未选择关联记录')
    expect(valueEl(wrapper).exists()).toBe(false)
  })

  it('字符串值直显（对齐 LookupPicker 新语义：modelValue=显示文本）', () => {
    const wrapper = createWrapper({ modelValue: 'XX 采购合同' })
    expect(valueEl(wrapper).text()).toBe('XX 采购合同')
    expect(placeholderEl(wrapper).exists()).toBe(false)
  })

  it('对象值按 displayField 深层取值（BizDataVO 内层 data 优先）', () => {
    const wrapper = createWrapper({
      modelValue: { id: 42, data: { code: 'PRJ-A' }, code: '顶层code' },
      displayField: 'code',
    })
    expect(valueEl(wrapper).text()).toBe('PRJ-A')
  })

  it('对象值未配置 displayField 时回退常用键（name/title/label/id）', () => {
    const wrapper = createWrapper({ modelValue: { id: 7, title: '备件入库单' } })
    expect(valueEl(wrapper).text()).toBe('备件入库单')
  })

  it('title 与 description 渲染在卡片标题行/说明行', () => {
    const wrapper = createWrapper({
      modelValue: '关联方：A 公司',
      title: '关联合同',
      description: '来自合同台账',
    })
    expect(wrapper.find('.relation-card__title').text()).toBe('关联合同')
    expect(wrapper.find('.relation-card__desc').text()).toBe('来自合同台账')
  })
})

describe('RelationCard — 交互', () => {
  it('clickable（默认 true）时点击卡片 emit open-detail 并携带原始值', async () => {
    const value = { id: 9, name: '某某项目' }
    const wrapper = createWrapper({ modelValue: value })
    await wrapper.find('.relation-card__body').trigger('click')
    expect(wrapper.emitted('open-detail')).toHaveLength(1)
    expect(wrapper.emitted('open-detail')![0][0]).toEqual(value)
  })

  it('clickable=false 时点击不触发 open-detail，且无 hover 可点击态', () => {
    const wrapper = createWrapper({ modelValue: '文本值', clickable: false })
    expect(wrapper.find('.relation-card__body').classes()).not.toContain('is-clickable')
    wrapper.find('.relation-card__body').trigger('click')
    expect(wrapper.emitted('open-detail')).toBeUndefined()
  })

  it('空值时点击不触发 open-detail（无详情可看）', () => {
    const wrapper = createWrapper({ modelValue: null })
    wrapper.find('.relation-card__body').trigger('click')
    expect(wrapper.emitted('open-detail')).toBeUndefined()
  })
})

// ===== v2 实时模式（sourceFormKey + recordIdField → bizDataApi.detail 实时取数） =====
import { vi, beforeEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { resolveRecordId } from '../lookupFetch'

vi.mock('../../../api/bizData', () => ({
  bizDataApi: {
    detail: vi.fn(),
  },
}))
vi.mock('../../../api/form', () => ({
  formApi: {
    getFormDefinitionByKey: vi.fn(),
  },
}))

import { bizDataApi } from '../../../api/bizData'
import { formApi } from '../../../api/form'

const detailMock = vi.mocked(bizDataApi.detail)
const formDefMock = vi.mocked(formApi.getFormDefinitionByKey)

function columnCfg(key: string, label: string, extra: Record<string, unknown> = {}) {
  return { key, label, columnType: 'VARCHAR', length: 255, scale: null, required: false, unique: false, indexed: false, ...extra }
}

function mockRow(id: string) {
  return {
    id,
    data: { name: '华东石化', status: '运行中', owner: '张伟', phone: '0512-66881234' },
    version: 1,
    createdAt: '2026-10-11 09:00:00',
    updatedAt: '2026-10-11 10:00:00',
  }
}

beforeEach(() => {
  detailMock.mockReset()
  formDefMock.mockReset()
  formDefMock.mockResolvedValue({
    data: {
      columnConfig: JSON.stringify([
        columnCfg('name', '客户名称'),
        columnCfg('status', '客户状态'),
        columnCfg('owner', '负责人'),
        columnCfg('phone', '联系电话'),
        columnCfg('name_text', '客户名称（显示）', { hidden: true }),
      ]),
    },
  } as any)
})

describe('resolveRecordId — 关联记录 id 解析', () => {
  it('dataPicker 值（id JSON 数组）取第一个', () => {
    expect(resolveRecordId('["row-1"]')).toBe('row-1')
    expect(resolveRecordId('["a","b"]')).toBe('a')
  })
  it('纯 id 字符串 / 对象 .id / JSON 对象 / 带引号字符串', () => {
    expect(resolveRecordId('plain-id')).toBe('plain-id')
    expect(resolveRecordId({ id: 42 })).toBe('42')
    expect(resolveRecordId('{"id":"x"}')).toBe('x')
    expect(resolveRecordId('"quoted"')).toBe('quoted')
  })
  it('空值与不可解析返回 null', () => {
    expect(resolveRecordId(null)).toBeNull()
    expect(resolveRecordId('')).toBeNull()
    expect(resolveRecordId('[]')).toBeNull()
    expect(resolveRecordId('{broken')).toBeNull()
    expect(resolveRecordId({})).toBeNull()
  })
})

describe('RelationCard v2 — 实时模式', () => {
  function createLiveWrapper(props: any = {}) {
    return mount(RelationCard, {
      props: { modelValue: null, sourceFormKey: 'cust_form', ...props },
      global: { plugins: [ElementPlus] },
    })
  }

  it('挂载即按 id 拉取详情，多字段摘要渲染且 label 来自 columnConfig', async () => {
    detailMock.mockResolvedValue({ data: mockRow('row-1') } as any)
    const wrapper = createLiveWrapper({
      modelValue: '["row-1"]',
      displayFields: ['status', 'owner', 'phone'],
    })
    await flushPromises()
    expect(detailMock).toHaveBeenCalledWith('cust_form', 'row-1')
    expect(wrapper.find('.relation-card__badge').text()).toBe('实时')
    const fields = wrapper.findAll('.relation-card__field')
    expect(fields).toHaveLength(3)
    expect(fields[0].text()).toContain('客户状态')
    expect(fields[0].text()).toContain('运行中')
    expect(fields[1].text()).toContain('负责人')
    expect(fields[1].text()).toContain('张伟')
  })

  it('displayFields 留空自动取非空字段，且排除隐藏列与 _text 冗余列', async () => {
    detailMock.mockResolvedValue({ data: mockRow('row-1') } as any)
    const wrapper = createLiveWrapper({ modelValue: '["row-1"]' })
    await flushPromises()
    const labels = wrapper.findAll('.relation-card__field').map((f) => f.text())
    expect(labels.length).toBeLessThanOrEqual(4)
    expect(labels.join('|')).not.toContain('客户名称（显示）')
    expect(labels.join('|')).toContain('客户名称')
  })

  it('记录被删（404）显示警示占位；加载中显示骨架屏', async () => {
    detailMock.mockRejectedValue(new Error('request failed with status 404 not found'))
    const wrapper = createLiveWrapper({ modelValue: '["gone"]' })
    expect(wrapper.find('.relation-card__loading').exists()).toBe(true)
    await flushPromises()
    expect(wrapper.find('.relation-card__placeholder.is-warning').text()).toContain('关联记录不存在')
  })

  it('无 id 时不调详情接口，显示等待占位', async () => {
    const wrapper = createLiveWrapper({ modelValue: null })
    await flushPromises()
    expect(detailMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('等待关联记录')
  })

  it('记录 id 变化（数据引用字段改选）→ 重新拉取新记录', async () => {
    detailMock.mockResolvedValue({ data: mockRow('row-1') } as any)
    const wrapper = createLiveWrapper({ modelValue: '["row-1"]' })
    await flushPromises()
    detailMock.mockClear()
    detailMock.mockResolvedValue({ data: mockRow('row-2') } as any)
    await wrapper.setProps({ modelValue: '["row-2"]' })
    await flushPromises()
    expect(detailMock).toHaveBeenCalledWith('cust_form', 'row-2')
    expect(wrapper.text()).toContain('华东石化')
  })

  it('点击卡片：open-detail 携带记录 id + 打开内置详情弹窗（全字段 descriptions）', async () => {
    detailMock.mockResolvedValue({ data: mockRow('row-1') } as any)
    const wrapper = createLiveWrapper({ modelValue: '["row-1"]' })
    await flushPromises()
    await wrapper.find('.relation-card__body').trigger('click')
    expect(wrapper.emitted('open-detail')![0][0]).toBe('row-1')
    await flushPromises()
    const dialogText = document.body.textContent || ''
    expect(dialogText).toContain('详情')
    expect(dialogText).toContain('联系电话')
    expect(dialogText).toContain('0512-66881234')
    expect(dialogText).toContain('数据更新时间')
  })

  it('刷新按钮重新拉取当前记录', async () => {
    detailMock.mockResolvedValue({ data: mockRow('row-1') } as any)
    const wrapper = createLiveWrapper({ modelValue: '["row-1"]' })
    await flushPromises()
    expect(detailMock).toHaveBeenCalledTimes(1)
    await wrapper.find('.relation-card__refresh').trigger('click')
    await flushPromises()
    expect(detailMock).toHaveBeenCalledTimes(2)
    expect(detailMock).toHaveBeenLastCalledWith('cust_form', 'row-1')
  })

  it('被动模式不受影响：未配置 sourceFormKey 时不调详情接口', async () => {
    const wrapper = createWrapper({ modelValue: '纯文本值' })
    await flushPromises()
    expect(detailMock).not.toHaveBeenCalled()
    expect(wrapper.find('.relation-card__value').text()).toBe('纯文本值')
  })
})
