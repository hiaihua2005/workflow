<template>
  <el-form label-width="7em" label-position="left" size="small" :disabled="readOnly">
    <el-divider content-position="left">基本信息</el-divider>

    <el-form-item label="节点ID">
      <el-input v-model="config.id" disabled />
    </el-form-item>

    <el-form-item label="节点名称">
      <el-input v-model="config.name" placeholder="如：发送通知" @change="saveConfig" />
    </el-form-item>

    <el-divider content-position="left">服务配置</el-divider>

    <el-form-item label="服务类型">
      <el-select v-model="config.serviceType" placeholder="请选择" style="width: 100%" @change="saveConfig">
        <el-option label="Java 服务调用" value="java" />
        <el-option label="表达式" value="expression" />
        <el-option label="调用逻辑流" value="logicflow" />
        <el-option label="HTTP 调用" value="http" />
      </el-select>
    </el-form-item>

    <!-- 调用逻辑流：选择已发布逻辑流 + 入参/出参映射 + 失败策略 -->
    <template v-if="config.serviceType === 'logicflow'">
      <el-form-item label="逻辑流">
        <el-select
          v-model="config.logicFlowKey"
          filterable
          placeholder="选择已发布的逻辑流"
          style="width: 100%"
          :loading="logicFlowLoading"
          @change="saveConfig"
        >
          <el-option
            v-for="f in publishedLogicFlows"
            :key="f.flowKey"
            :label="`${f.name}（${f.flowKey}）`"
            :value="f.flowKey"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="入参映射">
        <div class="mapping-block">
          <div v-for="(p, idx) in config.inParams" :key="idx" class="mapping-row">
            <el-input v-model="p.source" placeholder="流程变量" @change="saveConfig" />
            <span class="mapping-arrow">→</span>
            <el-input v-model="p.target" placeholder="逻辑流变量" @change="saveConfig" />
            <el-button link type="danger" :disabled="readOnly" @click="config.inParams.splice(idx, 1); saveConfig()">删除</el-button>
          </div>
          <el-button size="small" :disabled="readOnly" @click="config.inParams.push({ source: '', target: '' }); saveConfig()">
            + 添加入参
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="出参映射">
        <div class="mapping-block">
          <div v-for="(p, idx) in config.outParams" :key="idx" class="mapping-row">
            <el-input v-model="p.source" placeholder="逻辑流变量" @change="saveConfig" />
            <span class="mapping-arrow">→</span>
            <el-input v-model="p.target" placeholder="流程变量" @change="saveConfig" />
            <el-button link type="danger" :disabled="readOnly" @click="config.outParams.splice(idx, 1); saveConfig()">删除</el-button>
          </div>
          <el-button size="small" :disabled="readOnly" @click="config.outParams.push({ source: '', target: '' }); saveConfig()">
            + 添加出参
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="失败策略">
        <template #label>
          <FormLabelTip tip="FAIL_FLOW=流程失败（可被错误边界事件捕获）；IGNORE_CONTINUE=写 __lastError 变量后继续">
            失败策略
          </FormLabelTip>
        </template>
        <el-radio-group v-model="config.errorAction" @change="saveConfig">
          <el-radio value="FAIL_FLOW">失败阻断</el-radio>
          <el-radio value="IGNORE_CONTINUE">失败继续</el-radio>
        </el-radio-group>
      </el-form-item>
    </template>

    <el-form-item v-if="config.serviceType === 'java'" label="Bean 名称">
      <el-input v-model="config.beanName" placeholder="如：notifyService" @change="saveConfig" />
    </el-form-item>

    <el-form-item v-if="config.serviceType === 'java'" label="方法名">
      <el-input v-model="config.methodName" placeholder="如：sendNotification（缺省时按 Delegate Bean 调用）" @change="saveConfig" />
    </el-form-item>

    <el-form-item v-if="config.serviceType === 'expression'" label="表达式">
      <ExpressionEditor
        v-model="config.expression"
        syntax="uel"
        multiline
        :rows="2"
        :variables="uelVars"
        placeholder="如：notifyService.sendNotification(execution)"
        @change="saveConfig"
      />
    </el-form-item>

    <el-form-item v-if="config.serviceType === 'http'" label="URL">
      <el-input v-model="config.url" placeholder="https://..." @change="saveConfig" />
    </el-form-item>

    <el-form-item v-if="config.serviceType === 'http'" label="请求方法">
      <el-select v-model="config.httpMethod" style="width: 100%" @change="saveConfig">
        <el-option label="GET" value="GET" />
        <el-option label="POST" value="POST" />
        <el-option label="PUT" value="PUT" />
        <el-option label="DELETE" value="DELETE" />
      </el-select>
    </el-form-item>

    <template v-if="config.serviceType === 'http'">
      <el-alert type="info" :closable="false" show-icon class="http-tip">
        引擎暂无内置 HTTP Delegate：HTTP 调用请改用「调用逻辑流」或节点的后端逻辑实现；此处配置仅存档并应用异步/重试。
      </el-alert>
    </template>

    <el-divider content-position="left">执行与重试</el-divider>

    <el-form-item label="异步执行">
      <template #label>
        <FormLabelTip tip="开启后任务由 Job 执行器异步运行，不阻塞流程推进线程；配置重试时自动开启">
          异步执行
        </FormLabelTip>
      </template>
      <el-switch v-model="config.async" :disabled="readOnly || config.retryCount > 0" @change="saveConfig" />
      <span v-if="config.retryCount > 0" class="async-hint">已配置重试，自动异步</span>
    </el-form-item>

    <el-form-item label="失败重试">
      <template #label>
        <FormLabelTip tip="服务调用抛异常时的自动重试次数（0 = 不重试）；需异步执行">
          失败重试
        </FormLabelTip>
      </template>
      <el-input-number
        v-model="config.retryCount"
        :min="0"
        :max="10"
        :disabled="readOnly"
        @change="saveConfig"
      />
    </el-form-item>

    <el-form-item v-if="config.retryCount > 0" label="重试间隔">
      <template #label>
        <FormLabelTip tip="每次重试的固定间隔（分钟）">
          重试间隔
        </FormLabelTip>
      </template>
      <el-input-number
        v-model="config.retryIntervalMinutes"
        :min="1"
        :max="1440"
        :disabled="readOnly"
        @change="saveConfig"
      />
      <span class="async-hint">分钟</span>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, watch } from 'vue'
