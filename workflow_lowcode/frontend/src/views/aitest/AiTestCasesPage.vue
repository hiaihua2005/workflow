<template>
  <div class="aitest-page">
    <div class="aitest-header">
      <div>
        <h2 class="aitest-title">AI 测试工具</h2>
        <div class="aitest-sub">
          录制页面操作（手动 + AI 助手执行）→ 保存为用例 → 回放验证 / 导出脚本 / LLM 转自然语言用例。
          录制为全局状态：<b>开始录制后可前往任意业务页面操作，再回到本页保存</b>。
        </div>
      </div>
      <div class="aitest-rec-ctl">
        <el-tag v-if="recording" type="danger" effect="light">● 录制中</el-tag>
        <el-button :type="recording ? 'danger' : 'primary'" @click="toggleRecording">
          <el-icon class="mr-1"><VideoPause v-if="recording" /><VideoPlay v-else /></el-icon>
          {{ recording ? `停止录制 (${recordCount})` : '开始录制' }}
        </el-button>
      </div>
    </div>

    <el-row :gutter="14">
      <!-- 当前录制 -->
      <el-col :span="10">
        <el-card shadow="never" class="aitest-card">
          <template #header>
            <div class="aitest-card-head">
              <span>当前录制</span>
              <el-button size="small" text :disabled="!hasRecords" @click="clearCurrent">清空</el-button>
            </div>
          </template>
          <div class="aitest-record-list">
            <div v-for="row in recordRows" :key="row.index" class="aitest-record-row">
              <span class="aitest-record-idx">{{ row.index }}</span>
              <el-tag :type="row.source === 'AI' ? 'primary' : 'success'" size="small" effect="plain">{{ row.source }}</el-tag>
              <span class="aitest-record-detail" :title="row.detail">{{ row.detail }}</span>
              <span class="aitest-record-time">{{ row.time }}</span>
            </div>
            <div v-if="!recordRows.length" class="aitest-empty">
              暂无录制内容——点击右上角「开始录制」后到业务页面正常操作（AI 助手执行的动作同样会被记录）
            </div>
          </div>
          <div class="aitest-btn-row">
            <el-button size="small" type="primary" :disabled="!hasRecords" @click="saveCurrentAsCase">
              <el-icon class="mr-1"><Notebook /></el-icon>保存为用例
            </el-button>
            <el-button size="small" :disabled="!hasRecords" @click="exportSpec">
              <el-icon class="mr-1"><Download /></el-icon>确定性导出
            </el-button>
            <el-button size="small" :disabled="!hasRecords || generating" :loading="generating" @click="generateSpecByLlm">
              <el-icon class="mr-1"><MagicStick /></el-icon>LLM 转自然语言用例
            </el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 用例库 -->
      <el-col :span="14">
        <el-card shadow="never" class="aitest-card">
          <template #header>
            <div class="aitest-card-head">
              <span>用例库</span>
              <el-button size="small" text @click="refreshCases">刷新</el-button>
            </div>
          </template>
          <div v-if="playingId" class="aitest-playing">
            <el-icon class="is-loading"><VideoPlay /></el-icon>
            {{ playingProgress }}
          </div>
          <el-table :data="cases" size="small" max-height="440">
            <el-table-column prop="name" label="用例名称" min-width="170" show-overflow-tooltip />
            <el-table-column prop="route" label="来源路由" width="150" show-overflow-tooltip />
            <el-table-column prop="steps" label="步骤" width="56" />
            <el-table-column label="操作" width="210">
              <template #default="{ row }">
                <el-button size="small" text type="primary" :disabled="isPlaying()" @click="replayCase(row)">回放</el-button>
                <el-button size="small" text @click="viewCase(row)">查看</el-button>
                <el-button size="small" text type="danger" @click="removeCase(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!cases.length" class="aitest-empty" style="padding-top: 12px">
            还没有保存的用例——先录制操作再点「保存为用例」
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 用例详情：自然语言步骤 + 单步删除（带确认） -->
    <el-dialog v-model="viewerVisible" :title="`用例详情：${viewerCase?.name ?? ''}`" width="680" append-to-body>
      <div v-if="viewerCase" class="aitest-case-meta">
        来源路由 {{ viewerCase.route || '当前页' }} · {{ viewerCase.steps }} 步 · 保存于
        {{ viewerCase.createdAt.slice(0, 16).replace('T', ' ') }}
      </div>
      <div class="aitest-steps">
        <div v-for="(s, i) in viewerSteps" :key="i" class="aitest-step-row">
          <span class="aitest-step-idx">{{ i + 1 }}</span>
          <el-tag :type="s.source === 'AI' ? 'primary' : 'success'" size="small" effect="plain">{{ s.source }}</el-tag>
          <span class="aitest-step-text">{{ s.text }}</span>
          <el-button size="small" text type="danger" @click="removeStep(i)">删除</el-button>
        </div>
        <div v-if="!viewerSteps.length" class="aitest-empty">该用例没有步骤了</div>
      </div>
      <template #footer>
        <span class="aitest-tip">删除步骤立即保存；回放前请确认步骤链路完整</span>
        <el-button size="small" @click="viewerCase && exportCaseSpec(viewerCase)">
          <el-icon class="mr-1"><Download /></el-icon>导出 Playwright 脚本
        </el-button>
        <el-button size="small" @click="viewerCase && renameCase(viewerCase)">
          <el-icon class="mr-1"><Edit /></el-icon>重命名
        </el-button>
        <el-button size="small" type="primary" :loading="generating" @click="viewerCase && generateCaseSpecByLlm(viewerCase)">
          <el-icon class="mr-1"><MagicStick /></el-icon>LLM 转自然语言用例
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Edit, MagicStick, Notebook, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import {
  clearRecords,
  deleteTestCase,
  deleteTestCaseStep,
  describeRecord,
  getRecords,
  getTestCaseRecords,
  isPlaying,
  isRecording,
  listTestCases,
  onRecordsChanged,
  playbackTestCase,
  renameTestCase,
  saveTestCase,
  startRecording,
  stopRecording,
  toPlaywrightSpec,
  type ActionRecord,
  type TestCaseMeta,
} from '@/utils/aiPageBridge'
import { generateTestSpec } from '@/api/ai'

