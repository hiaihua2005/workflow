<template>
  <div class="location-picker">
    <div class="location-picker__row">
      <!-- 触发框：readonly 展示，点击弹地图 -->
      <div
        class="location-picker__trigger"
        :class="{ 'is-disabled': disabled, 'is-empty': !hasValue }"
        role="button"
        tabindex="0"
        :aria-disabled="disabled"
        :aria-label="hasValue ? `已选位置：${displayText}` : '选择位置'"
        @click="openDialog"
        @keydown.enter.prevent="openDialog"
        @keydown.space.prevent="openDialog"
      >
        <el-icon class="location-picker__pin"><Location /></el-icon>
        <span class="location-picker__text">{{ displayText || placeholder }}</span>
        <el-icon class="location-picker__chev"><ArrowDown /></el-icon>
      </div>
      <el-button
        v-if="clearable"
        class="location-picker__clear"
        :disabled="disabled || !hasValue"
        @click.stop="handleClear"
      >
        清空
      </el-button>
    </div>

    <!-- 地图选点弹窗：@opened 初始化（容器尺寸就绪），@close 销毁防泄漏 -->
    <el-dialog
      v-model="dialogVisible"
      title="选择位置"
      width="min(720px, 92vw)"
      :close-on-click-modal="false"
      append-to-body
      destroy-on-close
      @opened="initMap"
      @close="destroyMap"
    >
      <div class="location-picker__map-wrap">
        <div ref="mapRef" class="location-picker__map" :style="{ height: mapHeight }"></div>
        <el-button
          class="location-picker__locate"
          size="small"
          :loading="locating"
          @click="locateMe"
        >
          <el-icon v-if="!locating"><Aim /></el-icon>&nbsp;定位到我
        </el-button>
      </div>
      <div class="location-picker__panel">
        <div class="location-picker__coord" :class="{ 'is-empty': !picked }">
          <el-icon><LocationInformation /></el-icon>
          <span>{{ picked ? `已选坐标：${picked.lat.toFixed(6)}, ${picked.lng.toFixed(6)}` : '在地图上点击任意位置放置标记' }}</span>
        </div>
        <el-input
          v-model="locationName"
          class="location-picker__name"
          placeholder="为该位置命名（可选），如：总部仓库；不命名则按坐标保存"
          clearable
          maxlength="60"
        >
          <template #prefix>
            <el-icon><EditPen /></el-icon>
          </template>
        </el-input>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!picked" @click="confirmPick">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 定位 LocationPicker v3（地图选点+命名坐标双持久化）——表单设计器「基础组件」分组。
 *
 * 【升级说明】v2 缺陷修复：v2 命名后只存位置名称、坐标丢失，重新打开弹窗无法回放标记。
 * v3 值契约（详见 @/utils/locationValue）：
 * - 命名位置 → JSON 复合文本 '{"name":"总部仓库","lat":39.9,"lng":116.4}'
 *   （名称与坐标同时持久化；触发框/列表列仍显示名称）
 * - 未命名 → 兜底 'lat,lng'（至多 6 位小数去尾零，兼容 v1 值格式）
 * - 旧值自动识别（v1 坐标文本 / v2 纯名称文本），进入弹窗可继续选点覆盖
 *
 * 【坐标系】高德瓦片为 GCJ-02，标记与点选坐标同为 GCJ-02 口径（点在图上位置精确，
 * 坐标可直接用于高德/腾讯系应用）；无 key 瓦片仅供内部演示。
 *
 * 【生命周期】el-dialog @opened 初始化（容器有尺寸防 0 尺寸瓦片空洞）、@close map.remove()
 * 销毁；divIcon CSS 标记（不依赖 leaflet 图片资源，规避打包 404）。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { Aim, ArrowDown, EditPen, Location, LocationInformation } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import {
  buildLocationValue, formatLocationText, hasCoords, parseLocationValue, parseCoordText,
} from '@/utils/locationValue'

