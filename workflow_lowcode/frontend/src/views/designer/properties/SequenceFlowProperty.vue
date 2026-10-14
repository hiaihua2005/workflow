<template>
  <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly">
    <el-divider content-position="left">基本信息</el-divider>

    <el-form-item label="连线ID">
      <el-input v-model="config.id" disabled />
    </el-form-item>

    <el-form-item label="连线名称">
      <el-input v-model="config.name" placeholder="如：同意" @change="updateBpmn" />
    </el-form-item>

    <el-divider content-position="left">条件配置</el-divider>

    <el-form-item label="条件类型">
      <el-radio-group v-model="condition.type" @change="onConditionTypeChange">
        <el-radio value="none">无条件</el-radio>
        <el-radio value="form">表单条件</el-radio>
        <el-radio value="expression">条件表达式</el-radio>
      </el-radio-group>
    </el-form-item>

    <!-- 表单条件：字段 + 运算符 + 值，无需手写 UEL -->
    <template v-if="condition.type === 'form'">
      <el-form-item label="多行组合">
        <el-radio-group v-model="condition.join" @change="updateCondition">
          <el-radio value="and">且（AND）</el-radio>
          <el-radio value="or">或（OR）</el-radio>
        </el-radio-group>
      </el-form-item>

      <div v-for="(row, idx) in condition.rows" :key="idx" class="form-cond-row">
        <el-select
          v-model="row.field"
          filterable
          allow-create
          default-first-option
          placeholder="字段/变量"
          class="cond-field"
          @change="updateCondition"
        >
          <el-option v-for="v in uelVars" :key="v.value" :label="v.label" :value="v.value" />
        </el-select>

        <el-select v-model="row.operator" class="cond-op" @change="updateCondition">
          <el-option label="等于" value="==" />
          <el-option label="不等于" value="!=" />
          <el-option label="大于" value=">" />
          <el-option label="大于等于" value=">=" />
          <el-option label="小于" value="<" />
          <el-option label="小于等于" value="<=" />
          <el-option label="包含" value="contains" />
          <el-option label="为空" value="empty" />
          <el-option label="不为空" value="notEmpty" />
        </el-select>

        <el-input
          v-if="!isEmptyOp(row.operator)"
          v-model="row.value"
          placeholder="值（数字/true/false/文本）"
          class="cond-value"
          @change="updateCondition"
        />

        <el-button
          v-if="condition.rows.length > 1"
          link
          type="danger"
          :disabled="readOnly"
          @click="removeRow(idx)"
        >
          删除
        </el-button>
      </div>

      <el-button size="small" :disabled="readOnly" @click="addRow">+ 添加条件</el-button>

      <el-form-item v-if="condition.expression" label="生成表达式">
        <el-input :model-value="condition.expression" disabled />
      </el-form-item>
    </template>

    <el-form-item v-if="condition.type === 'expression'" label="条件表达式">
      <template #label>
        <FormLabelTip tip="Flowable UEL 表达式，支持变量比较、方法调用等">条件表达式</FormLabelTip>
      </template>
      <ExpressionEditor
        v-model="condition.expression"
        syntax="uel"
        multiline
        :rows="3"
        :variables="uelVars"
        placeholder="如：${amount > 10000}"
        @change="updateCondition"
      />
    </el-form-item>

    <el-form-item v-if="condition.type === 'expression'" label="常用条件">
      <el-select
        v-model="condition.preset"
        placeholder="选择预设条件"
        clearable
        style="width: 100%"
        @change="applyPreset"
      >
        <el-option label="同意" value="${approved == true}" />
        <el-option label="拒绝" value="${approved == false}" />
        <el-option label="金额大于1万" value="${amount > 10000}" />
        <el-option label="金额大于10万" value="${amount > 100000}" />
      </el-select>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, onMounted, watch } from 'vue'
import { useDesignerStore } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import FormLabelTip from './shared/FormLabelTip.vue'
import ExpressionEditor from '@/components/expression/ExpressionEditor.vue'
import { UEL_FLOW_VARS } from '@/components/expression/uelVars'

