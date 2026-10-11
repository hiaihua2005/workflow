// ----- LocationPicker v3（地图选点+命名坐标双持久化）组件测试 -----
// npx vitest run src/components/business/__tests__/LocationPicker.test.ts
//
// 覆盖：触发框渲染（placeholder/名称/坐标/JSON 复合值回显）、openDialog 门控与回填、
// initMap 地图初始化（mock leaflet：map/tileLayer/divIcon/marker）、地图 click 放标记、
// confirmPick 值发射（命名 → JSON 复合文本 name+lat+lng 双持久化；未命名 → 'lat,lng' 兜底）、
// 往返持久化（保存值回灌新实例 → 重开弹窗坐标+名称同时还原——v3 核心修复）、
// 清空、v1 坐标值/v2 纯名称旧值兼容、locationValue 工具全历史形态解析。
// leaflet 全模块 mock（jsdom 无真实 canvas/瓦片）；leaflet.css 由 vitest 自动置空。

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// leaflet mock：链式 stub，记录调用供断言
const tileLayerStub = {
  addTo: vi.fn().mockReturnThis(),
}
const markerStub = {
  addTo: vi.fn().mockReturnThis(),
  setLatLng: vi.fn().mockReturnThis(),
}
const mapStub = {
  addLayer: vi.fn(),
  on: vi.fn(),
  setView: vi.fn().mockReturnThis(),
  getZoom: vi.fn().mockReturnValue(4),
  remove: vi.fn(),
  invalidateSize: vi.fn(),
}
vi.mock('leaflet', () => ({
  default: {
    map: vi.fn(() => mapStub),
    tileLayer: vi.fn(() => tileLayerStub),
    divIcon: vi.fn(() => ({ el: document.createElement('div') })),
    marker: vi.fn(() => markerStub),
  },
}))
import L from 'leaflet'
import LocationPicker from '../LocationPicker.vue'
import {
  parseLocationValue, formatLocationText, buildLocationValue, hasCoords, parseCoordText, trimCoord,
} from '@/utils/locationValue'