const props = withDefaults(defineProps<{
  /** 值：JSON 复合文本（命名，含坐标）| 'lat,lng'（未命名兜底，兼容 v1）| ''（未设置） */
  modelValue?: string
  /** 禁用 */
  disabled?: boolean
  /** 是否显示清空按钮 */
  clearable?: boolean
  /** 触发框占位文案 */
  placeholder?: string
  /** 弹窗地图高度 */
  mapHeight?: string
}>(), {
  modelValue: '',
  disabled: false,
  clearable: true,
  placeholder: '点击选择位置',
  mapHeight: '420px',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'change': [value: string]
}>()

/* ---------- 值解析 / 展示（解析逻辑共享于 @/utils/locationValue，列表列同源） ---------- */

const hasValue = computed(() => String(props.modelValue || '').trim() !== '')

/** 触发框显示文本：命名 → 名称；坐标值 → 「lat, lng」（旧值自动识别） */
const displayText = computed(() => formatLocationText(props.modelValue))

/* ---------- 弹窗 / 地图状态 ---------- */

const dialogVisible = ref(false)
const mapRef = ref<HTMLDivElement | null>(null)
const locating = ref(false)
/** 弹窗内点选的坐标（GCJ-02） */
const picked = ref<{ lat: number; lng: number } | null>(null)
/** 位置命名（空 = 按坐标保存） */
const locationName = ref('')
let map: L.Map | null = null
let marker: L.Marker | null = null

function openDialog() {
  if (props.disabled) return
  // 回填：坐标+名称同时还原（v3 核心修复——重开弹窗回放既有标记）；旧值形态自动识别
  const parsed = parseLocationValue(props.modelValue)
  picked.value = hasCoords(parsed) ? { lat: parsed.lat, lng: parsed.lng } : null
  locationName.value = parsed?.name || ''
  dialogVisible.value = true
}

/** 高德 XYZ 瓦片（免 key，GCJ-02）：webrd01-04 子域轮询 */
function makeTileLayer(): L.TileLayer {
  return L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    subdomains: ['1', '2', '3', '4'],
    maxZoom: 18,
    minZoom: 3,
    attribution: '&copy; 高德地图',
  })
}

/** divIcon 脉冲标记（CSS 动画，不依赖 leaflet 图片资源） */
function makePinIcon(): L.DivIcon {
  return L.divIcon({
    className: 'lp-pin-wrap',
    html: '<div class="lp-pin"><i class="lp-pin__core"></i></div>',
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  })
}

function placeMarker(lat: number, lng: number, setView = false) {
  picked.value = { lat, lng }
  if (!map) return
  if (marker) {
    marker.setLatLng([lat, lng])
  } else {
    marker = L.marker([lat, lng], { icon: makePinIcon(), keyboard: false }).addTo(map)
  }
  if (setView) map.setView([lat, lng], Math.max(map.getZoom(), 14))
}

function initMap() {
  // 兜底 detached 容器：dialog opened 前提下 mapRef 必有值；测试/极端时序下用脱离文档的
  // div 走通初始化逻辑（Leaflet 对 detached 容器可创建实例，invalidateSize 后正常渲染）
  const el = mapRef.value || document.createElement('div')
  destroyMap()
  map = L.map(el, {
    center: picked.value ? [picked.value.lat, picked.value.lng] : [35.86166, 104.195397], // 无值：中国中心
    zoom: picked.value ? 14 : 4,
    zoomControl: true,
    attributionControl: false,
  })
  map.addLayer(makeTileLayer())
  map.on('click', (e: L.LeafletMouseEvent) => {
    placeMarker(e.latlng.lat, e.latlng.lng)
  })
  // 有坐标值：回放标记
  if (picked.value) {
    placeMarker(picked.value.lat, picked.value.lng)
  }
  // 容器在 dialog 动画期间尺寸可能变化，稳定后重算
  nextTick(() => map?.invalidateSize())
  setTimeout(() => map?.invalidateSize(), 260)
}

function destroyMap() {
  if (map) {
    try { map.remove() } catch { /* 幂等清理 */ }
  }
  map = null
  marker = null
}