import { useDesignerStore } from '@/stores/designerStore'
import { getModeler } from '../utils/bpmnModeler'
import ExpressionEditor from '@/components/expression/ExpressionEditor.vue'
import { UEL_FLOW_VARS } from '@/components/expression/uelVars'
import FormLabelTip from './shared/FormLabelTip.vue'
import { logicFlowApi, type LogicFlowSummary } from '@/api/logicFlow'

const uelVars = UEL_FLOW_VARS

defineProps<{ readOnly?: boolean }>()

const designerStore = useDesignerStore()

const config = reactive({
  id: '',
  name: '',
  serviceType: 'java' as 'java' | 'expression' | 'logicflow' | 'http',
  beanName: '',
  methodName: '',
  expression: '',
  url: '',
  httpMethod: 'POST' as 'GET' | 'POST' | 'PUT' | 'DELETE',
  /** 调用逻辑流配置 */
  logicFlowKey: '',
  inParams: [] as { source: string; target: string }[],
  outParams: [] as { source: string; target: string }[],
  errorAction: 'FAIL_FLOW' as 'FAIL_FLOW' | 'IGNORE_CONTINUE',
  /** 异步执行（flowable:async） */
  async: false,
  /** 失败重试次数（0 = 不重试） */
  retryCount: 0,
  /** 重试间隔（分钟） */
  retryIntervalMinutes: 10
})

/** 已发布逻辑流选项（下拉用） */
const publishedLogicFlows = ref<LogicFlowSummary[]>([])
const logicFlowLoading = ref(false)

