<template>
  <div class="relation-card">
    <el-card
      class="relation-card__body"
      :class="{ 'is-clickable': isClickable, 'is-empty': isEmptyState, 'is-live': isLiveMode }"
      :shadow="isClickable ? 'hover' : 'never'"
      @click="handleClick"
    >
      <div class="relation-card__header">
        <el-icon class="relation-card__icon"><Connection /></el-icon>
        <span class="relation-card__title">{{ title || '关联记录' }}</span>
        <el-tag v-if="isLiveMode && liveState === 'ready'" class="relation-card__badge" size="small" type="success" effect="plain" round>实时</el-tag>
        <el-button
          v-if="isLiveMode && liveState === 'ready'"
          class="relation-card__refresh"
          link
          size="small"
          @click.stop="reload"
        >
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-icon v-if="isClickable" class="relation-card__arrow"><ArrowRight /></el-icon>
      </div>

      <!-- ===== 实时模式：按记录 id 实时拉取源表单数据 ===== -->
      <template v-if="isLiveMode">
        <div v-if="liveState === 'loading'" class="relation-card__loading">
          <el-skeleton :rows="2" animated />
        </div>
        <div v-else-if="liveState === 'missing'" class="relation-card__placeholder is-warning">
          <el-icon><WarningFilled /></el-icon>
          <span>关联记录不存在或已删除</span>
        </div>
        <div v-else-if="liveState === 'error'" class="relation-card__placeholder is-warning">
          <el-icon><WarningFilled /></el-icon>
          <span>关联数据加载失败</span>
        </div>
        <div v-else-if="liveState === 'ready'" class="relation-card__fields">
          <div v-for="col in liveDisplayColumns" :key="col.key" class="relation-card__field">
            <span class="relation-card__field-label">{{ col.label }}</span>
            <span class="relation-card__field-value" :title="formatLiveCell(col.key)">{{ formatLiveCell(col.key) }}</span>
          </div>
        </div>
        <div v-else class="relation-card__placeholder">
          <el-icon><InfoFilled /></el-icon>
          <span>等待关联记录（选择数据后实时展示）</span>
        </div>
      </template>

      <!-- ===== 被动模式（v1 兼容）：字符串/对象值摘要直显 ===== -->
      <template v-else>
        <div v-if="hasValue" class="relation-card__value" :title="summaryText">
          {{ summaryText }}
        </div>
        <div v-else class="relation-card__placeholder">
          <el-icon><InfoFilled /></el-icon>
          <span>未选择关联记录</span>
        </div>
      </template>

      <div v-if="description" class="relation-card__desc">{{ description }}</div>
    </el-card>

    <!-- 实时模式详情弹窗：全字段 descriptions（passive 模式不内置弹窗，保持 v1 契约） -->
    <el-dialog
      v-model="dialogVisible"
      :title="`${title || '关联记录'} · 详情`"
      width="560px"
      append-to-body
      destroy-on-close
    >
      <el-skeleton v-if="liveState === 'loading'" :rows="4" animated />
      <template v-else-if="liveRow">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item v-for="col in detailColumns" :key="col.key" :label="col.label">
            {{ formatLiveCell(col.key) }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="liveRow.updatedAt" class="relation-card__meta">数据更新时间：{{ liveRow.updatedAt }}</div>
      </template>
      <el-empty v-else description="暂无关联记录数据" :image-size="72" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 关联记录卡片 RelationCard v2——表单设计器「基础组件」分组。
 *
 * 【v2 实时模式（本组件的核心价值）】
 * 配置来源后，卡片不再是值的被动显示器，而是「活的关联记录视图」：
 * - 来源三级解析（对齐 DataPicker 契约）：
 *   ① dataSourceId（页面级数据源绑定 id）→ activeDsBindings 模块存储解析 refId
 *   ② globalDataSourceId（全局数据源 id 直连，不依赖页面绑定上下文）
 *   ③ sourceFormKey（旧版业务表单 key 直连，兼容回退）
 *   数据源模式下经统一数据访问 SPI（DataSourceAdapter：FORM/WORKFLOW/SYSTEM/API/SQL
 *   五类型均支持单条 getById + metadata 列定义），可关联非业务表单数据
 *   （系统用户/部门/岗位/流程实例/外部 API/SQL）
 * - 监听记录 id（可指向 dataPicker 数据引用字段本身——其值为 id JSON 数组；
 *   或 LookupPicker 的 idField 独立字段；兼容对象值 .id）
 * - id 变化 → 数据源模式 dataSourceApi.getData(refId,id) / 表单模式 bizDataApi.detail(formKey,id)
 *   实时拉取最新整行（非快照，源数据变更后卡片始终展示最新值——这是它与
 *   LookupPicker 带回显示文本的本质区别：不冗余存储、永远最新）
 * - 卡片正文多字段摘要（displayFields 配置；留空自动取前 4 个非空可见字段），
 *   字段中文 label 自动映射：数据源模式取 metadata.columns（同 ColumnConfigItem 形），
 *   表单模式取源表单 columnConfig（getFormDefinitionByKey）
 * - 点击卡片 → 内置详情弹窗（全字段 descriptions + 更新时间），同时 emit('open-detail')
 * - 右上角刷新按钮手动重拉；记录被删 → 「关联记录不存在」警示态
 *
 * 【v1 被动模式（完全保留，向后兼容）】
 * 未配置 sourceFormKey 时行为与 v1 逐字一致：值形态对齐 LookupPicker
 * （显示文本字符串 / 整行对象），displayField 深层取值 + 常用键回退；
 * clickable 点击仅 emit('open-detail')，不内置弹窗（宿主决定打开方式）。
 */
import { computed, getCurrentInstance, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, Connection, InfoFilled, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { readCellValue, resolveRecordId } from './lookupFetch'
import { bizDataApi, type BizDataVO } from '@/api/bizData'
import { dataSourceApi } from '@/api/data-source'
import { formApi } from '@/api/form'
import type { ColumnConfigItem } from '@/api/bizData'
import { activeDsBindings } from '@/utils/formDsBindingsStore'
import { formatLocationText } from '@/utils/locationValue'

interface FormCreateInject {
  api?: {
    getValue?: (field: string) => unknown
    setValue?: (field: string, value: unknown) => void
  }
  [key: string]: unknown
}

const props = withDefaults(defineProps<{
  /** 关联记录值（被动模式）：显示文本字符串 / 整行对象 / 空值（对齐 LookupPicker 值形态） */
  modelValue?: any
  /** 卡片标题行文案（form-create 字段 label 在卡片外，此为卡内标题） */
  title?: string
  /** 说明文字（值摘要下方的次级说明） */
  description?: string
  /** 被动模式：对象值取显示字段（深层取值，未配置回退 name/title/label/id） */
  displayField?: string
  /** 实时模式（旧版回退）：业务表单 key（未配置数据源时启用表单直连取数） */
  sourceFormKey?: string
  /** 实时模式：页面级数据源绑定 id（经 activeDsBindings 解析为全局 refId，优先级最高） */
  dataSourceId?: string
  /** 实时模式：全局数据源 id 直连（不依赖页面绑定上下文；优先于 sourceFormKey） */
  globalDataSourceId?: string
  /** 实时模式：记录 id 所在字段（dataPicker 字段=值本身为 id 数组；或 LookupPicker idField 回写目标字段） */
  recordIdField?: string
  /** 实时模式：摘要展示字段（数组或逗号分隔；留空自动取前 4 个非空可见字段） */
  displayFields?: string | string[]
  /** 是否可点击查看详情 */
  clickable?: boolean
}>(), {
  modelValue: null,
  title: '',
  description: '',
  displayField: '',
  sourceFormKey: '',
  dataSourceId: '',
  globalDataSourceId: '',
  recordIdField: '',
  displayFields: '',
  clickable: true,
})

const emit = defineEmits<{
  /** 点击卡片请求打开详情（携带原始值/记录 id） */
  'open-detail': [value: any]
}>()

// ===== form-create 注入（与 LookupPicker 同款双通道：prop 优先，inject 兜底） =====
const providedFormCreateInject = inject<FormCreateInject | undefined>('formCreateInject', undefined)
const instance = getCurrentInstance()
const formCreateInject = computed<FormCreateInject | undefined>(() => {
  const fromProp = (instance?.proxy?.$props as any)?.formCreateInject
  if (fromProp) return fromProp as FormCreateInject
  return providedFormCreateInject
})

// ===== 模式判定（来源三级解析：页面绑定 > 全局数据源 > 业务表单，对齐 DataPicker）=====
/** 页面级数据源绑定上下文（dataSourceId → activeDsBindings 模块存储解析） */
const currentBinding = computed(() => {
  if (!props.dataSourceId) return undefined
  return activeDsBindings.value.find((b: { id: string; refId: string }) => b.id === props.dataSourceId)
})

/** 生效数据源 refId：页面绑定 refId > 全局直连 id > 空（回退 sourceFormKey 表单直连） */
const dsRefId = computed(() => currentBinding.value?.refId || props.globalDataSourceId || '')

const isLiveMode = computed(() => Boolean(props.sourceFormKey || dsRefId.value))

/** 被动模式：是否有值（空串 / null / undefined 视为未选择） */
const hasValue = computed(() => {
  const v = props.modelValue
  return v !== null && v !== undefined && v !== ''
})

// ===== 记录 id 解析（数据引用字段值=JSON 数组 / 独立 id 字段=字符串 / 对象 .id） =====
// 注意：api.getValue 为非响应式读取，本 computed 仅负责「挂载时初值」（编辑页回显）；
// 后续跨字段变更由下方 api.on('change') 事件驱动（form-create core setValue 时 emit('change', field, value, ...)）。
const recordId = computed<string | null>(() => {
  if (!isLiveMode.value) return null
  const api = formCreateInject.value?.api
  const raw = (props.recordIdField && api?.getValue)
    ? api.getValue(props.recordIdField)
    : props.modelValue
  return resolveRecordId(raw)
})

// ===== 跨字段 id 变更监听 =====
let offFieldChange: (() => void) | null = null
function bindFieldChangeWatcher() {
  const api = formCreateInject.value?.api as { on?: Function; off?: Function; getValue?: (field: string) => unknown } | undefined
  if (!api || typeof api.on !== 'function' || !props.recordIdField || offFieldChange) return
  const handler = (...args: any[]) => {
    const field = args[0]
    if (typeof field !== 'string' || field !== props.recordIdField) return
    const raw = args.length > 1 ? args[1] : api.getValue?.(props.recordIdField)
    loadLive(resolveRecordId(raw))
  }
  try {
    api.on('change', handler)
    offFieldChange = () => {
      try { api.off?.('change', handler) } catch { /* 事件总线不可用时静默 */ }
    }
  } catch {
    /* 非 form-create 环境（纯 prop 用法）无需订阅 */
  }
}
onMounted(bindFieldChangeWatcher)
watch(formCreateInject, bindFieldChangeWatcher)
onBeforeUnmount(() => {
  offFieldChange?.()
  offFieldChange = null
})

// ===== 实时取数 =====
type LiveState = 'idle' | 'loading' | 'ready' | 'missing' | 'error'
const liveState = ref<LiveState>('idle')
const liveRow = ref<BizDataVO | null>(null)
const columnsByKey = ref<Record<string, ColumnConfigItem>>({})
const columnsLoadedFor = ref('')

/** 拉取记录详情（id 为空 → 回到等待态）；数据源模式 getData / 表单模式 detail，返回同形 BizDataVO */
async function loadLive(id: string | null) {
  if (!isLiveMode.value || !id) {
    liveRow.value = null
    liveState.value = 'idle'
    return
  }
  liveState.value = 'loading'
  try {
    const res: any = dsRefId.value
      ? await dataSourceApi.getData(dsRefId.value, id)
      : await bizDataApi.detail(props.sourceFormKey, id)
    const row = res?.data ?? res ?? null
    liveRow.value = row && typeof row === 'object' ? row : null
    liveState.value = liveRow.value ? 'ready' : 'missing'
  } catch (e: any) {
    liveState.value = /404|not found|不存在/i.test(String(e?.message ?? e)) ? 'missing' : 'error'
  }
  ensureColumns()
}

/**
 * 列配置（字段中文 label / 组件类型），每个来源只拉一次：
 * 数据源模式 metadata.columns / 表单模式 getFormDefinitionByKey().columnConfig（同形）
 */
async function ensureColumns() {
  const cacheKey = dsRefId.value ? `ds:${dsRefId.value}` : `form:${props.sourceFormKey}`
  if (!isLiveMode.value || columnsLoadedFor.value === cacheKey) return
  columnsLoadedFor.value = cacheKey
  try {
    let list: ColumnConfigItem[] = []
    if (dsRefId.value) {
      const res: any = await dataSourceApi.getMetadata(dsRefId.value)
      const meta = res?.data ?? res
      list = Array.isArray(meta?.columns) ? meta.columns : []
    } else {
      const res: any = await formApi.getFormDefinitionByKey(props.sourceFormKey)
      const def = res?.data ?? res
      const cfg = def?.columnConfig
      list = cfg ? (typeof cfg === 'string' ? JSON.parse(cfg) : cfg) : []
    }
    const map: Record<string, ColumnConfigItem> = {}
    for (const c of Array.isArray(list) ? list : []) {
      if (c && c.key) map[c.key] = c
    }
    columnsByKey.value = map
  } catch {
    /* label 缺失时回退 key 显示，不阻断主流程 */
  }
}

/** 记录 id 变化（含选人回写 / 清空）→ 重新拉取 */
watch(recordId, (id) => { loadLive(id) }, { immediate: true })

/** 手动刷新 */
function reload() {
  ensureColumnsForce()
  loadLive(recordId.value)
}
function ensureColumnsForce() {
  columnsLoadedFor.value = ''
  ensureColumns()
}

// ===== 摘要字段 =====
const displayFieldsList = computed<string[]>(() => {
  const df = props.displayFields
  if (Array.isArray(df)) return df.filter(Boolean).map(String)
  if (typeof df === 'string') return df.split(',').map((s) => s.trim()).filter(Boolean)
  return []
})

/** 展示列：displayFields 优先；留空自动取前 4 个非空可见字段（排除隐藏/不支持/_text 冗余列） */
const liveDisplayColumns = computed<Array<{ key: string; label: string }>>(() => {
  const row = liveRow.value
  if (!row) return []
  const labelOf = (key: string) => columnsByKey.value[key]?.label || key
  const configured = displayFieldsList.value
  if (configured.length > 0) {
    return configured.map((key) => ({ key, label: labelOf(key) }))
  }
  const visibleKeys = Object.keys(columnsByKey.value).filter((key) => {
    const col = columnsByKey.value[key]
    return !col?.hidden && !col?.unsupported && !key.endsWith('_text')
  })
  const keys = (visibleKeys.length > 0 ? visibleKeys : Object.keys(row.data || {}))
    .filter((key) => {
      const v = readCellValue(row, key)
      return v !== undefined && v !== null && v !== ''
    })
  return keys.slice(0, 4).map((key) => ({ key, label: labelOf(key) }))
})

/** 详情弹窗列：全部可见字段（按 columnConfig 顺序，排除隐藏/不支持/_text） */
const detailColumns = computed<Array<{ key: string; label: string }>>(() => {
  const row = liveRow.value
  if (!row) return []
  const labelOf = (key: string) => columnsByKey.value[key]?.label || key
  const visibleKeys = Object.keys(columnsByKey.value).filter((key) => {
    const col = columnsByKey.value[key]
    return !col?.hidden && !col?.unsupported && !key.endsWith('_text')
  })
  const keys = visibleKeys.length > 0 ? visibleKeys : Object.keys(row.data || {})
  return keys.map((key) => ({ key, label: labelOf(key) }))
})

/** 单元格格式化：数组 join、LocationPicker 复合文本、对象 JSON 兜底 */
function formatLiveCell(key: string): string {
  const row = liveRow.value
  if (!row) return '—'
  const v = readCellValue(row, key)
  if (v === undefined || v === null || v === '') return '—'
  if (Array.isArray(v)) {
    return v.every((x) => x === null || typeof x !== 'object') ? v.join('、') : JSON.stringify(v)
  }
  if (typeof v === 'object') {
    if (columnsByKey.value[key]?.componentType === 'LocationPicker') return formatLocationText(v)
    try { return JSON.stringify(v) } catch { return String(v) }
  }
  return String(v)
}

// ===== 被动模式（v1 逐字保留） =====
/** 对象值取显示字段：displayField 深层取值优先，未配置/取不到依次回退常用键 */
function pickObjectDisplay(obj: Record<string, any>): string {
  const keys = props.displayField ? [props.displayField, 'name', 'title', 'label', 'id'] : ['name', 'title', 'label', 'id']
  for (const key of keys) {
    const v = readCellValue(obj, key)
    if (v !== undefined && v !== null && v !== '') return String(v)
  }
  try {
    return JSON.stringify(obj)
  } catch {
    return String(obj)
  }
}

/** 值摘要文本：字符串/数值直显，对象取 display 字段 */
const summaryText = computed(() => {
  const v = props.modelValue
  if (v === null || v === undefined || v === '') return ''
  if (typeof v === 'object') return pickObjectDisplay(v)
  return String(v)
})

// ===== 交互 =====
const dialogVisible = ref(false)

const isEmptyState = computed(() => {
  if (!isLiveMode.value) return !hasValue.value
  return liveState.value === 'idle'
})

const isClickable = computed(() => {
  if (!props.clickable) return false
  return isLiveMode.value ? liveState.value === 'ready' : hasValue.value
})

function handleClick() {
  if (!isClickable.value) return
  emit('open-detail', isLiveMode.value ? recordId.value : props.modelValue)
  // 实时模式内置详情弹窗；被动模式保持 v1 契约（不内置弹窗，由宿主决定打开方式）
  if (isLiveMode.value) dialogVisible.value = true
}

defineOptions({ name: 'RelationCard' })
defineExpose({ summaryText, hasValue, reload, liveRow })
</script>

<style scoped>
.relation-card {
  width: 100%;
}

.relation-card__body {
  width: 100%;
}

.relation-card__body.is-clickable {
  cursor: pointer;
  transition: box-shadow var(--el-transition-duration), transform var(--el-transition-duration);
}

.relation-card__body.is-clickable:hover {
  transform: translateY(-1px);
}

.relation-card__body.is-empty {
  --el-card-bg-color: var(--el-fill-color-lighter);
}

.relation-card__header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.relation-card__icon {
  color: var(--el-color-primary);
}

.relation-card__badge {
  flex-shrink: 0;
}

.relation-card__refresh {
  margin-left: 0;
  padding: 2px;
}

.relation-card__arrow {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}

.relation-card__title {
  font-size: 14px;
  line-height: 1.6;
}

.relation-card__value {
  margin-top: 6px;
  font-size: 16px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.relation-card__placeholder {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}

.relation-card__placeholder.is-warning {
  color: var(--el-color-warning);
}

/* 实时模式：多字段摘要网格 */
.relation-card__fields {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.relation-card__field {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.relation-card__field-label {
  flex-shrink: 0;
  min-width: 64px;
  color: var(--el-text-color-secondary);
}

.relation-card__field-label::after {
  content: '：';
}

.relation-card__field-value {
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.relation-card__loading {
  margin-top: 8px;
}

.relation-card__desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.relation-card__meta {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: right;
}
</style>