const route = useRoute()

// ==================== 录制（全局状态：跨页面持续） ====================
const recording = ref(false)
const recordCount = ref(0)
const hasRecords = computed(() => recordCount.value > 0)
const recordRows = ref<{ index: number; source: string; detail: string; time: string }[]>([])

function refreshRecordRows(): void {
  recordRows.value = getRecords().map((r: ActionRecord, i: number) => {
    let detail = String(r.args?.desc ?? '')
    if (!detail && r.args?.values) {
      detail = `填表 ${Object.keys(r.args.values as Record<string, unknown>).join('、')}`
    }
    if (!detail && r.args?.path) detail = String(r.args.path)
    if (!detail && r.args?.filter) {
      detail = `查询条件 ${Object.entries(r.args.filter as Record<string, unknown>).map(([k, v]) => `${k}=${v}`).join('、')}`
    }
    if (!detail) detail = r.result.message
    return {
      index: i + 1,
      source: r.source === 'ai' ? 'AI' : '手动',
      detail,
      time: r.time.slice(11, 19),
    }
  })
}

function toggleRecording(): void {
  if (recording.value) {
    stopRecording()
    recording.value = isRecording()
    refreshRecordRows()
  } else {
    startRecording()
    recording.value = isRecording()
    ElMessage.success('开始录制：可前往任意业务页面操作，操作完成后回到本页保存为用例')
  }
}

function clearCurrent(): void {
  clearRecords()
  refreshRecordRows()
}

// ==================== 导出 / LLM 转用例（针对当前录制缓冲） ====================
const generating = ref(false)