async function loadPublishedLogicFlows() {
  if (publishedLogicFlows.value.length > 0) return
  logicFlowLoading.value = true
  try {
    const res = await logicFlowApi.list({ page: 1, size: 100 })
    publishedLogicFlows.value = (res.data?.rows ?? []).filter((f) => f.status === 'PUBLISHED')
  } catch {
    publishedLogicFlows.value = []
  } finally {
    logicFlowLoading.value = false
  }
}

let isLoading = false

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

  // 重置为默认值，避免残留上一节点
  config.name = ''
  config.serviceType = 'java'
  config.beanName = ''
  config.methodName = ''
  config.expression = ''
  config.url = ''
  config.httpMethod = 'POST'
  config.logicFlowKey = ''
  config.inParams = []
  config.outParams = []
  config.errorAction = 'FAIL_FLOW'
  config.async = false
  config.retryCount = 0
  config.retryIntervalMinutes = 10

  config.id = element.id
  config.name = bo.name || ''

  const existing = designerStore.getNodeConfig(designerStore.selectedNodeId!)
  if (existing?.serviceTask) {
    const st = existing.serviceTask
    config.serviceType = st.serviceType || 'java'
    config.beanName = st.beanName || ''
    config.methodName = st.methodName || ''
    config.expression = st.expression || ''
    config.url = st.url || ''
    config.httpMethod = st.httpMethod || 'POST'
    config.logicFlowKey = st.logicFlow?.flowKey || ''
    config.inParams = (st.logicFlow?.inParams ?? []).map((p) => ({ ...p }))
    config.outParams = (st.logicFlow?.outParams ?? []).map((p) => ({ ...p }))
    config.errorAction = st.logicFlow?.errorAction || 'FAIL_FLOW'
    config.async = st.async ?? false
    config.retryCount = st.retryCount ?? 0
    config.retryIntervalMinutes = st.retryIntervalMinutes ?? 10
  }
  if (config.serviceType === 'logicflow') {
    loadPublishedLogicFlows()
  }
  isLoading = false
}

function updateBpmnName() {
  const modeler = getModeler()
  const elementRegistry = (modeler as any).get('elementRegistry')
  const modeling = (modeler as any).get('modeling')
  const element = elementRegistry.get(designerStore.selectedNodeId)
  if (element) {
    modeling.updateProperties(element, { name: config.name })
  }
}

/**
 * 保存节点配置（此前本面板仅更新节点名称，服务配置不落盘；
 * 部署改写器读取 serviceTask 块注入实现/异步/重试）。
 */
function saveConfig() {
  const nodeId = designerStore.selectedNodeId
  if (!nodeId || isLoading) return
  updateBpmnName()

  const existing = designerStore.getNodeConfig(nodeId) || {}
  if (config.serviceType === 'logicflow') {
    loadPublishedLogicFlows()
  }
  designerStore.setNodeConfig(nodeId, {
    ...existing,
    basic: { ...(existing.basic || {}), name: config.name },
    serviceTask: {
      serviceType: config.serviceType,
      ...(config.serviceType === 'java' ? { beanName: config.beanName, methodName: config.methodName } : {}),
      ...(config.serviceType === 'expression' ? { expression: config.expression } : {}),
      ...(config.serviceType === 'logicflow'
        ? {
            logicFlow: {
              flowKey: config.logicFlowKey,
              ...(config.inParams.length > 0 ? { inParams: config.inParams.map((p) => ({ ...p })) } : {}),
              ...(config.outParams.length > 0 ? { outParams: config.outParams.map((p) => ({ ...p })) } : {}),
              errorAction: config.errorAction,
            },
          }
        : {}),
      ...(config.serviceType === 'http' ? { url: config.url, httpMethod: config.httpMethod } : {}),
      async: config.async || config.retryCount > 0,
      retryCount: Math.max(0, Math.round(config.retryCount || 0)),
      retryIntervalMinutes: Math.max(1, Math.round(config.retryIntervalMinutes || 10)),
    },
  })
}
</script>

<style scoped>
.http-tip {
  margin: 0 0 12px;
}

.async-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #8b91ab);
}

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
