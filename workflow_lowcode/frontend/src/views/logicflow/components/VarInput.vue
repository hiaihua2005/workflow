<template>
  <div class="var-field" v-bind="wrapperAttrs">
    <ExpressionEditor
      ref="editorInst"
      :model-value="displayValue"
      :variables="variables"
      :mode="mode"
      :lang="lang"
      :multiline="textarea"
      :rows="rows"
      :small="isSmall"
      :clearable="clearable"
      :placeholder="placeholder"
      :disabled="disabled"
      :custom-source="hashSource"
      :priority-sources="prioritySources"
      @update:model-value="onValue"
    >
      <VariablePicker :variables="variables" :mode="mode" @pick="onPick" />
    </ExpressionEditor>

    <!-- 表达式徽标：显示层已剥离 = 前缀，用角标区分“值表达式”与字面量/模板 -->
    <span v-if="showFxBadge" class="fx-badge" aria-hidden="true">ƒx</span>

    <!-- 就近变量列表（Groovy 编辑器等长文本场景） -->
    <div v-if="chips" class="var-chips">
      <span class="chips-label">变量</span>
      <div class="chips-scroll">
        <template v-if="chipVars.length">
          <button
            v-for="v in chipVars"
            :key="v.group + v.name"
            class="chip"
            type="button"
            :title="v.detail || v.name"
            @click="onChipPick(v)"
          >
            {{ v.name }}
          </button>
        </template>
        <span v-else class="chips-empty">暂无可用变量：工具栏「入参」可声明，上游节点输出参数（results）可产出</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useAttrs } from 'vue'
import type { CompletionSource } from '@codemirror/autocomplete'
import ExpressionEditor from '@/components/expression/ExpressionEditor.vue'
import VariablePicker from './VariablePicker.vue'
import { makeHashFieldCompletionSource, type HashFieldOption, type HashInsertMode } from '@/components/expression/cm6'
import type { FlowVarItem } from '../utils/flowVars'
import { varInsertText } from '../utils/flowVars'