function createWrapper(props: any = {}) {
  return mount(LocationPicker, {
    props: {
      modelValue: '',
      ...props,
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('LocationPicker — 触发框渲染与值回显', () => {
  it('空值显示 placeholder 与「点击选择位置」占位，样式 is-empty', () => {
    const wrapper = createWrapper()
    const trigger = wrapper.find('.location-picker__trigger')
    expect(trigger.exists()).toBe(true)
    expect(trigger.classes()).toContain('is-empty')
    expect(trigger.find('.location-picker__text').text()).toBe('点击选择位置')
    // 清空按钮在无值时禁用
    expect(wrapper.find('.location-picker__clear').attributes('disabled')).toBeDefined()
  })

  it('v3 JSON 复合值显示位置名称（组件默认显示名称，坐标藏在值里）', () => {
    const wrapper = createWrapper({ modelValue: '{"name":"石化一号仓库","lat":39.9042,"lng":116.4074}' })
    expect(wrapper.find('.location-picker__trigger .location-picker__text').text()).toBe('石化一号仓库')
    expect(wrapper.vm.displayText).toBe('石化一号仓库')
  })

  it('v2 纯名称旧值兼容：原样显示（坐标已丢失，进入弹窗可重新选点）', () => {
    const wrapper = createWrapper({ modelValue: '总部仓库' })
    expect(wrapper.vm.displayText).toBe('总部仓库')
  })

  it('v1 坐标值兼容：显示为格式化坐标文本', () => {
    const wrapper = createWrapper({ modelValue: '39.904200,116.407400' })
    expect(wrapper.vm.displayText).toBe('39.9042, 116.4074')
  })

  it('未命名坐标兜底值显示坐标文本', () => {
    const wrapper = createWrapper({ modelValue: '31.2304,121.4737' })
    expect(wrapper.vm.displayText).toBe('31.2304, 121.4737')
  })

  it('disabled 时触发框不可点（is-disabled），清空按钮禁用', async () => {
    const wrapper = createWrapper({ modelValue: '总部仓库', disabled: true })
    await wrapper.find('.location-picker__trigger').trigger('click')
    expect((wrapper.vm as any).dialogVisible).toBe(false)
    expect(wrapper.find('.location-picker__clear').attributes('disabled')).toBeDefined()
  })

  it('clearable=false 不渲染清空按钮', () => {
    const wrapper = createWrapper({ clearable: false })
    expect(wrapper.find('.location-picker__clear').exists()).toBe(false)
  })
})

describe('LocationPicker — 清空', () => {
  it('有值时清空：emit update:modelValue + change 空串', async () => {
    const wrapper = createWrapper({ modelValue: '总部仓库' })
    await wrapper.vm.handleClear()
    expect(wrapper.emitted('update:modelValue')![0]).toEqual([''])
    expect(wrapper.emitted('change')![0]).toEqual([''])
  })

  it('无值时清空不发射（幂等守卫）', async () => {
    const wrapper = createWrapper()
    await wrapper.vm.handleClear()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})

describe('LocationPicker — 弹窗与地图（leaflet mock）', () => {
  it('openDialog 回填：v3 JSON 值 → 坐标+名称同时还原（★ v3 核心修复）', () => {
    const wrapper = createWrapper({ modelValue: '{"name":"石化一号仓库","lat":39.9042,"lng":116.4074}' })
    wrapper.vm.openDialog()
    expect(wrapper.vm.picked).toEqual({ lat: 39.9042, lng: 116.4074 })
    expect(wrapper.vm.locationName).toBe('石化一号仓库')
  })

  it('openDialog 回填：旧值形态自动识别（坐标值 → picked 定位；纯名称 → 名称回显无 picked）', () => {
    const w1 = createWrapper({ modelValue: '39.9,116.4' })
    w1.vm.openDialog()
    expect(w1.vm.picked).toEqual({ lat: 39.9, lng: 116.4 })
    expect(w1.vm.locationName).toBe('')

    const w2 = createWrapper({ modelValue: '总部仓库' })
    w2.vm.openDialog()
    expect(w2.vm.picked).toBeNull()
    expect(w2.vm.locationName).toBe('总部仓库')
  })

  it('initMap：L.map 初始化 + 高德瓦片 addTo + click 监听注册；已有坐标值创建 divIcon 标记（重开回放标记）', () => {
    const wrapper = createWrapper({ modelValue: '39.9,116.4' })
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    expect(L.map).toHaveBeenCalledTimes(1)
    expect(L.map).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ zoom: 14 }))
    expect(L.tileLayer).toHaveBeenCalledWith(
      expect.stringContaining('webrd0{s}.is.autonavi.com'),
      expect.objectContaining({ subdomains: ['1', '2', '3', '4'] }),
    )
    expect(mapStub.addLayer).toHaveBeenCalledWith(tileLayerStub)
    expect(mapStub.on).toHaveBeenCalledWith('click', expect.any(Function))
    expect(L.marker).toHaveBeenCalledWith([39.9, 116.4], expect.objectContaining({ icon: expect.anything() }))
    expect(markerStub.addTo).toHaveBeenCalledWith(mapStub)
    wrapper.vm.destroyMap?.()
    expect(mapStub.remove).toHaveBeenCalled()
  })

  it('initMap：v3 JSON 值重开弹窗 → 按已存坐标回放标记（★ 持久化回归）', () => {
    const wrapper = createWrapper({ modelValue: '{"name":"石化一号仓库","lat":39.9042,"lng":116.4074}' })
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    expect(L.map).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ zoom: 14 }))
    expect(L.marker).toHaveBeenCalledWith([39.9042, 116.4074], expect.objectContaining({ icon: expect.anything() }))
    wrapper.vm.destroyMap?.()
  })

  it('initMap 无值：中国中心 + zoom 4，不创建标记', () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    expect(L.map).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ zoom: 4 }))
    expect(L.marker).not.toHaveBeenCalled()
    wrapper.vm.destroyMap?.()
  })

  it('地图 click 回调：placeMarker 更新 picked，首次点击创建新标记', () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304, lng: 121.4737 } })
    expect(wrapper.vm.picked).toEqual({ lat: 31.2304, lng: 121.4737 })
    expect(L.marker).toHaveBeenCalledWith([31.2304, 121.4737], expect.objectContaining({ icon: expect.anything() }))
  })

  it('地图 click 回调：已有标记时复用 setLatLng（不重复创建）', () => {
    const wrapper = createWrapper({ modelValue: '39.9,116.4' })
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    expect(L.marker).toHaveBeenCalledTimes(1) // 初始回放标记
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304, lng: 121.4737 } })
    expect(markerStub.setLatLng).toHaveBeenCalledWith([31.2304, 121.4737])
    expect(L.marker).toHaveBeenCalledTimes(1) // 未重复创建
  })

  it('confirmPick：命名 → JSON 复合文本（名称+坐标同时持久化，v3 核心契约）', async () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304, lng: 121.4737 } })
    wrapper.vm.locationName = '上海仓库'
    wrapper.vm.confirmPick()
    const emitted = wrapper.emitted('update:modelValue')![0][0] as string
    expect(JSON.parse(emitted)).toEqual({ name: '上海仓库', lat: 31.2304, lng: 121.4737 })
    expect(wrapper.emitted('change')![0][0]).toBe(emitted)
  })

  it('confirmPick：未命名 → 兜底保存「lat,lng」（与 v1 值格式一致，6 位去尾零）', async () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304123456, lng: 121.4737 } })
    wrapper.vm.confirmPick()
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['31.230412,121.4737'])
  })

  it('confirmPick：未选点时确定守卫（不发射、不关弹窗）', () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.confirmPick()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('往返持久化（★ v3 修复验收）：选点+命名 → 确定值回灌新实例 → 重开弹窗坐标+名称同时还原', () => {
    // 第一步：选点 + 命名 + 确定
    const source = createWrapper()
    source.vm.openDialog()
    source.vm.initMap()
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304, lng: 121.4737 } })
    source.vm.locationName = '石化一号仓库'
    source.vm.confirmPick()
    const saved = source.emitted('update:modelValue')![0][0] as string
    expect(JSON.parse(saved)).toEqual({ name: '石化一号仓库', lat: 31.2304, lng: 121.4737 })
    // 第二步：模拟表单数据保存后重新打开（新实例携带已存值）
    const reopened = createWrapper({ modelValue: saved })
    expect(reopened.vm.displayText).toBe('石化一号仓库')
    reopened.vm.openDialog()
    expect(reopened.vm.picked).toEqual({ lat: 31.2304, lng: 121.4737 })
    expect(reopened.vm.locationName).toBe('石化一号仓库')
  })

  it('destroyMap 幂等：未初始化时调用不抛错', () => {
    const wrapper = createWrapper()
    expect(() => (wrapper.vm as any).destroyMap?.()).not.toThrow()
  })
})