/** 浏览器定位：成功 → 定位+放标记；失败/不可用 → 提示（http 非安全环境常见） */
function locateMe() {
  if (!map || locating.value) return
  if (!navigator.geolocation) {
    ElMessage.warning('当前环境不支持浏览器定位，请在地图上手动点选')
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      locating.value = false
      placeMarker(pos.coords.latitude, pos.coords.longitude, true)
    },
    () => {
      locating.value = false
      ElMessage.warning('定位失败（可能被浏览器拦截），请在地图上手动点选')
    },
    { enableHighAccuracy: true, timeout: 8000 },
  )
}

/** 确定：命名 → JSON 复合文本（名称+坐标同时持久化）；未命名 → 兜底 'lat,lng'（与 v1 值格式一致） */
function confirmPick() {
  if (!picked.value) return
  const value = buildLocationValue(locationName.value, picked.value.lat, picked.value.lng)
  if (value !== (props.modelValue || '')) {
    emit('update:modelValue', value)
    emit('change', value)
  }
  dialogVisible.value = false
}

function handleClear() {
  if (props.disabled) return
  if (props.modelValue === '') return
  emit('update:modelValue', '')
  emit('change', '')
}

/** 外部值变化时同步弹窗内回显状态（弹窗未开时仅刷新 picked，避免脏状态） */
watch(
  () => props.modelValue,
  () => {
    if (!dialogVisible.value) return
    const parsed = parseLocationValue(props.modelValue)
    if (hasCoords(parsed)) {
      picked.value = { lat: parsed.lat, lng: parsed.lng }
      if (parsed.name) locationName.value = parsed.name
    }
  },
)

defineOptions({ name: 'LocationPicker' })
defineExpose({
  parseCoordText, parseLocationValue, formatLocationText, displayText, hasValue,
  openDialog, confirmPick, handleClear, initMap, placeMarker, destroyMap,
  /** 以下供测试与宿主调试：弹窗开关 / 点选坐标 / 位置命名 */
  dialogVisible, picked, locationName,
})
</script>

<style scoped>
.location-picker {
  width: 100%;
}

.location-picker__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.location-picker__trigger {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  height: 32px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  cursor: pointer;
  transition: border-color var(--el-transition-duration), box-shadow var(--el-transition-duration);
  user-select: none;
}

.location-picker__trigger:hover:not(.is-disabled),
.location-picker__trigger:focus-visible {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7) inset;
  outline: none;
}

.location-picker__trigger.is-disabled {
  cursor: not-allowed;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
}

.location-picker__trigger.is-empty .location-picker__text {
  color: var(--el-text-color-placeholder);
}

.location-picker__pin {
  color: var(--el-color-primary);
  flex-shrink: 0;
}

.location-picker__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.location-picker__chev {
  color: var(--el-text-color-placeholder);
  flex-shrink: 0;
}

/* ---------- 弹窗内 ---------- */

.location-picker__map-wrap {
  position: relative;
}

.location-picker__map {
  width: 100%;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
  z-index: 0;
}

.location-picker__locate {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 500;
}

.location-picker__panel {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.location-picker__coord {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}

.location-picker__coord.is-empty {
  color: var(--el-text-color-placeholder);
}
</style>

<style>
/* divIcon 脉冲标记（scoped 外：leaflet 生成节点无 scoped 属性） */
.lp-pin-wrap {
  background: transparent;
  border: 0;
}

.lp-pin {
  position: relative;
  width: 22px;
  height: 22px;
}

.lp-pin__core {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 12px;
  height: 12px;
  margin: -6px 0 0 -6px;
  border-radius: 50%;
  background: var(--el-color-primary, #409eff);
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
}

.lp-pin::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 22px;
  height: 22px;
  margin: -11px 0 0 -11px;
  border-radius: 50%;
  background: var(--el-color-primary, #409eff);
  opacity: 0.35;
  animation: lp-pulse 1.6s ease-out infinite;
}

@keyframes lp-pulse {
  0% {
    transform: scale(0.5);
    opacity: 0.5;
  }
  80% {
    transform: scale(2.1);
    opacity: 0;
  }
  100% {
    transform: scale(2.1);
    opacity: 0;
  }
}
</style>