defineOptions({ name: 'VarInput', inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue: string | null | undefined
    variables?: FlowVarItem[]
    /** placeholder = 插入 {{name}}；bare = 插入裸变量名 */
    mode?: 'placeholder' | 'bare'
    /** 点击后整字段替换（变量名单值字段：condition.variable / 参数 source 等） */
    replace?: boolean
    /** 多行形态（Groovy/SQL/LLM 提示词等长文本编辑器） */
    textarea?: boolean
    rows?: number
    /** 显示就近变量 chips 行 */
    chips?: boolean
    /** 脚本语言高亮：groovy（JS 近似）/ sql（关键字 + {{var}} 模板） */
    lang?: 'none' | 'groovy' | 'sql'
    /** el-input 兼容尺寸（small → 24px 行高） */
    size?: string
    /** 可清空（http url 等字段） */
    clearable?: boolean
    placeholder?: string
    disabled?: boolean
    /** # 呼出字段补全（如数据更新值/WHERE 值：目标表字段列表） */
    hashFields?: HashFieldOption[]
    /** # 选中插入形态：placeholder = {{formData.字段}}（默认）；bare = 裸列名（值表达式场景，
     *  显示层剥离存储的 = 前缀，写回时统一补回，编辑器内永不见 =） */
    hashInsert?: HashInsertMode
    /** 优先补全源（SQL 表名/字段名、Groovy Bean/方法等结构化提示；
     *  排在 SQL 关键字源之前，透传 ExpressionEditor 热替换） */
    prioritySources?: CompletionSource[]
  }>(),
  {
    variables: () => [],
    mode: 'placeholder',
    replace: false,
    textarea: false,
    rows: 3,
    chips: false,
    lang: 'none',
    size: '',
    clearable: false,
    placeholder: '',
    disabled: false,
    hashFields: undefined,
    hashInsert: 'placeholder',
  }
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const attrs = useAttrs()
/** class/style 落在外层包装（flex 布局/deep 选择器沿用原类名位置） */
const wrapperAttrs = computed(() => ({ class: attrs.class, style: attrs.style }))

const isSmall = computed(() => props.size === 'small')

/** # 呼出字段补全源（getter 闭包随 hashFields 实时生效免重建；hashInsert 变化经 computed 换源，
 *  ExpressionEditor 对 customSource 引用变化做 Compartment 热替换） */
const hashFieldsRef = computed<HashFieldOption[]>(() => props.hashFields ?? [])
const hashSource = computed(() =>
  makeHashFieldCompletionSource(() => hashFieldsRef.value, 'mustache', props.hashInsert)
)

/** 值表达式展示模式（bare = 呼出裸列名场景）：编辑器显示层剥离存储层的 = 前缀，
 *  用户只看到表达式本体；== 转义字面量原样展示。 */
const isExprMode = computed(() => props.hashInsert === 'bare')

/** 存储 → 显示：剥 "=" + 后随空白；== 转义（及手输 = 开头）原样保留。 */
function toDisplay(v: string | null | undefined): string {
  const s = String(v ?? '')
  if (s.startsWith('==')) return s
  if (s.startsWith('=')) return s.replace(/^=\s*/, '')
  return s
}

/** 显示 → 存储：非空且非 = 开头统一补 "= " 前缀（表达式语义）；= 开头原样（转义/手输）；空串归空。 */
function toStored(v: string): string {
  const t = v.trim()
  if (!t) return ''
  if (t.startsWith('=')) return v
  return '= ' + v
}

const displayValue = computed(() => (isExprMode.value ? toDisplay(props.modelValue) : String(props.modelValue ?? '')))

/** 存储值以单 = 开头（非 == 转义）→ 当前为表达式语义，角标提示 */
const showFxBadge = computed(() => {
  if (!isExprMode.value) return false
  const s = String(props.modelValue ?? '').trimStart()
  return s.startsWith('=') && !s.startsWith('==')
})

const editorInst = ref<InstanceType<typeof ExpressionEditor> | null>(null)

/** bare 模式 chips 同步隐藏 formData 组 */
const chipVars = computed(() =>
  props.mode === 'bare' ? props.variables.filter((v) => v.group !== 'form') : props.variables
)

function onValue(value: string) {
  emit('update:modelValue', isExprMode.value ? toStored(value) : value)
}

function onPick(text: string) {
  if (props.replace && !props.textarea) {
    emit('update:modelValue', text)
    return
  }
  editorInst.value?.insertAtCursor(text)
}

/** chips 点击：与补全列表同一插入语义 */
function onChipPick(v: FlowVarItem) {
  onPick(varInsertText(v, props.mode))
}
</script>

<style scoped>
.var-field {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  width: 100%;
  flex-direction: column;
  gap: 6px;
}

.var-field :deep(.expr-editor),
.var-field :deep(.cm-editor) {
  width: 100%;
}

/* ===== 表达式角标（ƒx）===== */
.fx-badge {
  position: absolute;
  top: -7px;
  right: 5px;
  z-index: 2;
  padding: 0 4px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 32%, transparent);
  border-radius: 4px;
  background: var(--el-bg-color-overlay);
  color: var(--el-color-primary);
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 10px;
  font-style: italic;
  line-height: 13px;
  letter-spacing: 0.5px;
  pointer-events: none;
  box-shadow: 0 1px 2px rgba(31, 36, 55, 0.08);
}

/* ===== 就近变量 chips ===== */
.var-chips {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 11px;
}

.chips-label {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  height: 21px; /* 与首行 chip 等高（11px×1.4 行高 + 2px 内距 + 1px 边框），label 垂直居中对齐 */
  color: var(--el-text-color-secondary);
}

.chips-scroll {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 74px;
  overflow-y: auto;
  scrollbar-width: thin;
}

.chips-scroll::-webkit-scrollbar {
  width: 5px;
}

.chips-scroll::-webkit-scrollbar-thumb {
  background: var(--el-border-color);
  border-radius: 3px;
}

.chip {
  padding: 2px 8px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 30%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);
  color: var(--el-color-primary);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  line-height: 1.4;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.chip:hover {
  background: color-mix(in srgb, var(--el-color-primary) 16%, transparent);
  border-color: var(--el-color-primary);
}

.chips-empty {
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
}
</style>
