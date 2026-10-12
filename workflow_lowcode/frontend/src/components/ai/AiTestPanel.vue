<template>
  <div class="ai-test-panel">
    <!-- 顶部操作条 -->
    <div class="atp-toolbar">
      <el-button size="small" :type="recording ? 'danger' : 'primary'" :disabled="replaying" @click="toggleRecord">
        {{ recording ? '■ 停止录制' : '● 录制用例' }}
      </el-button>
      <el-button size="small" :disabled="replaying" @click="onGenerate">AI 生成用例</el-button>
      <div class="atp-toolbar-spacer" />
      <el-button size="small" text :disabled="replaying" @click="importCases">导入</el-button>
      <el-button size="small" text :disabled="!cases.length" @click="exportCases">导出</el-button>
    </div>
    <div v-if="recording" class="atp-recording-tip">
      <el-icon class="is-loading"><Loading /></el-icon>
      正在录制「{{ recorder.recordingName() }}」——通过「对话」页签让 AI 操作页面，动作将自动记入用例
    </div>

    <!-- 用例列表 -->
    <div class="atp-list">
      <el-empty v-if="!cases.length" description="暂无用例：录制或让 AI 生成" :image-size="60" />
      <div v-for="c in cases" :key="c.id" class="atp-case" :class="{ current: currentCaseId === c.id }">
        <div class="atp-case-main">
          <div class="atp-case-name" :title="c.name">{{ c.name }}</div>
          <div class="atp-case-meta">{{ c.actions.length }} 步 · {{ shortRoute(c.pageRoute) }}</div>
        </div>
        <div class="atp-case-actions">
          <el-button size="small" type="primary" text :disabled="replaying" @click="replay(c)">回放</el-button>
          <el-dropdown trigger="click" @command="(cmd: string) => onCaseMenu(cmd, c)">
            <el-button size="small" text :disabled="replaying">⋯</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="export">导出本条</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </div>

    <!-- 回放报告 -->
    <el-dialog v-model="reportVisible" :title="`回放报告：${report?.caseName ?? ''}`" width="360px" append-to-body>
      <template v-if="report">
        <div class="atp-report-summary" :class="{ pass: report.allPassed }">
          {{ report.allPassed ? '✅ 全部通过' : '❌ 存在失败' }} ·
          通过 {{ report.passed }} / 失败 {{ report.failed }} · {{ report.durationMs }}ms
        </div>
        <div class="atp-report-steps">
          <div v-for="s in report.steps" :key="s.index" class="atp-report-step" :class="{ fail: !s.ok }">
            <span class="atp-step-ok">{{ s.ok ? '✓' : '✗' }}</span>
            <span class="atp-step-op">{{ s.op }}{{ s.target ? `(${s.target})` : '' }}</span>
            <span v-if="s.error" class="atp-step-err" :title="s.error">{{ s.error }}</span>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { executeAiActions } from '@/utils/aiPageExecutor'
import { aiTestRecorder, type AiTestCase, type AiTestReport } from '@/utils/aiTestRecorder'
import { useAiAssistantStore } from '@/stores/aiAssistantStore'

const emit = defineEmits<{ (e: 'generate'): void }>()

const route = useRoute()
const router = useRouter()
const store = useAiAssistantStore()
const recorder = aiTestRecorder

const cases = ref<AiTestCase[]>([])
const recording = ref(false)
const replaying = ref(false)
const reportVisible = ref(false)
const report = ref<AiTestReport | null>(null)
const currentCaseId = ref('')

onMounted(refresh)

function refresh(): void {
  cases.value = recorder.list()
  recording.value = recorder.isRecording()
}

function shortRoute(path: string): string {
  if (!path) return ''
  return path.length > 24 ? `${path.slice(0, 24)}…` : path
}

