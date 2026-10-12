<template>
  <div class="p-4" data-testid="ai-config-page">
    <el-row :gutter="16">
      <!-- 左：配置表单 -->
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="h-full">
          <template #header>
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <el-icon class="text-base"><Cpu /></el-icon>
                <span class="font-medium">模型服务配置</span>
                <el-tag v-if="vo" :type="vo.source.startsWith('数据库') ? 'success' : 'info'" size="small">
                  {{ vo.source }}
                </el-tag>
              </div>
              <el-tag v-if="vo" :type="vo.enabled ? 'success' : 'danger'" size="small" effect="plain">
                {{ vo.enabled ? '已启用' : '已停用' }}
              </el-tag>
            </div>
          </template>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="mb-4"
            title="平台接入任意 OpenAI 兼容服务（GLM / DeepSeek / 通义千问 / Ollama 等）"
            description="保存即生效，无需重启；API Key 仅脱敏展示、不回传明文。"
          />

          <el-form
            ref="formRef"
            v-loading="loading"
            :model="form"
            :rules="rules"
            label-width="120px"
            label-position="right"
            @submit.prevent
          >
            <el-form-item label="启用大模型">
              <el-switch v-model="form.enabled" />
              <span class="ml-2 text-xs text-gray-400">停用后逻辑流 LLM 节点、AI 对话等调用将快速失败并提示</span>
            </el-form-item>

            <el-form-item label="端点地址" prop="baseUrl">
              <el-input
                v-model="form.baseUrl"
                placeholder="https://api.deepseek.com/v1"
                clearable
                data-testid="ai-base-url"
              />
              <div class="w-full mt-1 flex flex-wrap gap-1">
                <el-tag
                  v-for="ep in endpointPresets"
                  :key="ep.label"
                  size="small"
                  type="info"
                  effect="plain"
                  class="cursor-pointer select-none"
                  @click="applyEndpoint(ep)"
                >{{ ep.label }}</el-tag>
              </div>
            </el-form-item>

            <el-form-item label="API Key" prop="apiKey">
              <el-input
                v-model="form.apiKey"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="keyPlaceholder"
                data-testid="ai-api-key"
              />
              <div class="w-full mt-1 text-xs text-gray-400">
                {{ vo?.hasApiKey ? `已配置（${vo.apiKeyMasked}）——留空保持不变，输入 "-" 可清除` : '尚未配置；部分内网网关可留空' }}
              </div>
            </el-form-item>

            <el-form-item label="模型名称" prop="model">
              <el-input v-model="form.model" placeholder="glm-4-plus" clearable data-testid="ai-model">
                <template #prepend>model</template>
              </el-input>
              <div class="w-full mt-1 flex flex-wrap gap-1">
                <el-tag
                  v-for="m in MODEL_PRESETS"
                  :key="m"
                  size="small"
                  type="info"
                  effect="plain"
                  class="cursor-pointer select-none"
                  @click="form.model = m"
                >{{ m }}</el-tag>
              </div>
            </el-form-item>

            <el-form-item label="默认温度">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" controls-position="right" />
              <span class="ml-2 text-xs text-gray-400">0~2；分类/抽取类任务建议 ≤0.3，留空用平台默认</span>
            </el-form-item>

            <el-form-item label="最大输出 Token">
              <el-input-number v-model="form.maxTokens" :min="1" :max="200000" :step="256" controls-position="right" />
            </el-form-item>

            <el-collapse class="border-none">
              <el-collapse-item title="高级（超时参数）" name="adv">
                <el-form-item label="连接超时">
                  <el-input-number v-model="form.connectTimeoutMs" :min="500" :max="60000" :step="500" controls-position="right" />
                  <span class="ml-2 text-xs text-gray-400">毫秒</span>
                </el-form-item>
                <el-form-item label="读超时">
                  <el-input-number v-model="form.readTimeoutMs" :min="1000" :max="600000" :step="5000" controls-position="right" />
                  <span class="ml-2 text-xs text-gray-400">毫秒；长回答建议 ≥60s</span>
                </el-form-item>
              </el-collapse-item>
            </el-collapse>

            <el-form-item class="mb-0 pt-2">
              <el-button type="primary" :loading="saving" data-testid="ai-save" @click="onSave">
                保存配置
              </el-button>
              <el-button :loading="testing" data-testid="ai-test" @click="onTest">
                测试连接
              </el-button>
              <el-button text @click="load">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右：状态与说明 -->
      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="mb-4">
          <template #header>
            <div class="flex items-center gap-2">
              <el-icon class="text-base"><Connection /></el-icon>
              <span class="font-medium">连通性</span>
            </div>
          </template>
          <el-alert
            v-if="testResult"
            :type="testResult.ok ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="testResult.ok ? `连接成功 · ${testResult.latencyMs}ms · ${testResult.model}` : '连接失败'"
            :description="testResult.ok ? undefined : testResult.message"
            data-testid="ai-test-result"
          />
          <el-empty v-else description="尚未测试 · 点击「测试连接」用表单当前值验证（不落库）" :image-size="72" />
        </el-card>

        <el-card shadow="never">
          <template #header>
            <span class="font-medium">生效范围与优先级</span>
          </template>
          <el-descriptions :column="1" border size="small" class="mb-3">
            <el-descriptions-item label="当前来源">{{ vo?.source || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最近更新">{{ vo?.updatedAt ? formatTime(vo.updatedAt) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="部署默认端点">
              <span class="break-all">{{ staticBaseUrl || '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <ol class="text-xs text-gray-500 leading-6 list-decimal pl-4">
            <li>页面保存的配置实时生效（最高优先级）；</li>
            <li>未保存过时使用部署默认：环境变量 <code>AI_BASE_URL</code> / <code>AI_API_KEY</code> / <code>AI_MODEL</code> 或 yml；</li>
            <li>作用范围：逻辑流「大模型节点」、AI 对话、AI 助手、表单 AI 生成等全部 AI 能力；</li>
            <li>仅支持 OpenAI 兼容协议（<code>/chat/completions</code>）；保存后可在逻辑流中拖入大模型节点验证。</li>
          </ol>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'AiConfigManagement' })

import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Cpu, Connection } from '@element-plus/icons-vue'
import { getAiConfig, saveAiConfig, testAiConfig } from '@/api/aiConfig'
import type { AiConfigVO, AiConfigTestResult } from '@/types/aiConfig'

const MODEL_PRESETS = ['glm-4-plus', 'deepseek-chat', 'qwen-max', 'moonshot-v1-32k', 'gpt-4o-mini']

/** 端点预设：平台默认项在拿到 VO 后动态填充部署真实默认端点 */
const endpointPresets = ref<{ label: string; url: string }[]>([
  { label: 'DeepSeek', url: 'https://api.deepseek.com/v1' },
  { label: '通义千问', url: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { label: 'Moonshot', url: 'https://api.moonshot.cn/v1' },
  { label: 'Ollama 本机', url: 'http://127.0.0.1:11434/v1' },
])

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const vo = ref<AiConfigVO | null>(null)
const testResult = ref<AiConfigTestResult | null>(null)

const form = reactive({
  enabled: true,
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.7 as number | null,
  maxTokens: 4096 as number | null,
  connectTimeoutMs: 5000,
  readTimeoutMs: 120000,
})

const rules: FormRules = {
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  temperature: [{ type: 'number', min: 0, max: 2, message: '温度范围 0~2', trigger: 'blur' }],
}

const staticBaseUrl = computed(() => vo.value?.staticDefaults?.baseUrl || '')

const keyPlaceholder = computed(() =>
  vo.value?.hasApiKey ? `已配置（${vo.value.apiKeyMasked}）——留空保持不变` : 'sk-...'
)

function applyEndpoint(ep: { label: string; url: string }) {
  form.baseUrl = ep.url || staticBaseUrl.value
  if (!form.baseUrl) {
    ElMessage.warning('部署默认端点为空，请手动输入')
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const res = await getAiConfig()
    vo.value = res.data
    form.enabled = res.data.enabled
    form.baseUrl = res.data.baseUrl || ''
    form.apiKey = ''
    form.model = res.data.model || ''
    form.temperature = res.data.temperature ?? null
    form.maxTokens = res.data.maxTokens ?? null
    form.connectTimeoutMs = res.data.connectTimeoutMs || 5000
    form.readTimeoutMs = res.data.readTimeoutMs || 120000
    testResult.value = null
    const presets = [...endpointPresets.value]
    if (staticBaseUrl.value && !presets.some((p) => p.url === staticBaseUrl.value)) {
      presets.unshift({ label: '部署默认', url: staticBaseUrl.value })
    }
    endpointPresets.value = presets
  } catch {
    /* 拦截器已 toast */
  } finally {
    loading.value = false
  }
}

async function onSave() {
  const valid = await formRef.value?.validate().then(() => true).catch(() => false)
  if (!valid) return
  if (!form.baseUrl?.trim()) {
    ElMessage.warning('端点地址不能为空（可点上方「部署默认」回填）')
    return
  }
  saving.value = true
  try {
    const res = await saveAiConfig({
      enabled: form.enabled,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined,
      model: form.model.trim(),
      temperature: form.temperature,
      maxTokens: form.maxTokens,
      connectTimeoutMs: form.connectTimeoutMs,
      readTimeoutMs: form.readTimeoutMs,
    })
    vo.value = res.data
    form.apiKey = ''
    ElMessage.success('已保存，配置实时生效')
  } catch {
    /* 拦截器已 toast */
  } finally {
    saving.value = false
  }
}

async function onTest() {
  if (!form.baseUrl?.trim()) {
    ElMessage.warning('请先填写端点地址')
    return
  }
  testing.value = true
  try {
    const res = await testAiConfig({
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined,
      model: form.model.trim() || undefined,
    })
    testResult.value = res.data
  } catch {
    /* 拦截器已 toast */
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>
