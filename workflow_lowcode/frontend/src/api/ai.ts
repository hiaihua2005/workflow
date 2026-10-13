/**
 * AI 助手对话 API（SSE 流式事件客户端）。
 *
 * POST /api/v1/ai/chat 以 text/event-stream 返回事件：
 * meta → (tool_call → tool_result)* → message → done（或 error）。
 * EventSource 无法携带 POST body，故使用 fetch + ReadableStream 手动解析。
 */

export interface AiChatTurn {
  role: 'user' | 'assistant'
  content: string
}

export interface AiChatPayload {
  message: string
  history: AiChatTurn[]
  context?: Record<string, unknown>
}

export interface AiNavigation {
  label: string
  path: string
}

export interface AiChatHandlers {
  onMeta?: (meta: { model: string }) => void
  onToolCall?: (name: string, args: unknown) => void
  onToolResult?: (name: string, result: unknown) => void
  onMessage?: (text: string, navigations: AiNavigation[]) => void
  /** page_control 动作序列下发（AI 页面操作，执行后需回传 observation） */
  onPageAction?: (actions: unknown[]) => void
  onDone?: () => void
  onError?: (error: { code: string; msg: string }) => void
}

const CHAT_URL = '/api/v1/ai/chat'

// ==================== 测试用例中文转写 ====================

/** 中文用例单步 */
export interface NarrateStep {
  text: string
  expect?: string
}

/** 中文用例（LLM 转写结果） */
export interface TestCaseNarrative {
  title: string
  summary: string
  steps: NarrateStep[]
}

/**
 * 把录制的动作序列交给 LLM 转写为中文测试用例。
 *
 * @param pageRoute 录制时页面路由
 * @param name      用例名（可空）
 * @param actions   动作序列（page_action 协议）
 */
export async function narrateTestCase(pageRoute: string, name: string, actions: unknown[]): Promise<TestCaseNarrative> {
  const response = await fetch('/api/v1/ai/testcase/narrate', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
      'X-Tenant-Id': 'default',
    },
    body: JSON.stringify({ pageRoute, name, actions }),
  })
  if (!response.ok) {
    let msg = `请求失败（${response.status}）`
    try {
      const data = await response.json()
      if (data?.msg) msg = data.msg
    } catch {
      // 非 JSON 响应体
    }
    throw new Error(msg)
  }
  const data = (await response.json()) as { code?: number; msg?: string; data?: TestCaseNarrative }
  if (data?.code !== 200 || !data.data) {
    throw new Error(data?.msg || '转写失败')
  }
  return data.data
}

/**
 * 发起助手对话。
 *
 * @returns AbortController（调用 abort() 取消）
 */
export function chat(payload: AiChatPayload, handlers: AiChatHandlers): AbortController {
  const controller = new AbortController()
  void (async () => {
    try {
      const response = await fetch(CHAT_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
          'X-Tenant-Id': 'default',
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      })

      if (!response.ok) {
        let msg = `请求失败（${response.status}）`
        try {
          const data = await response.json()
          if (data?.msg) msg = data.msg
        } catch {
          // 忽略非 JSON 响应体
        }
        handlers.onError?.({ code: String(response.status), msg })
        return
      }

      if (!response.body) {
        handlers.onError?.({ code: 'NO_BODY', msg: '响应内容为空' })
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const blocks = buffer.split('\n\n')
        buffer = blocks.pop() ?? ''
        for (const block of blocks) {
          dispatchBlock(block, handlers)
        }
      }
      if (buffer.trim()) {
        dispatchBlock(buffer, handlers)
      }
    } catch (error) {
      const err = error as { name?: string; message?: string }
      if (err?.name === 'AbortError') return
      handlers.onError?.({ code: 'NETWORK', msg: err?.message ?? '网络错误' })
    }
  })()
  return controller
}

function dispatchBlock(block: string, handlers: AiChatHandlers): void {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (dataLines.length === 0) return

  const dataStr = dataLines.join('\n')
  let payload: any = dataStr
  try {
    payload = JSON.parse(dataStr)
  } catch {
    // 保留原始文本
  }

  switch (event) {
    case 'meta':
      handlers.onMeta?.(payload)
      break
    case 'tool_call':
      handlers.onToolCall?.(payload?.name, payload?.args)
      break
    case 'tool_result':
      handlers.onToolResult?.(payload?.name, payload?.result)
      break
    case 'message':
      handlers.onMessage?.(
        typeof payload?.text === 'string' ? payload.text : '',
        Array.isArray(payload?.navigations) ? payload.navigations : [],
      )
      break
    case 'page_action':
      if (Array.isArray(payload?.actions)) {
        handlers.onPageAction?.(payload.actions)
      }
      break
    case 'done':
      handlers.onDone?.()
      break
    case 'error':
      handlers.onError?.(payload)
      break
    default:
      break
  }
}
