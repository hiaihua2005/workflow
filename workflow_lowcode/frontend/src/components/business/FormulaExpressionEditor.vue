<template>
  <div class="fxe">
    <!-- 属性面板内嵌态：CM6 表达式编辑器（${字段} 高亮+字段/函数即输即补）+ 弹窗入口 -->
    <ExpressionEditor
      class="fxe__input"
      :model-value="modelValue ?? ''"
      syntax="uel"
      multiline
      :rows="2"
      :max-rows="6"
      placeholder="${price} * ${count} * (1 - ${discount})"
      :variables="fieldVars"
      :custom-source="fnSource"
      @update:model-value="onPanelInput"
    />
    <el-button class="fxe__open" size="small" @click="open">
      <span class="fxe__open-fx">ƒx</span> 可视化配置
    </el-button>

    <!-- 可视化配置弹窗 -->
    <el-dialog
      v-model="visible"
      title="计算表达式可视化配置"
      width="720px"
      append-to-body
      :close-on-click-modal="false"
      class="fxd"
    >
      <div class="fxd__body">
        <!-- 表达式编辑区（CM6：字段/函数补全 + ${} 占位符高亮） -->
        <ExpressionEditor
          ref="edRef"
          v-model="draft"
          class="fxd__ed"
          syntax="uel"
          multiline
          :rows="4"
          :max-rows="14"
          placeholder="点击下方字段 / 函数 / 运算符拼接表达式，或直接输入"
          :variables="fieldVars"
          :custom-source="fnSource"
        />
        <div class="fxd__status" :class="statusClass">
          <template v-if="!draft.trim()">空表达式：运行时将显示占位符，不参与提交值计算</template>
          <template v-else-if="draftError">✗ {{ draftError }}</template>
          <template v-else>✓ 表达式有效<template v-if="draftDeps.length">，引用字段：{{ draftDeps.map(depLabel).join('、') }}</template></template>
        </div>
        <div v-if="unknownDeps.length" class="fxd__warn">
          ⚠ 以下字段不在当前表单中：{{ unknownDeps.join('、') }}（运行时按空值处理，结果为空）
        </div>

        <!-- 字段面板 -->
        <div class="fxd__section">
          <div class="fxd__label">字段 <span class="fxd__label-sub">点击插入 ${字段名}</span></div>
          <div class="fxd__chips">
            <button
              v-for="opt in fieldOptions"
              :key="opt.field"
              type="button"
              class="fxd__chip fxd__chip--field"
              :title="`${opt.field}${opt.title ? ' · ' + opt.title : ''}${opt.type ? ' · ' + opt.type : ''}`"
              @click="insert('${' + opt.field + '}')"
            >
              <span class="fxd__chip-title">{{ opt.title || opt.field }}</span>
              <span class="fxd__chip-name">{{ opt.field }}</span>
            </button>
            <span v-if="!fieldOptions.length" class="fxd__empty">
              当前表单暂无其他字段——先在画布添加数字类字段（数字 / 金额 / 计算公式除外），或直接输入常量
            </span>
          </div>
        </div>

        <!-- 函数面板 -->
        <div class="fxd__section">
          <div class="fxd__label">函数 <span class="fxd__label-sub">点击插入并定位参数位</span></div>
          <div class="fxd__chips">
            <button
              v-for="fn in FORMULA_FUNCTIONS"
              :key="fn.name"
              type="button"
              class="fxd__chip fxd__chip--fn"
              :title="`${fn.name}(${fn.args}) — ${fn.desc}`"
              @click="insert(fn.name + '()', 1)"
            >
              <span class="fxd__chip-name">{{ fn.name }}</span>
              <span class="fxd__chip-title">{{ fn.desc }}</span>
            </button>
          </div>
        </div>

        <!-- 运算符 / 数字面板 -->
        <div class="fxd__section">
          <div class="fxd__label">运算符与数字</div>
          <div class="fxd__chips">
            <button
              v-for="op in FORMULA_OPERATORS"
              :key="'op-' + op"
              type="button"
              class="fxd__chip fxd__chip--op"
              :title="op === '%' ? '取模（余数）' : op === '(' || op === ')' ? '分组括号' : op === '*' ? '乘' : op === '/' ? '除' : op === '-' ? '减' : '加'"
              @click="insert(op === '*' || op === '/' ? ` ${op} ` : op)"
            >{{ op }}</button>
            <button
              v-for="d in FORMULA_DIGITS"
              :key="'d-' + d"
              type="button"
              class="fxd__chip fxd__chip--op"
              @click="insert(d)"
            >{{ d }}</button>
          </div>
        </div>

        <!-- 试算面板 -->
        <div v-if="draftDeps.length" class="fxd__section">
          <div class="fxd__label">试算 <span class="fxd__label-sub">为每个引用字段填测试值，预览计算结果</span></div>
          <div class="fxd__try">
            <label v-for="d in draftDeps" :key="d" class="fxd__try-item">
              <span class="fxd__try-name" :title="d">{{ depLabel(d) }}</span>
              <el-input v-model="tryValues[d]" size="small" placeholder="测试值" clearable />
            </label>
            <span class="fxd__try-eq">=</span>
            <span class="fxd__try-result" :class="{ 'is-empty': preview === '—' }">{{ preview }}</span>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="fxd__footer">
          <el-button size="small" text type="danger" @click="clearAll">清空</el-button>
          <span class="fxd__footer-spacer"></span>
          <el-button size="small" @click="visible = false">取消</el-button>
          <el-button size="small" type="primary" :disabled="!!draft.trim() && !!draftError" @click="confirm">确定</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 计算表达式可视化编辑器（Task 145）。
 *
 * 位置：fc-designer 属性面板中 FormulaField 的 expression 字段（自定义 prop 组件，
 * main.ts 经 FcDesigner.component 全局注册，设计器面板 form-create 实例可解析）。
 *
 * 结构：
 * - 内嵌态：CM6 表达式编辑器（${字段} 占位符高亮 + 字段/函数即输即补）+「可视化配置」按钮
 * - 弹窗：CM6 编辑区 + 字段/函数/运算符点选插入（光标处插入，
 *   函数自动补右括号并定位参数位）+ 实时校验（formulaEval 解析器）+
 *   依赖试算预览 + 未知字段警示
 * - 字段候选来自 formulaFieldRegistry provider（设计器侧注册，实时读 rule 树）
 */
