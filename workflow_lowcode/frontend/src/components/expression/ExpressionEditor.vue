<template>
  <div
    class="expr-editor"
    :class="{ 'is-disabled': disabled, 'has-anchor': hasAnchor, 'has-clear': hasClear }"
  >
    <div ref="hostRef" class="expr-host" />
    <button
      v-if="hasClear"
      class="expr-clear"
      type="button"
      title="清空"
      @mousedown.prevent
      @click="clear"
    >
      <el-icon :size="12"><Close /></el-icon>
    </button>
    <div v-if="hasAnchor" class="expr-anchor" :class="{ 'is-center': !multiline }">
      <slot />
    </div>
  </div>
</template>

<script lang="ts">
export default { name: 'ExpressionEditor' }
</script>

<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
import { autocompletion, type CompletionSource } from '@codemirror/autocomplete'
import { EditorState } from '@codemirror/state'
import { EditorView, placeholder as cmPlaceholder } from '@codemirror/view'
import { computed, onBeforeUnmount, onMounted, ref, useSlots, watch } from 'vue'
import type { FlowVarItem } from '@/views/logicflow/utils/flowVars'
import {
  assembleCompartments,
  buildDynamicExtensions,
  buildLang,
  buildStaticExtensions,
  buildTheme,
  makeKeywordCompletionSource,
  makeVarCompletionSource,
  SQL_KEYWORDS,
  type ExpressionCompartments,
  type ExpressionExtensionConfig,
} from './cm6'

/**
 * CodeMirror 6 表达式/脚本编辑器（全项目共用）：
 * - {{var}} / ${var} 占位符高亮 + 占位符内变量自动补全（输入 {{ 即列全部变量，↑↓/Enter 选择）
 * - bare 模式（变量名/Groovy）：标识符即输即补
 * - lang：none（模板）/ groovy（JS 近似高亮）/ sql（关键字高亮 + 关键字补全 + {{var}} 模板）
 * - 右侧锚位插槽：逻辑流 VarInput 放 { } 变量选择器；清空钮由 clearable 控制
 */
const props = withDefaults(
  defineProps<{
    modelValue: string | null | undefined
    variables?: FlowVarItem[]
    mode?: 'placeholder' | 'bare'
    syntax?: 'mustache' | 'uel'
    lang?: 'none' | 'groovy' | 'sql'
    multiline?: boolean
    rows?: number
    maxRows?: number
    placeholder?: string
    disabled?: boolean
    small?: boolean
    clearable?: boolean
    /** 追加自定义补全源（公式函数等；与变量补全源共存） */
    customSource?: CompletionSource
  }>(),
  {
    variables: () => [],
    mode: 'placeholder',
    syntax: 'mustache',
    lang: 'none',
    multiline: false,
    rows: 3,
    maxRows: 26,
    placeholder: '',
    disabled: false,
    small: false,
    clearable: false,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 失焦提交（对齐 el-input change 语义，BPMN 面板 saveConfig 用） */
  change: [value: string]
}>()

const slots = useSlots()
const hostRef = ref<HTMLDivElement | null>(null)
const editorRef = ref<EditorView | null>(null)
const compartments: ExpressionCompartments = assembleCompartments()

/** 外部回灌期间抑制 emit 回环（dispatch 同步触发 updateListener） */
let applyingExternal = false

const hasAnchor = computed(() => !!slots.default)
const hasValue = computed(() => String(props.modelValue ?? '').length > 0)
const hasClear = computed(() => props.clearable && hasValue.value && !props.disabled)

function currentConfig(): ExpressionExtensionConfig {
  return {
    mode: props.mode,
    syntax: props.syntax,
    lang: props.lang,
    items: props.variables,
    placeholder: props.placeholder,
    disabled: props.disabled,
    multiline: props.multiline,
    singleLine: !props.multiline,
    small: props.small,
    rows: props.rows,
    maxRows: props.maxRows,
    reserveRight: hasAnchor.value ? (props.clearable ? 58 : 34) : props.clearable ? 24 : 0,
    extraSources: props.customSource ? [props.customSource] : undefined,
  }
}

const updateListener = EditorView.updateListener.of((vu) => {
  if (vu.docChanged && !applyingExternal) {
    emit('update:modelValue', vu.state.doc.toString())
  }
})

/** 失焦提交（el-input change 同义） */
const blurHandler = EditorView.domEventHandlers({
  blur: (_event, view) => {
    emit('change', view.state.doc.toString())
    return false
  },
})

function createState(cfg: ExpressionExtensionConfig): EditorState {
  return EditorState.create({
    doc: String(props.modelValue ?? ''),
    extensions: [
      ...buildStaticExtensions(cfg),
      blurHandler,
      updateListener,
      ...buildDynamicExtensions(compartments, cfg),
    ],
  })
}

function mountView() {
  if (!hostRef.value) return
  editorRef.value?.destroy()
  editorRef.value = new EditorView({ state: createState(currentConfig()), parent: hostRef.value })
}

onMounted(mountView)

// ===== props 同步 =====

/** 外部 modelValue → 文档（内部编辑触发的 emit 已等值，跳过） */
watch(
  () => props.modelValue,
  (v) => {
    const view = editorRef.value
    if (!view) return
    const next = String(v ?? '')
    if (next === view.state.doc.toString()) return
    applyingExternal = true
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } })
    applyingExternal = false
  }
)