/** 录制开关：停止时保存用例 */
function toggleRecord(): void {
  if (recorder.isRecording()) {
    const saved = recorder.stop()
    if (saved) {
      ElMessage.success(`用例已保存：${saved.name}（${saved.actions.length} 步）`)
    } else {
      ElMessage.info('未捕获到动作，用例未保存')
    }
  } else {
    recorder.start(`用例 ${new Date().toLocaleTimeString()}`, route.path)
    ElMessage.success('开始录制——切到「对话」页签让 AI 操作页面')
  }
  refresh()
}

function onGenerate(): void {
  emit('generate')
}

/** 回放：先导航到录制页面（留足加载时间），再顺序执行 + 断言 */
async function replay(testCase: AiTestCase): Promise<void> {
  replaying.value = true
  currentCaseId.value = testCase.id
  try {
    if (testCase.pageRoute && route.path !== testCase.pageRoute) {
      await router.push(testCase.pageRoute)
      // 等待页面加载 + 执行器注册（L1 清单在数据加载后才上报）
      await new Promise((r) => window.setTimeout(r, 1500))
    }
    report.value = await recorder.replay(testCase, (actions) =>
      executeAiActions(actions, {
        replay: true,
        autoConfirm: store.autoConfirm,
        confirm: (message) =>
          ElMessageBox.confirm(message, '回放操作确认', { confirmButtonText: '执行', cancelButtonText: '跳过', type: 'warning' })
            .then(() => true)
            .catch(() => false),
      }),
    )
    reportVisible.value = true
    refresh()
  } finally {
    replaying.value = false
  }
}

async function onCaseMenu(cmd: string, c: AiTestCase): Promise<void> {
  if (cmd === 'delete') {
    recorder.remove(c.id)
    refresh()
  } else if (cmd === 'rename') {
    const { value } = await ElMessageBox.prompt('用例名称', '重命名', { inputValue: c.name })
    if (value) {
      recorder.rename(c.id, value)
      refresh()
    }
  } else if (cmd === 'export') {
    download(JSON.stringify({ version: 1, cases: [c] }, null, 2), `ai-case-${c.name}.json`)
  }
}

function exportCases(): void {
  if (!cases.value.length) return
  download(recorder.exportAll(), `ai-test-cases-${Date.now()}.json`)
}

async function importCases(): Promise<void> {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json,application/json'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    try {
      const count = recorder.importAll(await file.text())
      ElMessage.success(`导入 ${count} 条用例`)
      refresh()
    } catch (e) {
      ElMessage.error((e as Error)?.message || '导入失败')
    }
  }
  input.click()
}

function download(content: string, filename: string): void {
  const blob = new Blob([content], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.ai-test-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.atp-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 14px 4px;
  flex-wrap: wrap;
}
.atp-toolbar-spacer {
  flex: 1;
}
.atp-recording-tip {
  margin: 4px 14px 0;
  padding: 6px 10px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--el-color-danger) 10%, transparent);
  color: var(--el-color-danger);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 1.5;
}
.atp-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.atp-case {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color-overlay);
  transition: border-color 0.15s ease;
}
.atp-case:hover,
.atp-case.current {
  border-color: var(--el-color-primary);
}
.atp-case-main {
  flex: 1;
  min-width: 0;
}
.atp-case-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.atp-case-meta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.atp-case-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.atp-report-summary {
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--el-color-danger) 10%, transparent);
  color: var(--el-color-danger);
  font-size: 13px;
  margin-bottom: 10px;
}
.atp-report-summary.pass {
  background: color-mix(in srgb, var(--el-color-success) 10%, transparent);
  color: var(--el-color-success);
}
.atp-report-steps {
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.atp-report-step {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--el-color-success) 7%, transparent);
}
.atp-report-step.fail {
  background: color-mix(in srgb, var(--el-color-danger) 9%, transparent);
}
.atp-step-ok {
  font-weight: 700;
}
.atp-report-step.fail .atp-step-ok {
  color: var(--el-color-danger);
}
.atp-step-op {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.atp-step-err {
  color: var(--el-color-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
</style>
