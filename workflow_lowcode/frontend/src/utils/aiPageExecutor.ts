/**
 * AI 页面动作执行器：三层分发（L1 结构化 / L2 页面注册 / L3 DOM 兜底）。
 *
 * - L1：低代码运行时页面（PageRenderer/PageRendererPage/FormRenderer 宿主）注册
 *   {@link PageExecutor}，按组件注册表/表单数据模型执行结构化动作；
 * - L2：手写管理页通过 {@link useAiPageActions} 注册语义动作与清单；
 * - L3：无注册执行器时按 aiPageContext 的 DOM 扫描清单 click/fill。
 *
 * 横切能力：danger 动作确认、目标高亮、navigate 全局路由、录制钩子、路由守卫
 * （executor 携带 routePath，keep-alive 缓存实例不会串页）。
 */
import router from '@/router'
import { DANGER_OPS, type AiActionResult, type AiPageAction, type AiPageManifest, type ExecuteOptions } from '@/types/aiPageAction'
import { getPageManifest, queryDomElement } from './aiPageContext'
import { aiTestRecorder } from './aiTestRecorder'

/** 页面执行器单动作返回（分发器负责补齐 index/op/target/layer 组装完整 AiActionResult） */
export interface ActionOutcome {
  ok: boolean
  error?: string
  data?: unknown
}

// ==================== 执行器注册表 ====================

/** 页面执行器接口（L1/L2 实现） */
export interface PageExecutor {
  /** 执行器绑定的路由路径（防 keep-alive 串页；空 = 任意页面） */
  routePath: string
  /** 页面类型标识（view/page/form/admin/…） */
  pageType: string
  /** 执行单个动作；不支持的 op 返回 ok:false（error 信息会回传给 LLM） */
  execute(action: AiPageAction): Promise<ActionOutcome>
  /** 高亮锚点（可选）：返回动作目标的 DOM 元素用于闪烁提示 */
  resolveElement?(action: AiPageAction): HTMLElement | null
  /** 页面组件清单（可选，动态求值；上报后端供 LLM 定位） */
  manifest?: () => Record<string, unknown>
}

let activeExecutor: PageExecutor | null = null

/** 注册当前页面执行器（挂载时调用），返回注销函数 */
export function registerPageExecutor(executor: PageExecutor): () => void {
  activeExecutor = executor
  return () => {
    if (activeExecutor === executor) activeExecutor = null
  }
}

export function hasPageExecutor(): boolean {
  return activeExecutor !== null
}

/** 当前页面清单：L1/L2 执行器动态清单优先，否则 L3 DOM 扫描 */
export function getActiveManifest(): AiPageManifest | null {
  if (activeExecutor?.manifest && activeExecutor.routePath === router.currentRoute.value.path) {
    const m = activeExecutor.manifest() as unknown as AiPageManifest
    if (m) return m
  }
  return getPageManifest(router.currentRoute.value.path)
}

// ==================== 执行入口 ====================

/** 高亮闪烁一个元素（200ms×3 次轮廓脉冲；样式见 style.css 的 .ai-flash-target） */
function flashElement(el: HTMLElement | null | undefined): void {
  if (!el || !(el instanceof HTMLElement)) return
  try {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    el.classList.add('ai-flash-target')
    window.setTimeout(() => el.classList.remove('ai-flash-target'), 1600)
  } catch {
    /* 高亮失败不影响执行 */
  }
}

/** 执行动作序列（顺序执行；单个动作失败不中断后续，结果逐条回传）。 */
export async function executeAiActions(actions: AiPageAction[], options: ExecuteOptions = {}): Promise<AiActionResult[]> {
  const results: AiActionResult[] = []
  const total = actions.length
  for (let i = 0; i < total; i++) {
    const action = actions[i]
    options.onProgress?.(i, total, action)
    const result = await executeOne(action, options)
    results.push(result)
    if (!options.replay) aiTestRecorder.recordAction(action, result)
  }
  return results
}