/** UEL_FLOW_VARS（树形变量表）拍平为字段下拉选项（可自由输入补充流程变量名） */
const uelVars = computed(() => {
  const out: { label: string; value: string }[] = []
  for (const item of UEL_FLOW_VARS) {
    if (Array.isArray(item.children) && item.children.length > 0) {
      for (const child of item.children) {
        out.push({ label: child.detail ? `${child.name}（${child.detail}）` : child.name, value: child.name })
      }
    } else if (item.name) {
      out.push({ label: item.detail ? `${item.name}（${item.detail}）` : item.name, value: item.name })
    }
  }
  return out
})

defineProps<{ readOnly?: boolean }>()

const designerStore = useDesignerStore()

const config = reactive({
  id: '',
  name: ''
})

/** 表单条件行：字段 + 运算符 + 值 */
interface FormCondRow {
  field: string
  operator: string
  value: string
}

const condition = reactive({
  type: 'none' as 'none' | 'form' | 'expression',
  expression: '',
  preset: '',
  /** 表单条件行（type=form） */
  rows: [] as FormCondRow[],
  /** 多行组合：and / or */
  join: 'and' as 'and' | 'or'
})

onMounted(() => {
  loadConfig()
})

// 切换同类型连线时重新加载配置
watch(() => designerStore.selectedNodeId, (newId, oldId) => {
  if (newId && newId !== oldId) {
    loadConfig()
  }
})

function loadConfig() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  const bo = element.businessObject
  config.id = element.id
  config.name = bo.name || ''

  // 重置条件，避免残留上一连线
  condition.type = 'none'
  condition.expression = ''
  condition.preset = ''
  condition.rows = []
  condition.join = 'and'

  // 读取条件表达式
  const cond = bo.conditionExpression
  if (cond) {
    if (typeof cond === 'object') {
      condition.expression = cond.body || ''
    } else if (typeof cond === 'string') {
      condition.expression = cond
    }
    if (condition.expression) {
      // 能完整解析为结构化行 → 表单条件模式；否则表达式模式
      const parsed = decodeFormCondition(condition.expression)
      if (parsed) {
        condition.rows = parsed.rows
        condition.join = parsed.join
        condition.type = 'form'
      } else {
        condition.type = 'expression'
      }
    }
  }
}

function updateBpmn() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (element) {
    modeling.updateProperties(element, { name: config.name })
  }
}

function updateCondition() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const moddle = (modeler as any).get('moddle')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  let body = ''
  if (condition.type === 'form') {
    body = buildFormCondition()
    condition.expression = body
  } else if (condition.type === 'expression') {
    body = condition.expression
  }

  if (body) {
    const cond = moddle.create('bpmn:FormalExpression', { body })
    modeling.updateProperties(element, { conditionExpression: cond })
  } else {
    modeling.updateProperties(element, { conditionExpression: undefined })
  }
}

/** 条件类型切换：expression 模式保留已生成的表达式便于微调 */
function onConditionTypeChange() {
  if (condition.type === 'expression' && !condition.expression && condition.rows.length > 0) {
    condition.expression = buildFormCondition()
  }
  updateCondition()
}

function applyPreset(value: string) {
  if (value) {
    condition.expression = value
    updateCondition()
  }
}

function addRow() {
  condition.rows.push({ field: '', operator: '==', value: '' })
}

function removeRow(idx: number) {
  condition.rows.splice(idx, 1)
  updateCondition()
}

function isEmptyOp(op: string): boolean {
  return op === 'empty' || op === 'notEmpty'
}

/** 值字面量：数字/布尔裸写，字符串单引号转义 */
function valueLiteral(raw: string): string {
  const v = (raw ?? '').trim()
  if (/^-?\d+(\.\d+)?$/.test(v) || v === 'true' || v === 'false') {
    return v
  }
  return `'${v.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`
}

/** 结构化行 → UEL 表达式；字段缺失的行跳过 */
function buildFormCondition(): string {
  const parts: string[] = []
  for (const row of condition.rows) {
    const field = (row.field || '').trim()
    if (!field) continue
    const op = row.operator
    const v = (row.value || '').trim()
    if (op === 'empty') {
      parts.push(`(${field} == null || ${field} == '')`)
    } else if (op === 'notEmpty') {
      parts.push(`(${field} != null && ${field} != '')`)
    } else if (op === 'contains') {
      parts.push(`${field}.contains(${valueLiteral(v)})`)
    } else {
      parts.push(`${field} ${op} ${valueLiteral(v)}`)
    }
  }
  if (parts.length === 0) return ''
  const joiner = condition.join === 'or' ? ' || ' : ' && '
  return parts.length === 1 ? `\${${parts[0]}}` : `\${${parts.join(joiner)}}`
}

