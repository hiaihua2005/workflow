<template>
  <div class="relation-field-select">
    <el-select
      :model-value="innerValue"
      :multiple="multiple"
      filterable
      :allow-create="allowCreate"
      :default-first-option="false"
      :clearable="clearable"
      :loading="loading"
      :placeholder="placeholder"
      :reserve-keyword="true"
      @update:model-value="handleInput"
    >
      <el-option
        v-for="opt in mergedOptions"
        :key="opt.value"
        :value="opt.value"
        :label="opt.label"
      >
        <span class="relation-field-select__opt-label">{{ opt.label }}</span>
        <span v-if="opt.hint" class="relation-field-select__opt-hint">{{ opt.hint }}</span>
      </el-option>
    </el-select>
    <div
      v-if="hintText"
      class="relation-field-select__hint"
      :class="{ 'is-error': hintError }"
    >
      {{ hintText }}
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * RelationFieldSelect——RelationCard 属性面板专用动态下拉（仅设计器 props 面板使用）。
 *
 * 与 ColorInput / FnInput / FormulaExpressionEditor 同族：注册到 designerForm 的
 * 自定义 prop 组件（main.ts 用 FcDesigner.component 注册），解决 relationCard.js
 * 显示字段/摘要展示字段此前只能静态硬编码候选字段的问题。
 *
 * 【两种模式】
 * - mode='form'（源表单选择）：拉取已发布业务表单列表
 *   （GET /v1/form-definitions?type=BUSINESS&status=PUBLISHED）作为下拉项，
 *   保留 filterable + allowCreate 允许手填未发布表单 key。
 * - mode='field'（字段选择）：props 面板的 props() 第一参是**活跃画布 rule**
 *   （FcDesigner.getPropsRule → tidyRuleConfig 传入），把它作为响应式 canvasRule
 *   prop 传入本组件，watch canvasRule.props[formKeyField]（即 sourceFormKey）：
 *   变化 → formApi.getFormDefinitionByKey(key) → columnConfig 字段列表
 *   （columnConfig 为空时兜底解析 schema 规则树提取 field/title）作为下拉项；
 *   未配置源表单 / 加载失败时回退静态常用字段（fallbackOptions），始终可手填。
 *
 * 值契约：单选 string / 多选 string[]，与原 select 组件一致，切换组件不迁移历史值。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { formApi } from '@/api/form'

interface FieldOption {
  value: string
  label: string
  hint?: string
}

const props = withDefaults(defineProps<{
  /** 绑定值：单选 string / 多选 string[]（对齐原 select 组件值形态） */
  modelValue?: string | number | Array<string | number> | null
  /** form：业务表单下拉；field：源表单字段下拉 */
  mode?: 'form' | 'field'
  /** 多选 */
  multiple?: boolean
  /** 静态兜底候选字段（relationCard.js options 同源传入；测试契约同步断言 rule.options） */
  options?: FieldOption[]
  /** 未配置源表单时的回退候选（与 options 同源，双通道确保可达） */
  fallbackOptions?: FieldOption[]
  /** 画布活跃 rule（props() 第一参透传，响应式读取 sourceFormKey） */
  canvasRule?: Record<string, any> | null
  /** 画布 rule.props 中存放源表单 key 的字段名 */
  formKeyField?: string
  placeholder?: string
  allowCreate?: boolean
  clearable?: boolean
}>(), {
  modelValue: '',
  mode: 'field',
  multiple: false,
  options: () => [],
  fallbackOptions: () => [],
  canvasRule: null,
  formKeyField: 'sourceFormKey',
  placeholder: '选择或输入',
  allowCreate: true,
  clearable: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | string[]]
  change: [value: string | string[]]
}>()

// ===== 值绑定 =====
const innerValue = computed<string | string[]>(() => {
  if (props.multiple) {
    return Array.isArray(props.modelValue) ? props.modelValue.map(String) : []
  }
  const v = props.modelValue
  return v === null || v === undefined ? '' : String(v)
})

function handleInput(v: string | string[]) {
  emit('update:modelValue', v)
  emit('change', v)
}

// ===== 动态候选 =====
const loading = ref(false)
const dynamicOptions = ref<FieldOption[]>([])
const formName = ref('')
const loadError = ref(false)
/** 加载序号守卫：快速切换源表单时丢弃过期响应 */
let fetchSeq = 0

/** 静态兜底候选（options 与 fallbackOptions 双通道，取先到者） */
const staticOptions = computed<FieldOption[]>(() => {
  if (props.fallbackOptions.length > 0) return props.fallbackOptions
  if (props.options.length > 0) return props.options
  return []
})

/** 画布 rule.props 中的源表单 key（响应式） */
const sourceFormKey = computed<string>(() => {
  const key = props.formKeyField || 'sourceFormKey'
  const v = props.canvasRule?.props?.[key]
  return typeof v === 'string' ? v.trim() : ''
})

function safeParse(json: unknown): any {
  if (typeof json !== 'string' || !json.trim()) return null
  try { return JSON.parse(json) } catch { return null }
}

/** columnConfig → 候选（排除 unsupported 与 _text 冗余列，与 RelationCard 展示口径一致） */
function parseColumnConfig(cfg: unknown): FieldOption[] {
  const list = typeof cfg === 'string' ? safeParse(cfg) : cfg
  if (!Array.isArray(list)) return []
  return list
    .filter((c: any) => c && c.key && !c.unsupported && !String(c.key).endsWith('_text'))
    .map((c: any) => ({
      value: String(c.key),
      label: `${c.label || c.key}（${c.key}）`,
    }))
}

