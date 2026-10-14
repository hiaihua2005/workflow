<template>
  <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly">
    <el-divider content-position="left">基本信息</el-divider>

    <el-form-item label="节点ID">
      <el-input v-model="config.id" disabled />
    </el-form-item>

    <el-form-item label="节点名称">
      <el-input v-model="config.name" placeholder="如：金额判断" @change="updateBpmn" />
    </el-form-item>

    <el-divider content-position="left">
      <FormLabelTip :tip="gatewayTip">网关说明</FormLabelTip>
    </el-divider>

    <el-form-item label="描述">
      <el-input
        v-model="config.description"
        type="textarea"
        :rows="2"
        placeholder="请输入网关描述"
      />
    </el-form-item>

    <!-- 默认流：所有分支条件都不满足时的兜底路径（排他/包含网关） -->
    <template v-if="gatewayType === 'exclusive' || gatewayType === 'inclusive'">
      <el-divider content-position="left">默认流</el-divider>

      <el-form-item label="默认流">
        <template #label>
          <FormLabelTip tip="所有分支条件都不满足时走默认流；设为默认流的连线上的条件会被自动清除">
            默认流
          </FormLabelTip>
        </template>
        <el-select
          v-model="config.defaultFlow"
          clearable
          placeholder="未设置（所有分支均不满足时报错）"
          style="width: 100%"
          @change="updateDefaultFlow"
        >
          <el-option
            v-for="f in outgoingFlows"
            :key="f.id"
            :label="f.label"
            :value="f.id"
          />
        </el-select>
      </el-form-item>
      <div v-if="outgoingFlows.length === 0" class="default-flow-hint">
        网关暂无出口连线，请先在画布上连线
      </div>
    </template>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, computed, ref, onMounted, watch } from 'vue'
import { useDesignerStore } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import FormLabelTip from './shared/FormLabelTip.vue'

defineProps<{ readOnly?: boolean }>()

const designerStore = useDesignerStore()

const config = reactive({
  id: '',
  name: '',
  description: '',
  defaultFlow: ''
})

/** 出口连线选项（名称 + 条件标记） */
const outgoingFlows = ref<{ id: string; label: string }[]>([])

const gatewayType = computed(() => {
  const type = designerStore.selectedNodeType || ''
  if (type.includes('Exclusive')) return 'exclusive'
  if (type.includes('Parallel')) return 'parallel'
  if (type.includes('Inclusive')) return 'inclusive'
  return ''
})

const gatewayTip = computed(() => {
  switch (gatewayType.value) {
    case 'exclusive': return '排他网关：根据条件选择一条路径执行（XOR）'
    case 'parallel': return '并行网关：所有路径同时执行（AND）'
    case 'inclusive': return '包含网关：满足条件的路径同时执行（OR）'
    default: return ''
  }
})

onMounted(() => {
  loadConfig()
})

// 切换同类型节点时重新加载配置
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
  config.description = ''
  config.defaultFlow = (bo as any).default || ''

  const docs = bo.documentation
  if (Array.isArray(docs) && docs.length > 0) {
    config.description = docs[0].text || ''
  }

  refreshOutgoingFlows(element)
}

function refreshOutgoingFlows(element: any) {
  const outgoing: any[] = (element.businessObject as any).outgoing || []
  outgoingFlows.value = outgoing.map((f) => ({
    id: f.id,
    label: f.name ? `${f.name}（${f.id}）` : f.id,
  }))
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

/**
 * 设置/清除网关默认流。
 * BPMN 规范：默认流不应带条件 —— 设为默认流时自动清除该连线上的条件表达式。
 */
function updateDefaultFlow() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return

  const newDefault = config.defaultFlow || undefined
  const oldDefault = (element.businessObject as any).default

  modeling.updateProperties(element, { default: newDefault })

  // 清除新默认流上的条件（重复设置为同一值时跳过）
  if (newDefault && newDefault !== oldDefault) {
    const flowEl = elementRegistry.get(newDefault)
    if (flowEl?.businessObject?.conditionExpression) {
      modeling.updateProperties(flowEl, { conditionExpression: undefined })
    }
  }
}
</script>

<style scoped>
.default-flow-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary, #8b91ab);
  margin: -8px 0 8px;
}
</style>