async function executeOne(action: AiPageAction, options: ExecuteOptions): Promise<AiActionResult> {
  const index = 0
  const base = { op: action.op, target: action.target }
  try {
    // 1) navigate：全局路由（不依赖页面执行器）
    if (action.op === 'navigate') {
      const path = action.target ?? String(action.value ?? '')
      if (!path || !path.startsWith('/')) {
        return { index, ...base, ok: false, error: 'navigate 需要站内路径（以 / 开头）', layer: 'global' }
      }
      await router.push(path)
      return { index, ...base, ok: true, data: `已跳转 ${path}`, layer: 'global' }
    }

    // 2) 危险动作确认
    if (DANGER_OPS.has(action.op) && !options.autoConfirm) {
      const message = confirmMessage(action)
      const allowed = options.confirm ? await options.confirm(message) : true
      if (!allowed) {
        return { index, ...base, ok: false, error: '用户取消了该操作', layer: activeExecutor ? 'L1' : 'L3' }
      }
    }

    // 3) 路由匹配的页面执行器（L1/L2）
    const executor = activeExecutor
    if (executor && (!executor.routePath || !router.currentRoute.value.path || executor.routePath === router.currentRoute.value.path)) {
      // 高亮（best-effort）
      flashElement(executor.resolveElement?.(action))
      const outcome = await executor.execute({ ...action })
      return {
        index,
        op: action.op,
        target: action.target,
        ...outcome,
        layer: executor.pageType === 'admin' ? 'L2' : 'L1',
      }
    }

    // 4) L3 DOM 兜底（l3Result 已含 index/op/target）
    const l3Result = await executeDomAction(action)
    return { ...base, ...l3Result, layer: 'L3' }
  } catch (e) {
    return { index, op: action.op, target: action.target, ok: false, error: (e as Error)?.message || '执行失败', layer: activeExecutor ? 'L1' : 'L3' }
  }
}

function confirmMessage(action: AiPageAction): string {
  if (action.op === 'delete-rows') return `AI 请求删除数据（${action.target ?? '当前选中行'}），确认执行？`
  if (action.op === 'submit') return `AI 请求提交表单${action.target ? `（${action.target}）` : ''}，确认执行？`
  return `AI 请求执行写操作「${action.op}」，确认执行？`
}

// ==================== L3 DOM 动作 ====================

/** L3：原生 setter 赋值 + input/change 事件（绕过 Vue v-model 直接改 value 不生效的问题） */
function nativeSetValue(el: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set
  setter?.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.dispatchEvent(new Event('change', { bubbles: true }))
}

async function executeDomAction(action: AiPageAction): Promise<AiActionResult> {
  switch (action.op) {
    case 'click': {
      const el = requireDomEl(parseDomTarget(action.target ?? action.field))
      ;(el as HTMLElement).click()
      return { index: 0, op: action.op, target: action.target, ok: true, data: '已点击' }
    }
    case 'fill':
    case 'set-value': {
      const el = requireDomEl(parseDomTarget(action.target ?? action.field)) as HTMLElement
      const text = action.value === null || action.value === undefined ? '' : String(action.value)
      // 原生 select
      if (el instanceof HTMLSelectElement) {
        const opt = Array.from(el.options).find((o) => o.value === text || o.textContent?.trim() === text)
        if (!opt) return { index: 0, op: action.op, target: action.target, ok: false, error: `下拉框无选项「${text}」` }
        el.value = opt.value
        el.dispatchEvent(new Event('change', { bubbles: true }))
        return { index: 0, op: action.op, target: action.target, ok: true, data: `已选择「${text}」` }
      }
      // element-plus select：点击展开 → 按文本点选项
      if (el.classList.contains('el-select') || el.closest('.el-select')) {
        const trigger = el.classList.contains('el-select') ? el : el.closest('.el-select')
        ;(trigger as HTMLElement).dispatchEvent(new MouseEvent('click', { bubbles: true }))
        await sleep(300)
        const hit = findDropdownItem(text)
        if (!hit) return { index: 0, op: action.op, target: action.target, ok: false, error: `下拉选项「${text}」未找到` }
        hit.click()
        return { index: 0, op: action.op, target: action.target, ok: true, data: `已选择「${text}」` }
      }
      if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
        if (el.readOnly) return { index: 0, op: action.op, target: action.target, ok: false, error: '目标为只读控件' }
        nativeSetValue(el, text)
        return { index: 0, op: action.op, target: action.target, ok: true, data: `已填入「${text.slice(0, 20)}」` }
      }
      return { index: 0, op: action.op, target: action.target, ok: false, error: `L3 暂不支持该控件类型（${el.tagName}）` }
    }
    case 'get-state': {
      const items = collectDomState()
      return { index: 0, op: action.op, target: action.target, ok: true, data: { domElements: items } }
    }
    case 'expect': {
      const p = (action.params ?? {}) as { type?: string; expected?: unknown; comparator?: string }
      const expected = String(p.expected ?? '')
      const bodyText = document.body.innerText ?? ''
      const pass = p.comparator === 'notEmpty' ? bodyText.trim().length > 0 : bodyText.includes(expected)
      return {
        index: 0,
        op: action.op,
        target: action.target,
        ok: pass,
        data: { actual: pass ? expected : '(页面文本未包含目标内容)', expected, pass },
        error: pass ? undefined : `页面文本未包含「${expected}」`,
      }
    }
    default:
      return {
        index: 0,
        op: action.op,
        target: action.target,
        ok: false,
        error: `当前页面无注册执行器（L3 仅支持 click/fill/get-state/expect），不支持「${action.op}」`,
      }
  }
}

