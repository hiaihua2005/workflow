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
  /** 前端执行工具下发（page_action/dom_action）：执行后调 chatContinue 续跑 */
  onClientTool?: (info: { sessionId: string; callId: string; name: string; args: Record<string, any> }) => void
  onDone?: () => void
  onError?: (error: { code: string; msg: string }) => void
}

const CHAT_URL = '/api/v1/ai/chat'
const CHAT_CONTINUE_URL = '/api/v1/ai/chat/continue'

/**
 * 发起助手对话。
 *
 * @returns AbortController（调用 abort() 取消）
 */
export function chat(payload: AiChatPayload, handlers: AiChatHandlers): AbortController {
  return streamChat(CHAT_URL, payload, handlers)
}

/**
 * 客户端工具执行结果回传并续跑（事件序列与 chat 相同，可再次含 client_tool）。
 *
 * @returns AbortController（调用 abort() 取消）
 */
export function chatContinue(
  sessionId: string,
  callId: string,
  result: unknown,
  handlers: AiChatHandlers,
): AbortController {
  return streamChat(CHAT_CONTINUE_URL, { sessionId, callId, result }, handlers)
}

/**
 * 录制日志 → LLM 生成 Playwright 测试用例（非流式）。
 *
 * @returns 生成的 TypeScript 测试文件文本
 */
export async function generateTestSpec(route: string, records: unknown[]): Promise<string> {
  const response = await fetch('/api/v1/ai/test-spec', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
      'X-Tenant-Id': 'default',
    },
    body: JSON.stringify({ route, records }),
  })
  const body = await response.json().catch(() => null)
  if (body?.code !== 200) {
    throw new Error(body?.msg || `用例生成失败（${response.status}）`)
  }
  return String(body?.data?.spec ?? '')
}

function streamChat(url: string, payload: unknown, handlers: AiChatHandlers): AbortController {
  const controller = new AbortController()
  void (async () => {
    try {
      const response = await fetch(url, {
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
    case 'client_tool':
      handlers.onClientTool?.({
        sessionId: String(payload?.sessionId ?? ''),
        callId: String(payload?.callId ?? ''),
        name: String(payload?.name ?? ''),
        args: (payload?.args ?? {}) as Record<string, any>,
      })
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