import { computed, nextTick, ref, reactive } from 'vue'
import type { EditorView } from '@codemirror/view'
import ExpressionEditor from '@/components/expression/ExpressionEditor.vue'
import { makeSnippetCompletionSource } from '@/components/expression/cm6'
import { parseFormula } from './formulaEval'
import { FORMULA_DIGITS, FORMULA_FUNCTIONS, FORMULA_OPERATORS, formatPreview, parseDraft } from './formulaEditorKit'
import { getFormulaFieldOptions, type FormulaFieldOption } from './formulaFieldRegistry'

const props = withDefaults(defineProps<{
  /** 表达式（绑定属性面板 expression 字段） */
  modelValue?: string
}>(), {
  modelValue: '',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const visible = ref(false)
const draft = ref('')
const edRef = ref<InstanceType<typeof ExpressionEditor> | null>(null)
const fieldOptions = ref<FormulaFieldOption[]>([])
const tryValues = reactive<Record<string, string>>({})

/** 字段补全树：${字段} 占位符内即输即补（detail=中文名） */
const fieldVars = computed(() =>
  fieldOptions.value.map((o) => ({ name: o.field, group: 'form' as const, groupLabel: '字段', detail: o.title || undefined }))
)

/** 函数补全源：键入函数名 → 插 FN() 且光标落括号内（与字段补全共存） */
const fnSource = makeSnippetCompletionSource(
  FORMULA_FUNCTIONS.map((fn) => ({
    label: fn.name,
    detail: `${fn.name}(${fn.args}) — ${fn.desc}`,
    type: 'function',
    apply: (view: EditorView, _completion: unknown, from: number, to: number) => {
      const insert = `${fn.name}()`
      view.dispatch({
        changes: { from, to, insert },
        selection: { anchor: from + fn.name.length + 1 },
        scrollIntoView: true,
      })
      view.focus()
    },
  }))
)

function onPanelInput(v: string): void {
  emit('update:modelValue', v)
}

function open(): void {
  draft.value = props.modelValue || ''
  fieldOptions.value = getFormulaFieldOptions()
  Object.keys(tryValues).forEach(k => delete tryValues[k])
  visible.value = true
}

function clearAll(): void {
  draft.value = ''
  Object.keys(tryValues).forEach(k => delete tryValues[k])
  nextTick(() => edRef.value?.focus())
}

function confirm(): void {
  emit('update:modelValue', draft.value)
  visible.value = false
}

/** 光标处插入（CM6 编辑器）：cursorBack 相对插入尾部回退（函数补右括号后定位参数位） */
function insert(text: string, cursorBack = 0): void {
  edRef.value?.insertAtCursor(text, cursorBack)
}

const draftParsed = computed(() => parseDraft(draft.value))
const draftDeps = computed(() => draftParsed.value.deps)
const draftError = computed(() => draftParsed.value.error)

/** 引用了当前表单不存在的字段（拼错 / 已删除）→ 轻警示（不阻断保存） */
const unknownDeps = computed(() => {
  const known = new Set(fieldOptions.value.map(o => o.field))
  return draftDeps.value.filter(d => !known.has(d))
})

const statusClass = computed(() => {
  if (!draft.value.trim()) return 'is-hint'
  return draftError.value ? 'is-err' : 'is-ok'
})

function depLabel(field: string): string {
  const hit = fieldOptions.value.find(o => o.field === field)
  return hit?.title ? `${hit.title}(${field})` : field
}

/** 试算：所有依赖都有测试值才计算；空/非数字由 formulaEval 归一为 NaN → '—' */
const preview = computed(() => {
  const src = draft.value.trim()
  if (!src || draftError.value) return '—'
  try {
    const f = parseFormula(src)
    if (!f.deps.length) return '—'
    if (!f.deps.every(d => (tryValues[d] ?? '').trim() !== '')) return '—'
    return formatPreview(f.evaluate({ ...tryValues }))
  } catch {
    return '—'
  }
})
</script>

<style scoped>
.fxe {
  width: 100%;
}
.fxe__input {
  width: 100%;
}
.fxe__open {
  width: 100%;
  margin-top: 6px;
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.fxe__open-fx {
  font-style: italic;
  font-weight: 600;
  margin-right: 2px;
}

/* ===== 弹窗 ===== */
.fxd__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.fxd__ed {
  width: 100%;
}
.fxd__status {
  font-size: 12px;
  line-height: 1.5;
}
.fxd__status.is-hint {
  color: var(--el-text-color-placeholder);
}
.fxd__status.is-ok {
  color: var(--el-color-success);
}
.fxd__status.is-err {
  color: var(--el-color-danger);
}
.fxd__warn {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-top: -6px;
}
.fxd__section {
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 10px;
}
.fxd__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
}
.fxd__label-sub {
  font-weight: 400;
  color: var(--el-text-color-placeholder);
  margin-left: 4px;
}
.fxd__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.fxd__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition: all 0.15s;
  min-height: 24px;
}
.fxd__chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.fxd__chip-title {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fxd__chip-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--el-text-color-secondary);
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fxd__chip:hover .fxd__chip-name {
  color: var(--el-color-primary);
}
.fxd__chip--fn .fxd__chip-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.fxd__chip--op {
  min-width: 30px;
  justify-content: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-weight: 600;
}
.fxd__empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.fxd__try {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.fxd__try-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}
.fxd__try-item :deep(.el-input) {
  width: 96px;
}
.fxd__try-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fxd__try-eq {
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.fxd__try-result {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}
.fxd__try-result.is-empty {
  color: var(--el-text-color-placeholder);
  font-weight: 400;
}
.fxd__footer {
  display: flex;
  align-items: center;
}
.fxd__footer-spacer {
  flex: 1;
}
</style>
