<template>
  <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly">
    <el-divider content-position="left">基本信息</el-divider>
    <el-form-item label="节点ID"><el-input v-model="config.id" disabled /></el-form-item>
    <el-form-item label="节点名称">
      <el-input v-model="config.name" placeholder="如：入职处理" @change="updateBpmn" />
    </el-form-item>
    <el-form-item label="描述">
      <el-input v-model="config.description" type="textarea" :rows="3" @change="saveConfig" />
    </el-form-item>

    <el-divider content-position="left">变量绑定</el-divider>

    <el-form-item label="入参">
      <template #label>
        <FormLabelTip tip="子流程启动时：把流程变量复制为子流程内变量（隔离同名遮蔽）">入参</FormLabelTip>
      </template>
      <div class="mapping-block">
        <div v-for="(p, idx) in config.inParams" :key="idx" class="mapping-row">
          <el-input v-model="p.source" placeholder="流程变量" @change="saveConfig" />
          <span class="mapping-arrow">→</span>
          <el-input v-model="p.target" placeholder="子流程内变量" @change="saveConfig" />
          <el-button link type="danger" :disabled="readOnly" @click="removeAt(config.inParams, idx)">删除</el-button>
        </div>
        <el-button size="small" :disabled="readOnly" @click="addAt(config.inParams)">+ 添加入参</el-button>
      </div>
    </el-form-item>

    <el-form-item label="出参">
      <template #label>
        <FormLabelTip tip="子流程结束时：把子流程内变量回写到流程变量">出参</FormLabelTip>
      </template>
      <div class="mapping-block">
        <div v-for="(p, idx) in config.outParams" :key="idx" class="mapping-row">
          <el-input v-model="p.source" placeholder="子流程内变量" @change="saveConfig" />
          <span class="mapping-arrow">→</span>
          <el-input v-model="p.target" placeholder="流程变量" @change="saveConfig" />
          <el-button link type="danger" :disabled="readOnly" @click="removeAt(config.outParams, idx)">删除</el-button>
        </div>
        <el-button size="small" :disabled="readOnly" @click="addAt(config.outParams)">+ 添加出参</el-button>
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, onMounted, watch } from 'vue'
import { useDesignerStore } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import FormLabelTip from './shared/FormLabelTip.vue'

defineProps<{ readOnly?: boolean }>()

const designerStore = useDesignerStore()
const config = reactive({
  id: '',
  name: '',
  description: '',
  inParams: [] as { source: string; target: string }[],
  outParams: [] as { source: string; target: string }[]
})

function loadConfig() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (!element) return
  const bo = element.businessObject
  config.id = element.id
  config.name = bo.name || ''
  const existing = designerStore.getNodeConfig(element.id)
  config.description = existing?.basic?.description || ''
  config.inParams = (existing?.subProcess?.inParams ?? []).map((p) => ({ ...p }))
  config.outParams = (existing?.subProcess?.outParams ?? []).map((p) => ({ ...p }))
}

function updateBpmn() {
  const modeler = getModeler()
  const modeling = (modeler as any).get('modeling')
  const element = (modeler as any).get('elementRegistry').get(designerStore.selectedNodeId)
  if (element) modeling.updateProperties(element, { name: config.name })
  saveConfig()
}

function saveConfig() {
  if (!designerStore.selectedNodeId) return
  const existing = designerStore.getNodeConfig(designerStore.selectedNodeId) || {}
  // 仅在配置了映射时写入 subProcess 块（保持旧配置形状不变）
  const hasIn = config.inParams.some((p) => p.source.trim() || p.target.trim())
  const hasOut = config.outParams.some((p) => p.source.trim() || p.target.trim())
  designerStore.setNodeConfig(designerStore.selectedNodeId, {
    ...existing,
    basic: { name: config.name, description: config.description },
    ...(hasIn || hasOut
      ? {
          subProcess: {
            ...(hasIn ? { inParams: config.inParams.filter((p) => p.source.trim() || p.target.trim()) } : {}),
            ...(hasOut ? { outParams: config.outParams.filter((p) => p.source.trim() || p.target.trim()) } : {}),
          },
        }
      : {}),
  })
}

function addAt(list: { source: string; target: string }[]) {
  list.push({ source: '', target: '' })
  saveConfig()
}

function removeAt(list: { source: string; target: string }[], idx: number) {
  list.splice(idx, 1)
  saveConfig()
}

onMounted(loadConfig)
watch(() => designerStore.selectedNodeId, (n, o) => { if (n && n !== o) loadConfig() })
</script>

<style scoped>
.mapping-block {
  width: 100%;
}

.mapping-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.mapping-arrow {
  flex-shrink: 0;
  color: var(--el-text-color-secondary, #8b91ab);
}
</style>