/**
 * UEL 表达式 → 结构化行（可完整解析时返回行与组合方式，否则 null 回落表达式模式）。
 * 支持：比较运算、contains、empty/notEmpty、单层 and/or 组合（与生成格式对应）。
 */
function decodeFormCondition(expression: string): { rows: FormCondRow[]; join: 'and' | 'or' } | null {
  let body = expression.trim()
  if (!body.startsWith('${') || !body.endsWith('}')) return null
  body = body.slice(2, -1)

  // 单层 and / or：不允许混用（生成器只产生单一组合方式）
  const andParts = splitTopLevel(body, '&&')
  const orParts = splitTopLevel(body, '||')
  let atoms: string[]
  let join: 'and' | 'or'
  if (andParts.length > 1 && orParts.length > 1) return null
  if (andParts.length > 1) {
    atoms = andParts
    join = 'and'
  } else if (orParts.length > 1) {
    atoms = orParts
    join = 'or'
  } else {
    atoms = [body]
    join = 'and'
  }

  const rows: FormCondRow[] = []
  for (const atom of atoms) {
    const row = decodeAtom(atom.trim())
    if (!row) return null
    rows.push(row)
  }
  return rows.length > 0 ? { rows, join } : null
}

/** 按分隔符切分（忽略字符串字面量与括号内的分隔符） */
function splitTopLevel(body: string, sep: '&&' | '||'): string[] {
  const parts: string[] = []
  let depth = 0
  let inString: string | null = null
  let start = 0
  for (let i = 0; i < body.length; i++) {
    const ch = body[i]
    if (inString) {
      if (ch === '\\') i++
      else if (ch === inString) inString = null
      continue
    }
    if (ch === "'" || ch === '"') {
      inString = ch
    } else if (ch === '(') {
      depth++
    } else if (ch === ')') {
      depth--
    } else if (depth === 0 && ch === sep[0] && body[i + 1] === sep[1]) {
      parts.push(body.slice(start, i))
      i++
      start = i + 1
    }
  }
  parts.push(body.slice(start))
  return parts
}

/** 单个原子条件解析；不可识别返回 null */
function decodeAtom(atom: string): FormCondRow | null {
  // (f == null || f == '') / (f != null && f != '')
  const emptyM = atom.match(/^\(\s*(\w+)\s*==\s*null\s*\|\|\s*\1\s*==\s*''\s*\)$/)
  if (emptyM) return { field: emptyM[1], operator: 'empty', value: '' }
  const notEmptyM = atom.match(/^\(\s*(\w+)\s*!=\s*null\s*&&\s*\1\s*!=\s*''\s*\)$/)
  if (notEmptyM) return { field: notEmptyM[1], operator: 'notEmpty', value: '' }

  const containsM = atom.match(/^(\w+)\.contains\((.*)\)$/)
  if (containsM) {
    const v = unquote(containsM[2])
    return v === null ? null : { field: containsM[1], operator: 'contains', value: v }
  }

  const cmpM = atom.match(/^(\w+)\s*(==|!=|>=|<=|>|<)\s*(.+)$/)
  if (cmpM) {
    const v = unquote(cmpM[3].trim())
    return v === null ? null : { field: cmpM[1], operator: cmpM[2], value: v }
  }
  return null
}

/** 去掉字符串引号（仅生成器格式的单引号字面量），其他原样返回 */
function unquote(raw: string): string | null {
  const v = raw.trim()
  const m = v.match(/^'((?:[^'\\]|\\.)*)'$/)
  if (m) {
    return m[1].replace(/\\'/g, "'").replace(/\\\\/g, '\\')
  }
  return v
}
</script>

<style scoped>
/* 表单条件行：字段 + 运算符 + 值 + 删除 */
.form-cond-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px 0;
  padding-left: 0;
}

.cond-field {
  flex: 1.2;
  min-width: 0;
}

.cond-op {
  width: 92px;
  flex-shrink: 0;
}

.cond-value {
  flex: 1;
  min-width: 0;
}
</style>