/** 变量树/模式/语法/语言/占位符/禁用/尺寸 → Compartment 热替换 */
watch(
  () =>
    [
      props.variables,
      props.mode,
      props.syntax,
      props.lang,
      props.placeholder,
      props.disabled,
      props.small,
      props.rows,
      props.maxRows,
      props.clearable,
      props.customSource,
    ] as const,
  () => {
    const view = editorRef.value
    if (!view) return
    const cfg = currentConfig()
    const acSources = [makeVarCompletionSource(cfg)]
    if (cfg.lang === 'sql') acSources.push(makeKeywordCompletionSource(SQL_KEYWORDS))
    // extraSources 与 createState 初值保持一致：reconfigure 重建 override 时若漏掉，
    // 自定义补全源（# 呼出字段等）会在任意 props 变化后静默失效
    if (cfg.extraSources?.length) acSources.push(...cfg.extraSources)
    view.dispatch({
      effects: [
        compartments.lang.reconfigure(buildLang(cfg)),
        compartments.autoComplete.reconfigure(
          autocompletion({ override: acSources, icons: false, closeOnBlur: true })
        ),
        compartments.placeholder.reconfigure(cmPlaceholder(cfg.placeholder || '')),
        compartments.readOnly.reconfigure(
          cfg.disabled ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []
        ),
        compartments.theme.reconfigure(buildThemeCompartment(cfg)),
      ],
    })
  }
)

/** 多行/单行切换涉及静态 keymap 差异，整体重建 */
watch(
  () => props.multiline,
  () => {
    editorRef.value?.destroy()
    mountView()
  }
)

function buildThemeCompartment(cfg: ExpressionExtensionConfig) {
  return [buildTheme(cfg), ...(cfg.multiline ? [EditorView.lineWrapping] : [])]
}

// ===== 对外命令 =====

function focus() {
  editorRef.value?.focus()
}

function blur() {
  editorRef.value?.contentDOM.blur()
}

/** 光标处插入文本（变量选择器/chips 共用），保持焦点与光标位置；cursorBack 相对插入尾部回退（函数括号内定位等）。
 *  未聚焦时退化为尾部追加（对齐旧 textarea selectionStart 兜底语义）。 */
function insertAtCursor(text: string, cursorBack = 0) {
  const view = editorRef.value
  if (!view) return
  const insert = props.multiline ? text : text.replace(/[\r\n]+/g, ' ')
  const focused = view.hasFocus
  const sel = view.state.selection.main
  const from = focused ? sel.from : view.state.doc.length
  const to = focused ? sel.to : view.state.doc.length
  view.dispatch({
    changes: { from, to, insert },
    selection: { anchor: from + insert.length - cursorBack },
    scrollIntoView: true,
  })
  view.focus()
}

function clear() {
  const view = editorRef.value
  if (!view) return
  applyingExternal = true
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: '' } })
  applyingExternal = false
  emit('update:modelValue', '')
  focus()
}

onBeforeUnmount(() => {
  editorRef.value?.destroy()
  editorRef.value = null
})

defineExpose({ focus, blur, insertAtCursor, clear, getView: () => editorRef.value })
</script>

<style scoped>
.expr-editor {
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
}

.expr-editor.is-disabled {
  cursor: not-allowed;
}

.expr-editor.is-disabled :deep(.cm-editor) {
  background: var(--el-fill-color-light);
  opacity: 0.72;
}

.expr-host {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
}

.expr-host :deep(.cm-editor) {
  flex: 1 1 auto;
}

.expr-clear {
  position: absolute;
  top: 50%;
  right: 8px;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-placeholder);
  cursor: pointer;
  transform: translateY(-50%);
  transition: background 0.15s, color 0.15s;
}

.expr-clear:hover {
  background: var(--el-fill-color-darker);
  color: var(--el-text-color-regular);
}

.has-anchor .expr-clear {
  right: 34px;
}

.expr-anchor {
  position: absolute;
  top: 6px;
  right: 8px;
  z-index: 5;
  display: flex;
  align-items: center;
  line-height: 1;
}

/* 单行：选择器垂直居中 */
.expr-anchor.is-center {
  top: 50%;
  transform: translateY(-50%);
}
</style>
