/**
 * AI 页面执行器（L2 注册层适配器）。
 *
 * 本模块是把远端 L2 注册层（UserPage/RolePage/AiConfigPage/PageRenderer 的
 * registerPageActions / registerPageExecutor 调用点）桥接到本地执行核心
 * {@link ./aiPageBridge} 的薄适配器：
 * - 注册：把载荷转成动态清单组件 + L2 动作字典，进入 aiPageBridge 的
 *   describePage（LLM 感知）与 page_action 分发（L2 分发钩子优先）；
 * - 执行：page_action 的 action 即 L2 handlers 的键（或 execute 回调的 op），
 *   参数整包透传给注册方实现。
 *
 * routePath 守卫沿用远端设计：动作只响应当前路由匹配的页面，防 keep-alive 缓存实例串页。
 */
import {
  registerDynamicComponent,
  setL2PageDispatcher,
  type ActionResult,
  type AiComponentMeta,
} from './aiPageBridge'
import type { AiPageAction } from '@/types/aiPageAction'

export type ActionOutcome = { ok: boolean; data?: unknown; error?: string }

export interface PageExecutorPayload {
  /** 注册页面路由（实际路由路径，非模式）；作为 page_action 的 componentId */
  routePath: string
  pageType?: string
  /** 页面清单（动态求值：页面数据就绪后才返回完整结构） */
  manifest?: () => Record<string, unknown>
  /** L2 动作字典：键 = 动作名（如 'set-query'/'open-create'/'submit'） */
  handlers?: Record<string, (a: Record<string, any>) => ActionOutcome | Promise<ActionOutcome>>
  /** 整包执行回调（与 handlers 二选一；PageRenderer 视图轨使用） */
  execute?: (action: AiPageAction) => ActionOutcome | Promise<ActionOutcome>
  /** 预留：定位页面元素（本适配器不使用 DOM 高亮） */
  resolveElement?: (action: AiPageAction) => HTMLElement | null
}

interface RegisteredPage {
  payload: PageExecutorPayload
  unregisterComponent: () => void
}

const pages = new Map<string, RegisteredPage>()

/** 从清单构造组件元数据（动态求值；未就绪返回 null）。 */
function buildMeta(payload: PageExecutorPayload): AiComponentMeta | null {
  let m: Record<string, any>
  try {
    m = payload.manifest?.() ?? {}
  } catch {
    return null
  }
  const title = String(m.title ?? payload.routePath)
  const capabilities: string[] =
    Array.isArray(m.capabilities) && m.capabilities.length
      ? m.capabilities.map(String)
      : payload.handlers
        ? Object.keys(payload.handlers)
        : []
  const fields: { key: string; label?: string; type?: string }[] = []
  for (const f of [...((m.searchFields ?? []) as any[]), ...((m.formFields ?? []) as any[])]) {
    if (f?.field) fields.push({ key: String(f.field), label: f.title, type: f.type })
  }
  return { id: payload.routePath, kind: 'list', title, capabilities, fields }
}

function register(payload: PageExecutorPayload): () => void {
  // keep-alive 重建：同 routePath 后注册者覆盖
  pages.get(payload.routePath)?.unregisterComponent?.()
  const unregisterComponent = registerDynamicComponent(() => buildMeta(payload))
  const entry: RegisteredPage = { payload, unregisterComponent }
  pages.set(payload.routePath, entry)
  return () => {
    if (pages.get(payload.routePath) === entry) {
      pages.delete(payload.routePath)
      unregisterComponent()
    }
  }
}

/** 注册 L2 动作字典型页面（UserPage/RolePage/AiConfigPage）。 */
export function registerPageActions(payload: PageExecutorPayload): () => void {
  return register(payload)
}

/** 注册整包执行型页面（PageRenderer 视图轨：execute 回调 + op 协议）。 */
export function registerPageExecutor(payload: PageExecutorPayload): () => void {
  return register(payload)
}

/** 桥内分发：componentId 命中 L2 页面则执行对应动作；未命中返回 null（走本地结构化动作）。 */
async function dispatchL2Page(componentId: string, args: Record<string, any>): Promise<ActionResult | null> {
  const entry = pages.get(componentId)
  if (!entry) return null
  const { payload } = entry
  // routePath 守卫：防 keep-alive 缓存实例串页
  if (payload.routePath !== location.pathname) {
    return { ok: false, message: `组件 ${componentId} 属于页面 ${payload.routePath}，当前不在该页面` }
  }
  const op = String(args?.action ?? '')
  let outcome: ActionOutcome
  if (payload.execute) {
    outcome = await payload.execute({ op, ...args })
  } else {
    const handler = payload.handlers?.[op]
    if (!handler) {
      const available = Object.keys(payload.handlers ?? {}).join('/')
      return { ok: false, message: `动作 "${op}" 不受支持（可用：${available || '无'}）` }
    }
    outcome = await handler(args)
  }
  const message =
    outcome?.error ??
    (typeof outcome?.data === 'string' && outcome.data
      ? outcome.data
      : outcome?.data != null
        ? JSON.stringify(outcome.data)
        : 'ok')
  return { ok: outcome?.ok !== false, message, data: outcome?.data }
}

// 注入桥内分发钩子：page_action 优先走 L2 注册层，未命中再落本地结构化动作
setL2PageDispatcher(dispatchL2Page)
