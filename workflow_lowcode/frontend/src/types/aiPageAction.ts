/**
 * AI 页面动作协议类型（兼容层：桥接至本地 @/utils/aiPageBridge 执行核心）。
 */

/** 单个页面动作：op 为动作名，其余字段为动作参数（field/value/target/rows…）。 */
export interface AiPageAction {
  op: string
  field?: string
  value?: unknown
  target?: string
  [key: string]: unknown
}

/** 动作执行结果。 */
export interface AiActionResult {
  ok: boolean
  data?: unknown
  error?: string
}

/** 页面清单（供 AI 感知当前页可操作能力）。 */
export interface AiPageManifest {
  pageType?: string
  title?: string
  [key: string]: unknown
}
