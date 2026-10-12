/** 大模型动态配置类型（系统管理 → 模型配置） */

export interface AiConfigVO {
  enabled: boolean
  baseUrl: string
  /** 脱敏展示（sk-ab****yz），仅供确认已配置 */
  apiKeyMasked: string | null
  hasApiKey: boolean
  model: string
  temperature: number | null
  maxTokens: number | null
  connectTimeoutMs: number
  readTimeoutMs: number
  /** "数据库配置" | "环境与默认配置（页面尚未保存过）" */
  source: string
  updatedAt: string | null
  /** 静态层默认值（env/yml/沙箱桥接），用于展示提示 */
  staticDefaults: Record<string, string>
}

export interface AiConfigSaveForm {
  enabled: boolean
  baseUrl: string
  /** 留空 = 保持不变；"-" = 清空 */
  apiKey?: string
  model: string
  temperature?: number | null
  maxTokens?: number | null
  connectTimeoutMs?: number
  readTimeoutMs?: number
}

export interface AiConfigTestResult {
  ok: boolean
  latencyMs: number
  message: string
  model: string
}
