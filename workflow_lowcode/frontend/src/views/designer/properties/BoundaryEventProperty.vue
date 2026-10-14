<template>
  <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly">
    <el-divider content-position="left">基本信息</el-divider>

    <el-form-item label="节点ID">
      <el-input v-model="config.id" disabled />
    </el-form-item>

    <el-form-item label="事件名称">
      <el-input v-model="config.name" placeholder="如：超时升级" @change="updateBpmn" />
    </el-form-item>

    <el-form-item label="附着节点">
      <el-input :model-value="hostLabel" disabled />
    </el-form-item>

    <el-form-item label="中断行为">
      <template #label>
        <FormLabelTip tip="开启=中断：触发时取消附着节点上未完成的任务；关闭=非中断：原任务继续，分支并行执行">
          中断行为
        </FormLabelTip>
      </template>
      <el-radio-group v-model="config.cancelActivity" @change="updateBoundaryProps">
        <el-radio :value="true">中断原任务</el-radio>
        <el-radio :value="false">不中断（并行）</el-radio>
      </el-radio-group>
    </el-form-item>

    <!-- 定时边界事件 -->
    <template v-if="isTimer">
      <el-divider content-position="left">定时设置</el-divider>

      <el-form-item label="超时时长">
        <template #label>
          <FormLabelTip tip="任务创建（或子流程进入）后经过该时长触发；与节点超时规则互不冲突">
            超时时长
          </FormLabelTip>
        </template>
        <div class="timer-row">
          <el-input-number v-model="timer.duration" :min="1" @change="updateBoundaryProps" />
          <el-select v-model="timer.unit" style="width: 90px" @change="updateBoundaryProps">
            <el-option label="分钟" value="M" />
            <el-option label="小时" value="H" />
            <el-option label="天" value="D" />
          </el-select>
        </div>
        <div class="timer-hint">生成 ISO-8601 周期：{{ durationIso }}</div>
      </el-form-item>
    </template>

    <!-- 错误边界事件 -->
    <template v-if="isError">
      <el-divider content-position="left">错误设置</el-divider>

      <el-form-item label="错误代码">
        <template #label>
          <FormLabelTip tip="可捕获的错误代码（BpmnError 的 errorCode）；留空 = 捕获任意错误。当前「调用逻辑流」节点失败时抛出 LOGIC_FLOW_FAILED">
            错误代码
          </FormLabelTip>
        </template>
        <el-input
          v-model="config.errorCode"
          placeholder="如 LOGIC_FLOW_FAILED（留空捕获任意错误）"
          @change="updateBoundaryProps"
        />
      </el-form-item>
    </template>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, computed, onMounted, watch } from 'vue'
import { useDesignerStore } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import FormLabelTip from './shared/FormLabelTip.vue'

defineProps<{ readOnly?: boolean }>()

const designerStore = useDesignerStore()

const config = reactive({
  id: '',
  name: '',
  cancelActivity: true,
  errorCode: ''
})

const timer = reactive({ duration: 1, unit: 'H' as 'M' | 'H' | 'D' })

const bo = computed(() => {
  try {
    const modeler = getModeler()
    const elementRegistry = (modeler as any).get('elementRegistry')
    const element = elementRegistry.get(designerStore.selectedNodeId)
    return element?.businessObject ?? null
  } catch {
    return null
  }
})

const isTimer = computed(() => {
  const defs = bo.value?.eventDefinitions ?? []
  return defs.some((d: any) => d.$type === 'bpmn:TimerEventDefinition')
})

const isError = computed(() => {
  const defs = bo.value?.eventDefinitions ?? []
  return defs.some((d: any) => d.$type === 'bpmn:ErrorEventDefinition')
})

const hostLabel = computed(() => {
  const host = bo.value?.attachedToRef
  if (!host) return '未附着（请重新拖到任务上方创建）'
  return host.name ? `${host.name}（${host.id}）` : host.id
})

/** 超时时长 → ISO-8601（PT{n}M / PT{n}H / PT{n}D） */
const durationIso = computed(() => {
  const d = Math.max(1, Math.round(timer.duration || 1))
  return `PT${d}${timer.unit}`
})

onMounted(loadConfig)

watch(() => designerStore.selectedNodeId, (newId, oldId) => {
  if (newId && newId !== oldId) loadConfig()
})

function loadConfig() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  const businessObject = element.businessObject
  config.id = element.id
  config.name = businessObject.name || ''
  config.cancelActivity = businessObject.cancelActivity !== false
  config.errorCode = ''

  // 读取事件定义
  const defs = businessObject.eventDefinitions ?? []
  for (const def of defs) {
    if (def.$type === 'bpmn:TimerEventDefinition') {
      const body = def.timeDuration?.body || ''
      const m = body.match(/^PT(\d+)([MHD])$/)
      if (m) {
        timer.duration = Number(m[1])
        timer.unit = m[2] as 'M' | 'H' | 'D'
      }
    } else if (def.$type === 'bpmn:ErrorEventDefinition') {
      config.errorCode = def.errorRef || ''
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

/** 写回事件定义与中断行为（cancelActivity + TimerEventDefinition.timeDuration / ErrorEventDefinition.errorRef） */
function updateBoundaryProps() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const moddle = (modeler as any).get('moddle')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  const props: Record<string, unknown> = { cancelActivity: config.cancelActivity }

  const defs = element.businessObject.eventDefinitions ?? []
  if (isTimer.value) {
    const existing = defs.find((d: any) => d.$type === 'bpmn:TimerEventDefinition')
    const def = existing ?? moddle.create('bpmn:TimerEventDefinition')
    def.timeDuration = moddle.create('bpmn:FormalExpression', { body: durationIso.value })
    if (!existing) {
      props.eventDefinitions = [def]
    }
  } else if (isError.value) {
    const existing = defs.find((d: any) => d.$type === 'bpmn:ErrorEventDefinition')
    const def = existing ?? moddle.create('bpmn:ErrorEventDefinition')
    def.errorRef = config.errorCode?.trim() || undefined
    if (!existing) {
      props.eventDefinitions = [def]
    }
  }

  modeling.updateProperties(element, props)
}
</script>

<style scoped>
.timer-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.timer-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary, #8b91ab);
  margin-top: 4px;
}
</style>