function exportSpec(): void {
  const spec = toPlaywrightSpec(route.path)
  const blob = new Blob([spec], { type: 'text/typescript;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `ai-recorded-${new Date().toISOString().slice(0, 10)}.spec.ts`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出 Playwright 测试脚本（请人工校对定位器与补全断言）')
}

async function generateSpecByLlm(): Promise<void> {
  if (generating.value) return
  generating.value = true
  try {
    const spec = await generateTestSpec(route.path, toLlmRecords(getRecords()))
    downloadText(spec, `ai-generated-${new Date().toISOString().slice(0, 10)}-用例.md`)
    ElMessage.success('自然语言测试用例已生成，已下载 .md 文件')
  } catch (e) {
    ElMessage.error((e as Error)?.message ?? '用例生成失败')
  } finally {
    generating.value = false
  }
}

async function generateCaseSpecByLlm(row: TestCaseMeta): Promise<void> {
  generating.value = true
  try {
    const spec = await generateTestSpec(row.route, toLlmRecords(getTestCaseRecords(row.id)))
    downloadText(spec, `${row.name.replace(/\s+/g, '-')}-用例.md`)
    ElMessage.success('自然语言测试用例已生成，已下载 .md 文件')
  } catch (e) {
    ElMessage.error((e as Error)?.message ?? '用例生成失败')
  } finally {
    generating.value = false
  }
}

function toLlmRecords(records: ActionRecord[]): Record<string, unknown>[] {
  return records.map((r) => ({
    source: r.source,
    kind: r.source === 'ai' ? r.tool : r.kind,
    ...r.args,
    resultMessage: r.result.message,
    time: r.time,
  }))
}

function downloadText(text: string, filename: string): void {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// ==================== 用例库（保存 / 回放 / 查看 / 单步删除 / 重命名 / 删除） ====================
const cases = ref<TestCaseMeta[]>([])
const playingId = ref('')
const playingProgress = ref('')

function refreshCases(): void {
  cases.value = listTestCases()
}

async function saveCurrentAsCase(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('输入用例名称（同名覆盖）', '保存为测试用例', {
      inputValue: `${route.meta?.title ?? route.path}-${new Date().toISOString().slice(5, 16).replace('T', ' ')}`,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const meta = saveTestCase(String(value).trim(), route.path, getRecords())
    refreshCases()
    ElMessage.success(`用例「${meta.name}」已保存（${meta.steps} 步）`)
  } catch {
    /* 用户取消 */
  }
}

async function replayCase(row: TestCaseMeta): Promise<void> {
  if (isPlaying()) {
    ElMessage.warning('已有回放在进行中')
    return
  }
  playingId.value = row.id
  playingProgress.value = '准备回放…'
  await playbackTestCase(row.id, {
    onStep: (i, total, desc, ok, msg) => {
      playingProgress.value = ok ? `步骤 ${i}/${total}：${desc}` : `步骤 ${i}/${total} 失败：${msg}`
    },
    onDone: (summary) => {
      playingId.value = ''
      playingProgress.value = ''
      const text = summary.aborted
        ? `回放中止：${summary.passed}/${summary.total} 通过，失败于第 ${summary.passed + 1} 步`
        : `回放完成：${summary.passed}/${summary.total} 通过`
      ElMessage[text.includes('中止') ? 'warning' : 'success'](text)
    },
  })
}

async function renameCase(row: TestCaseMeta): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('输入新名称', '重命名用例', {
      inputValue: row.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    renameTestCase(row.id, String(value).trim())
    refreshCases()
  } catch {
    /* 用户取消 */
  }
}

async function removeCase(row: TestCaseMeta): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除用例「${row.name}」吗？该操作不可恢复。`, '删除用例', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  deleteTestCase(row.id)
  refreshCases()
  ElMessage.success('用例已删除')
}

// ==================== 用例详情（查看 + 单步删除） ====================
const viewerVisible = ref(false)
const viewerCase = ref<TestCaseMeta | null>(null)
const viewerSteps = ref<{ source: string; text: string }[]>([])

function viewCase(row: TestCaseMeta): void {
  viewerCase.value = row
  refreshViewerSteps()
  viewerVisible.value = true
}

function refreshViewerSteps(): void {
  if (!viewerCase.value) return
  viewerSteps.value = getTestCaseRecords(viewerCase.value.id).map((r) => ({
    source: r.source === 'ai' ? 'AI' : '手动',
    text: describeRecord(r),
  }))
}

/** 删除用例中的单个步骤（二次确认后立即持久化）。 */
async function removeStep(index: number): Promise<void> {
  if (!viewerCase.value) return
  const text = viewerSteps.value[index]?.text ?? `第 ${index + 1} 步`
  try {
    await ElMessageBox.confirm(`确定删除步骤 ${index + 1}（${text}）吗？删除后立即保存。`, '删除步骤', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  deleteTestCaseStep(viewerCase.value.id, index)
  refreshViewerSteps()
  refreshCases()
  ElMessage.success('步骤已删除')
}

function exportCaseSpec(row: TestCaseMeta): void {
  const spec = toPlaywrightSpec(row.route, getTestCaseRecords(row.id))
  downloadText(spec, `${row.name.replace(/\s+/g, '-')}.spec.ts`)
  ElMessage.success('已导出该用例的 Playwright 脚本')
}

// ==================== 生命周期 ====================
onMounted(() => {
  recording.value = isRecording()
  refreshRecordRows()
  refreshCases()
  onRecordsChanged(() => {
    recordCount.value = getRecords().length
    refreshRecordRows()
  })
})
onUnmounted(() => {
  // 不停止录制：录制是全局状态，用户可能去别的页面继续操作
})
</script>

<style scoped>
.aitest-page {
  max-width: 1280px;
  margin: 0 auto;
}
.aitest-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.aitest-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 700;
}
.aitest-sub {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  max-width: 720px;
}
.aitest-rec-ctl {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  padding-top: 4px;
}
.aitest-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.aitest-record-list {
  max-height: 380px;
  overflow-y: auto;
  scrollbar-width: thin;
}
.aitest-record-row,
.aitest-step-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 2px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}
.aitest-record-row:last-child,
.aitest-step-row:last-child {
  border-bottom: none;
}
.aitest-record-idx,
.aitest-step-idx {
  flex: none;
  width: 24px;
  text-align: right;
  color: var(--el-text-color-placeholder);
}
.aitest-record-detail {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.aitest-record-time {
  flex: none;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.aitest-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 10px 2px;
}
.aitest-btn-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.aitest-playing {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  color: var(--el-color-primary);
  font-size: 13px;
}
.aitest-case-meta {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.aitest-steps {
  max-height: 380px;
  overflow-y: auto;
}
.aitest-step-text {
  flex: 1 1 auto;
  min-width: 0;
}
.aitest-tip {
  float: left;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 24px;
}
</style>