function parseDomTarget(target?: string): number {
  const m = /^#ai-(\d+)$/.exec((target ?? '').trim())
  if (!m) throw new Error(`L3 目标必须是页面清单 domElements 中的 #ai-N（收到「${target ?? '空'}」；先 get-state 查看可用编号）`)
  return Number(m[1])
}

function requireDomEl(index: number): HTMLElement {
  const el = queryDomElement(index)
  if (!el) throw new Error(`DOM 元素 #ai-${index} 不存在（页面可能已变化，请重新 get-state）`)
  return el
}

function findDropdownItem(text: string): HTMLElement | null {
  const items = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item:not(.hidden)'))
  return items.find((it) => it.textContent?.trim() === text) ?? items.find((it) => it.textContent?.includes(text)) ?? null
}

function collectDomState(): { index: number; label?: string; value?: string }[] {
  const out: { index: number; label?: string; value?: string }[] = []
  document.querySelectorAll<HTMLElement>('[data-ai-idx]').forEach((el) => {
    const anyEl = el as unknown as Record<string, string>
    const input = el as HTMLInputElement
    out.push({
      index: Number(el.getAttribute('data-ai-idx')),
      label: el.getAttribute('aria-label') || el.getAttribute('placeholder') || undefined,
      value: anyEl.value !== undefined ? String(anyEl.value).slice(0, 60) : undefined,
    })
    void input
  })
  return out
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

// ==================== L2 页面动作注册（组合式） ====================

/**
 * L2 注册层：手写管理页声明语义动作与组件清单。
 *
 * @example
 * ```ts
 * const unregister = registerPageActions({
 *   routePath: route.path,
 *   pageType: 'admin',
 *   manifest: () => ({ pageType: 'admin', title: '模型配置', formFields: [...], buttons: ['save','test'] }),
 *   handlers: {
 *     'set-value': (a) => { form[a.field!] = a.value; return { ok: true } },
 *     'click': (a) => { if (a.target === 'save') return onSave(); ... },
 *     'get-state': () => ({ ok: true, data: { form: { ...form, apiKey: '***' } } }),
 *   },
 * })
 * onUnmounted(unregister)
 * ```
 */
export function registerPageActions(config: {
  routePath: string
  pageType: string
  manifest: () => Record<string, unknown>
  handlers: Record<string, (action: AiPageAction) => ActionOutcome | Promise<ActionOutcome>>
}): () => void {
  const executor: PageExecutor = {
    routePath: config.routePath,
    pageType: config.pageType,
    manifest: config.manifest,
    async execute(action) {
      const handler = config.handlers[action.op]
      if (!handler) {
        return { ok: false, error: `本页面未注册「${action.op}」动作（可用：${Object.keys(config.handlers).join('/')})` }
      }
      return await handler(action)
    },
  }
  return registerPageExecutor(executor)
}
