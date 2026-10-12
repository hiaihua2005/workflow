import http from '@/utils/http'
import type { R } from '@/types/common'
import type { AiConfigVO, AiConfigSaveForm, AiConfigTestResult } from '@/types/aiConfig'

/** 读取当前模型配置（apiKey 脱敏） */
export function getAiConfig() {
  return http.get<any, R<AiConfigVO>>('/ai-config')
}

/** 保存模型配置（保存即生效，无需重启；apiKey 留空保持不变） */
export function saveAiConfig(data: AiConfigSaveForm) {
  return http.put<any, R<AiConfigVO>>('/ai-config', data)
}

/** 连通性测试（可用未保存的草稿值，不落库） */
export function testAiConfig(data: Partial<AiConfigSaveForm>) {
  return http.post<any, R<AiConfigTestResult>>('/ai-config/test', data)
}
