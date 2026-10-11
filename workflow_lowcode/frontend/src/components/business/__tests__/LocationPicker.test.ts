// ----- LocationPicker v2（弹窗地图选点版）组件测试 -----
// npx vitest run src/components/business/__tests__/LocationPicker.test.ts
//
// 覆盖：触发框渲染（placeholder/名称值/坐标值回显）、openDialog 门控与回填、
// initMap 地图初始化（mock leaflet：map/tileLayer/divIcon/marker）、地图 click 放标记、
// confirmPick 值发射（命名 → 名称；未命名 → 'lat,lng' 兜底）、清空、v1 坐标值兼容。
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

  it('名称值原样显示（组件默认显示位置名称）', () => {
    const wrapper = createWrapper({ modelValue: '总部仓库' })
    expect(wrapper.find('.location-picker__trigger .location-picker__text').text()).toBe('总部仓库')
    expect(wrapper.vm.displayText).toBe('总部仓库')
  })

  it('v1 坐标值兼容：显示为格式化坐标文本', () => {
    const wrapper = createWrapper({ modelValue: '39.904200,116.407400' })
    expect(wrapper.vm.displayText).toBe('39.9042, 116.4074')
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
  it('openDialog 打开弹窗并回填：坐标值 → picked 定位、名称框空；名称值 → 名称回显、无 picked', () => {
    const w1 = createWrapper({ modelValue: '39.9,116.4' })
    w1.vm.openDialog()
    expect(w1.vm.picked).toEqual({ lat: 39.9, lng: 116.4 })
    expect(w1.vm.locationName).toBe('')

    const w2 = createWrapper({ modelValue: '总部仓库' })
    w2.vm.openDialog()
    expect(w2.vm.picked).toBeNull()
    expect(w2.vm.locationName).toBe('总部仓库')
  })

  it('initMap：L.map 初始化 + 高德瓦片 addTo + click 监听注册；已有坐标值创建 divIcon 标记', () => {
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

  it('confirmPick：命名 → 保存位置名称（组件默认显示名称）', async () => {
    const wrapper = createWrapper()
    wrapper.vm.openDialog()
    wrapper.vm.initMap()
    const clickHandler = mapStub.on.mock.calls.find((c: any[]) => c[0] === 'click')![1]
    clickHandler({ latlng: { lat: 31.2304, lng: 121.4737 } })
    wrapper.vm.locationName = '上海仓库'
    wrapper.vm.confirmPick()
    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['上海仓库'])
    expect(wrapper.emitted('change')![0]).toEqual(['上海仓库'])
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

  it('destroyMap 幂等：未初始化时调用不抛错', () => {
    const wrapper = createWrapper()
    expect(() => (wrapper.vm as any).destroyMap?.()).not.toThrow()
  })
})
