<template>
  <el-dialog v-model="visible" title="导入模块" width="820px" :close-on-click-modal="false" @closed="reset">
    <!-- 步骤 1：上传 -->
    <template v-if="step === 1">
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".zip"
        :on-change="onFileChange"
        :on-remove="() => (file = null)"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽模块包（.zip）到此处，或<em>点击选择文件</em></div>
      </el-upload>
    </template>

    <!-- 步骤 2：预检结果 + 冲突处理 -->
    <template v-else-if="step === 2 && inspect">
      <el-descriptions :column="2" size="small" border class="module-meta">
        <el-descriptions-item label="模块名称">{{ inspect.moduleName }}</el-descriptions-item>
        <el-descriptions-item label="导出时间">{{ inspect.exportedAt }}</el-descriptions-item>
        <el-descriptions-item label="来源应用">{{ inspect.sourceTenant }}</el-descriptions-item>
        <el-descriptions-item label="元素数量">{{ inspect.elements.length }}</el-descriptions-item>
        <el-descriptions-item v-if="inspect.moduleDescription" label="模块说明" :span="2">
          {{ inspect.moduleDescription }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-for="(w, i) in inspect.globalWarnings"
        :key="i"
        type="info"
        :closable="false"
        show-icon
        :title="w"
        class="warn-row"
      />
      <el-alert
        v-for="(w, i) in elementWarnings"
        :key="'e' + i"
        type="warning"
        :closable="false"
        show-icon
        :title="w"
        class="warn-row"
      />
      <el-alert
        v-if="hasMissingDeps"
        type="error"
        :closable="false"
        show-icon
        title="存在依赖缺口：缺失依赖的元素导入后不可用，请先导出包含缺失依赖的模块"
        class="warn-row"
      />

      <el-table :data="inspect.elements" size="small" max-height="300">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="key" label="标识" min-width="120" />
        <el-table-column label="目标状态" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.exists" type="warning" size="small">同名冲突（{{ row.targetVersion }}）</el-tag>
            <el-tag v-else type="success" size="small">新建</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缺失依赖" min-width="120">
          <template #default="{ row }">
            <span v-if="row.missingDeps.length" class="missing">{{ row.missingDeps.join('、') }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="处理方式" width="150" fixed="right">
          <template #default="{ row }">
            <el-radio-group v-model="strategies[row.type + ':' + row.key]" size="small">
              <el-radio-button value="OVERWRITE">覆盖</el-radio-button>
              <el-radio-button value="SKIP">跳过</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 步骤 3：结果 -->
    <template v-else-if="step === 3">
      <el-result
        v-if="!failedCount"
        icon="success"
        title="导入完成"
        :sub-title="`导入 ${importedCount} 个，跳过 ${skippedCount} 个。请为表单/页面配置菜单入口，工作流为草稿态需部署。`"
      />
      <el-result v-else icon="error" title="导入失败" :sub-title="failMessage" />
      <el-table :data="result?.results ?? []" size="small" max-height="260">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="key" label="标识" min-width="120" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'IMPORTED' ? 'success' : 'info'" size="small">
              {{ row.status === 'IMPORTED' ? '已导入' : '已跳过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="160" />
      </el-table>
    </template>

    <template #footer>
      <template v-if="step === 1">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!file" :loading="inspecting" @click="doInspect">
          读取模块描述
        </el-button>
      </template>
      <template v-else-if="step === 2">
        <el-button @click="step = 1">上一步</el-button>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="importing" :disabled="hasMissingDeps" @click="doImport">
          执行导入
        </el-button>
      </template>
      <template v-else>
        <el-button type="primary" @click="visible = false">完成</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { moduleApi, ELEMENT_TYPE_LABELS, type ModuleElementType, type InspectResult, type ImportResult } from '@/api/module'

const visible = ref(false)
const step = ref<1 | 2 | 3>(1)
const file = ref<File | null>(null)
const inspecting = ref(false)
const importing = ref(false)
const inspect = ref<InspectResult | null>(null)
const result = ref<ImportResult | null>(null)
const strategies = ref<Record<string, 'OVERWRITE' | 'SKIP'>>({})

const elementWarnings = computed(() => {
  const list: string[] = []
  for (const el of inspect.value?.elements ?? []) {
    for (const w of el.warnings) list.push(`${typeLabel(el.type)}「${el.name}」：${w}`)
  }
  return list
})

const hasMissingDeps = computed(() =>
  (inspect.value?.elements ?? []).some((e) => e.missingDeps.length > 0)
)
const importedCount = computed(
  () => (result.value?.results ?? []).filter((r) => r.status === 'IMPORTED').length
)
const skippedCount = computed(
  () => (result.value?.results ?? []).filter((r) => r.status === 'SKIPPED').length
)
const failedCount = ref(0)
const failMessage = ref('')

function typeLabel(t: ModuleElementType): string {
  return ELEMENT_TYPE_LABELS[t] ?? t
}

function open(): void {
  visible.value = true
}

defineExpose({ open })

function reset(): void {
  step.value = 1
  file.value = null
  inspect.value = null
  result.value = null
  strategies.value = {}
  failedCount.value = 0
  failMessage.value = ''
}

function onFileChange(uploadFile: { raw?: File }): void {
  file.value = uploadFile.raw ?? null
}

async function doInspect(): Promise<void> {
  if (!file.value) return
  inspecting.value = true
  try {
    const res = await moduleApi.inspect(file.value)
    if (res.code !== 0 && res.code !== 200) {
      ElMessage.error(res.msg || '模块包解析失败')
      return
    }
    inspect.value = res.data
    strategies.value = {}
    for (const el of res.data.elements) {
      // 默认策略：新元素=导入，冲突=跳过
      strategies.value[el.type + ':' + el.key] = el.exists ? 'SKIP' : 'OVERWRITE'
    }
    step.value = 2
  } finally {
    inspecting.value = false
  }
}

async function doImport(): Promise<void> {
  if (!file.value || !inspect.value) return
  importing.value = true
  try {
    const actions = Object.entries(strategies.value).map(([ref, strategy]) => {
      const [type, ...rest] = ref.split(':')
      return { type, key: rest.join(':'), strategy }
    })
    const res = await moduleApi.importModule(file.value, actions)
    if (res.code !== 0 && res.code !== 200) {
      failedCount.value = 1
      failMessage.value = res.msg || '导入失败'
      step.value = 3
      return
    }
    result.value = res.data
    failedCount.value = 0
    step.value = 3
    ElMessage.success('模块导入完成')
  } catch (e) {
    failedCount.value = 1
    failMessage.value = e instanceof Error ? e.message : String(e)
    step.value = 3
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.module-meta {
  margin-bottom: 12px;
}
.warn-row {
  margin-bottom: 8px;
}
.missing {
  color: var(--el-color-danger);
}
</style>
