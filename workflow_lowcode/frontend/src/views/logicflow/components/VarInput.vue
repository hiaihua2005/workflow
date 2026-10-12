<template>
  <div class="var-field" v-bind="wrapperAttrs">
    <ExpressionEditor
      ref="editorInst"
      :model-value="modelValue"
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
      @update:model-value="onValue"
    >
      <VariablePicker :variables="variables" :mode="mode" @pick="onPick" />
    </ExpressionEditor>

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
    /** # 选中插入形态：placeholder = {{formData.字段}}（默认）；bare = 裸列名 + 自动补 = 前缀（SET 值表达式） */
    hashInsert?: HashInsertMode
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
const hashSource = computed(() => {
  console.log('[VarInput-debug] hashInsert =', props.hashInsert, '| hashFields =', hashFieldsRef.value.length)
  return makeHashFieldCompletionSource(() => hashFieldsRef.value, 'mustache', props.hashInsert)
})

const editorInst = ref<InstanceType<typeof ExpressionEditor> | null>(null)

/** bare 模式 chips 同步隐藏 formData 组 */
const chipVars = computed(() =>
  props.mode === 'bare' ? props.variables.filter((v) => v.group !== 'form') : props.variables
)

function onValue(value: string) {
  emit('update:modelValue', value)
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