/** columnConfig 缺失时兜底：遍历 schema 规则树收集 field/title */
function extractFromSchema(schema: unknown): FieldOption[] {
  const tree = typeof schema === 'string' ? safeParse(schema) : schema
  const out: FieldOption[] = []
  const seen = new Set<string>()
  const walk = (node: any) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) {
      node.forEach(walk)
      return
    }
    if (node.field && !seen.has(node.field) && !String(node.field).endsWith('_text')) {
      seen.add(node.field)
      const title = typeof node.title === 'string' ? node.title : ''
      out.push({
        value: String(node.field),
        label: title ? `${title}（${node.field}）` : String(node.field),
      })
    }
    if (node.children) walk(node.children)
    if (node.columns) walk(node.columns)
    if (node.props?.rule) walk(node.props.rule)
  }
  walk(tree)
  return out
}

async function loadFieldOptions(formKey: string) {
  const seq = ++fetchSeq
  formName.value = ''
  loadError.value = false
  if (!formKey) {
    dynamicOptions.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await formApi.getFormDefinitionByKey(formKey)
    if (seq !== fetchSeq) return // 过期响应丢弃
    const def = res?.data ?? res
    formName.value = def?.name || ''
    const opts = parseColumnConfig(def?.columnConfig).length
      ? parseColumnConfig(def?.columnConfig)
      : extractFromSchema(def?.schema)
    dynamicOptions.value = opts
  } catch {
    if (seq !== fetchSeq) return
    loadError.value = true
    dynamicOptions.value = []
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

/** form 模式：已发布业务表单列表 */
async function loadFormOptions() {
  loading.value = true
  loadError.value = false
  try {
    const res: any = await formApi.getFormDefinitions({ type: 'BUSINESS', status: 'PUBLISHED', size: 200 })
    const data = res?.data ?? res
    const rows: any[] = data?.rows ?? data?.content ?? (Array.isArray(data) ? data : [])
    dynamicOptions.value = rows
      .filter((f) => f && f.key)
      .map((f) => ({ value: String(f.key), label: `${f.name || f.key}（${f.key}）` }))
  } catch {
    loadError.value = true
    dynamicOptions.value = []
  } finally {
    loading.value = false
  }
}

watch(sourceFormKey, (k) => { if (props.mode === 'field') loadFieldOptions(k) }, { immediate: true })
onMounted(() => { if (props.mode === 'form') loadFormOptions() })

// ===== 合并候选：已选值不在候选中（换了源表单/手填历史值）时补入，保证回显不裸奔 =====
const mergedOptions = computed<FieldOption[]>(() => {
  const base = dynamicOptions.value.length > 0 ? dynamicOptions.value : staticOptions.value
  const map = new Map<string, FieldOption>()
  for (const o of base) map.set(o.value, o)
  const selected = props.multiple
    ? (Array.isArray(props.modelValue) ? props.modelValue : [])
    : (props.modelValue !== null && props.modelValue !== undefined && props.modelValue !== '' ? [props.modelValue] : [])
  for (const raw of selected) {
    const v = String(raw)
    if (v && !map.has(v)) map.set(v, { value: v, label: v })
  }
  return [...map.values()]
})

/** 已选但不在当前候选中的数量（切换源表单后的提示） */
const missingSelectedCount = computed<number>(() => {
  if (dynamicOptions.value.length === 0) return 0
  const known = new Set(dynamicOptions.value.map((o) => o.value))
  const selected = props.multiple
    ? (Array.isArray(props.modelValue) ? props.modelValue.map(String) : [])
    : (props.modelValue ? [String(props.modelValue)] : [])
  return selected.filter((v) => v && !known.has(v)).length
})

// ===== 提示行 =====
const hintText = computed<string>(() => {
  if (props.mode === 'form') {
    if (loadError.value) return '表单列表加载失败，可直接手填表单 Key'
    if (loading.value) return '正在加载表单列表…'
    if (dynamicOptions.value.length > 0) return `共 ${dynamicOptions.value.length} 个已发布业务表单`
    return ''
  }
  if (!sourceFormKey.value) return '先配置「源表单」，下拉将自动加载其字段列表；也可直接手填'
  if (loadError.value) return '字段列表加载失败（表单不存在或未发布），可手填字段名'
  if (loading.value) return '正在加载字段列表…'
  if (missingSelectedCount.value > 0) return `${missingSelectedCount.value} 个已选字段不属于当前源表单，建议重新选择`
  if (dynamicOptions.value.length > 0) {
    return formName.value
      ? `已加载「${formName.value}」${dynamicOptions.value.length} 个字段`
      : `已加载 ${dynamicOptions.value.length} 个字段`
  }
  return '该表单未解析到字段，可手填字段名'
})
const hintError = computed(() => loadError.value || missingSelectedCount.value > 0)
</script>

<style scoped>
.relation-field-select {
  width: 100%;
}

.relation-field-select :deep(.el-select) {
  width: 100%;
}

.relation-field-select__opt-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.relation-field-select__opt-hint {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.relation-field-select__hint {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.relation-field-select__hint.is-error {
  color: var(--el-color-warning);
}
</style>