describe('locationValue 工具 — 全历史形态解析/格式化', () => {
  it('parseLocationValue：JSON 复合文本 → 名称+坐标；对象值容错；空值 null', () => {
    expect(parseLocationValue('{"name":"总部","lat":39.9,"lng":116.4}')).toEqual({ name: '总部', lat: 39.9, lng: 116.4 })
    expect(parseLocationValue({ name: '对象值', lat: '40.1', lng: '117.2' })).toEqual({ name: '对象值', lat: 40.1, lng: 117.2 })
    expect(parseLocationValue('')).toBeNull()
    expect(parseLocationValue(null)).toBeNull()
  })

  it('parseLocationValue：坐标文本 / 纯名称旧值 / 非法 JSON 兜底', () => {
    expect(parseLocationValue('39.9,116.4')).toEqual({ name: '', lat: 39.9, lng: 116.4 })
    expect(parseLocationValue('总部仓库')).toEqual({ name: '总部仓库', lat: null, lng: null })
    // 非法 JSON 文本 → 纯名称兜底（不抛错）
    expect(parseLocationValue('{"name":残缺')).toEqual({ name: '{"name":残缺', lat: null, lng: null })
    // JSON 但缺坐标 → 纯名称兜底
    expect(parseLocationValue('{"name":"无坐标"}')).toEqual({ name: '{"name":"无坐标"}', lat: null, lng: null })
    expect(hasCoords(parseLocationValue('总部仓库'))).toBe(false)
    expect(hasCoords(parseLocationValue('39.9,116.4'))).toBe(true)
  })

  it('parseCoordText：中英文逗号/空白分隔、范围校验', () => {
    expect(parseCoordText('39.9,116.4')).toEqual([39.9, 116.4])
    expect(parseCoordText('39.9， 116.4')).toEqual([39.9, 116.4])
    expect(parseCoordText('91,116.4')).toBeNull() // 纬度越界
    expect(parseCoordText('39.9')).toBeNull()
  })

  it('buildLocationValue：命名 → JSON 文本（6 位去尾零）；未命名 → lat,lng', () => {
    expect(buildLocationValue('仓库', 39.9042123, 116.4)).toBe('{"name":"仓库","lat":39.904212,"lng":116.4}')
    expect(buildLocationValue('', 39.9, 116.4)).toBe('39.9,116.4')
    expect(buildLocationValue('  ', 39.9, 116.4)).toBe('39.9,116.4')
  })

  it('formatLocationText：命名 → 名称；坐标 → 坐标文本；非法 → 原文透传；空 → 空串', () => {
    expect(formatLocationText('{"name":"总部","lat":39.9,"lng":116.4}')).toBe('总部')
    expect(formatLocationText('39.904200,116.407400')).toBe('39.9042, 116.4074')
    expect(formatLocationText('总部仓库')).toBe('总部仓库')
    expect(formatLocationText('')).toBe('')
    expect(trimCoord(39.904200)).toBe('39.9042')
  })
})
