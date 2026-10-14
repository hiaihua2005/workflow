<template>
  <div class="module-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>模块管理</span>
        </div>
      </template>
      <p class="intro">
        将已发布的业务表单、页面、数据源、逻辑流、工作流连同依赖打包导出为模块文件（zip），
        在其他应用中导入复用。导入前可预览模块描述并处理同名冲突。
      </p>
      <div class="actions">
        <el-card shadow="hover" class="action-card" @click="exportDialog?.open()">
          <el-icon :size="28" class="icon"><Download /></el-icon>
          <div>
            <div class="action-title">导出模块</div>
            <div class="action-desc">选择元素 → 依赖闭包分析 → 打包下载</div>
          </div>
        </el-card>
        <el-card shadow="hover" class="action-card" @click="importDialog?.open()">
          <el-icon :size="28" class="icon"><Upload /></el-icon>
          <div>
            <div class="action-title">导入模块</div>
            <div class="action-desc">上传模块包 → 预检冲突 → 覆盖/跳过导入</div>
          </div>
        </el-card>
      </div>
    </el-card>

    <ModuleExportDialog ref="exportDialog" />
    <ModuleImportDialog ref="importDialog" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Download, Upload } from '@element-plus/icons-vue'
import ModuleExportDialog from './ModuleExportDialog.vue'
import ModuleImportDialog from './ModuleImportDialog.vue'

const exportDialog = ref<InstanceType<typeof ModuleExportDialog> | null>(null)
const importDialog = ref<InstanceType<typeof ModuleImportDialog> | null>(null)
</script>

<style scoped>
.module-page {
  padding: 16px;
}
.card-header {
  font-weight: 600;
}
.intro {
  color: var(--el-text-color-secondary);
  margin: 0 0 16px;
}
.actions {
  display: flex;
  gap: 16px;
}
.action-card {
  width: 320px;
  cursor: pointer;
  transition: transform 0.15s;
}
.action-card:hover {
  transform: translateY(-2px);
}
.action-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 14px;
}
.icon {
  color: var(--el-color-primary);
}
.action-title {
  font-weight: 600;
}
.action-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
