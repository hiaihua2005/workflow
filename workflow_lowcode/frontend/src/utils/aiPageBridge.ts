/**
 * AI 页面操作执行核心（与 AI 悬浮窗解耦的独立层）。
 *
 * 职责：
 * 1. 可操作组件注册表：页面组件（列表/表单）挂载时注册实例与能力清单，
 *    构成 page_action 的寻址空间与 LLM 上下文（describePage）；
 * 2. 结构化动作执行（page_action）：setFilter/refresh/fillForm/submitForm/navigate…，
 *    路由到组件 expose 的可编程 API；
 * 3. DOM 语义树 + 原语执行器（dom_action 兜底）：可交互元素编号清单 +
 *    click/input/select 原语（原生 setter + 事件派发，兼容 Vue/element-plus）；
 * 4. 动作录制：记录每次执行的动作为 Playwright spec（自动化测试工具）。
 *
 * 安全边界：写操作（submitForm/危险按钮 click）执行前弹确认框；
 * navigate 走白名单回调（由悬浮窗注入 router）。
 */
import { ElMessageBox } from 'element-plus'

// ==================== 组件注册表 ====================

export interface AiFieldMeta {
  key: string
  label?: string
  type?: string
}

export interface AiComponentMeta {
  /** 组件寻址 id：列表 = dataSourceId；表单 = form:<formDefId>:<uid> */
  id: string
  kind: 'list' | 'form'
  title?: string
  /** 实际具备的能力（注册时按实例方法探测） */
  capabilities: string[]
  fields?: AiFieldMeta[]
}

interface RegisteredComponent {
  meta: AiComponentMeta
  instance: Record<string, any>
  /** 动态清单：注册时元数据未就绪（页面异步加载），describePage 时再求值。 */
  getMeta?: () => AiComponentMeta | null
}

const components = new Map<string, RegisteredComponent>()

/** 注册可操作组件，返回注销函数（同名覆盖：后注册者优先，常见于 keep-alive 重建）。 */
export function registerAiComponent(meta: AiComponentMeta, instance: Record<string, any>): () => void {
  components.set(meta.id, { meta, instance })
  return () => {
    const cur = components.get(meta.id)
    // 仅当仍是本实例时才移除（防止旧实例注销覆盖新实例）
    if (cur && cur.instance === instance) {
      components.delete(meta.id)
    }
  }
}

/**
 * 注册动态清单组件：元数据由 getMeta 在每次 describePage 时求值
 * （返回 null 表示本轮未就绪，跳过）。适用于页面数据异步就绪后才可描述的场景。
 */
export function registerDynamicComponent(
  getMeta: () => AiComponentMeta | null,
  instance: Record<string, any> = {},
): () => void {
  const key = `__dyn_${Date.now()}_${Math.floor(Math.random() * 1e6)}`
  components.set(key, { meta: null as unknown as AiComponentMeta, instance, getMeta })
  return () => {
    components.delete(key)
  }
}

export function unregisterAiComponent(id: string): void {
  components.delete(id)
}

/** 按实例实际方法裁剪能力清单。 */
function detectCapabilities(instance: Record<string, any>, candidates: string[]): string[] {
  return candidates.filter((c) => typeof instance[c] === 'function')
}

/** L2 页面分发钩子（由 aiPageExecutor 适配器注入；返回 null 表示 componentId 非 L2 页面）。 */
let l2Dispatcher: ((componentId: string, args: Record<string, any>) => Promise<ActionResult | null>) | null = null

export function setL2PageDispatcher(
  fn: (componentId: string, args: Record<string, any>) => Promise<ActionResult | null>,
): void {
  l2Dispatcher = fn
}

/** 当前页面可操作组件清单（随对话上下文上报给 LLM）。 */
export function describePage(): { components: AiComponentMeta[] } {
  const out: AiComponentMeta[] = []
  for (const entry of components.values()) {
    try {
      const meta = entry.getMeta ? entry.getMeta() : entry.meta
      if (meta) out.push({ ...meta })
    } catch {
      /* 动态清单求值失败（页面未就绪）→ 本轮跳过 */
    }
  }
  return { components: out }
}

// ==================== 结构化动作执行（page_action） ====================

export interface ActionResult {
  ok: boolean
  message: string
  data?: unknown
}

/** 导航回调：由悬浮窗注入 router（避免 bridge 依赖 router 实例）。 */
let navigateHandler: ((path: string) => void) | null = null

export function setNavigateHandler(handler: (path: string) => void): void {
  navigateHandler = handler
}

/** 写操作确认：用户取消 → 返回 ok=false（如实回传模型）。 */
async function confirmDangerous(text: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(text, 'AI 操作确认', { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' })
    return true
  } catch {
    return false
  }
}

/**
 * 执行结构化页面动作（page_action 的前端执行体）。
 *
 * @param opts autoConfirm=true 时跳过写操作确认框（仅回放场景使用）
 */
export async function executePageAction(
  args: Record<string, any>,
  opts?: { autoConfirm?: boolean },
): Promise<ActionResult> {
  const action = String(args?.action ?? '')
  const componentId = String(args?.componentId ?? '')

  // L2 页面分发钩子优先：注册层页面（用户/角色/模型配置/搭建页等）的动作字典
  if (l2Dispatcher) {
    const l2 = await l2Dispatcher(componentId, args)
    if (l2) return l2
  }

  if (action === 'navigate') {
    const path = String(args?.path ?? '')
    if (!path) return { ok: false, message: '缺少 path' }
    if (!navigateHandler) return { ok: false, message: '导航能力未就绪' }
    navigateHandler(path)
    return { ok: true, message: `已跳转到 ${path}` }
  }

  const registered = components.get(componentId)
  if (!registered) {
    return { ok: false, message: `组件 ${componentId} 不在当前页面（或未注册可操作能力）` }
  }
  const inst = registered.instance

  try {
    switch (action) {
      case 'setFilter': {
        if (typeof inst.setFilter !== 'function') return { ok: false, message: '该组件不支持查询条件' }
        const filter = (args?.filter ?? {}) as Record<string, unknown>
        if (!filter || typeof filter !== 'object' || Object.keys(filter).length === 0) {
          return { ok: false, message: '缺少 filter（{字段: 值}）' }
        }
        inst.setFilter(filter)
        return { ok: true, message: `已按条件查询：${Object.entries(filter).map(([k, v]) => `${k}=${v}`).join('、')}` }
      }
      case 'resetFilter':
        if (typeof inst.resetFilter !== 'function') return { ok: false, message: '该组件不支持重置' }
        inst.resetFilter()
        return { ok: true, message: '已清空查询条件并刷新' }
      case 'refresh':
        if (typeof inst.refresh !== 'function') return { ok: false, message: '该组件不支持刷新' }
        inst.refresh()
        return { ok: true, message: '已刷新列表' }
      case 'openCreate':
        if (typeof inst.openCreate !== 'function') return { ok: false, message: '该组件不支持新增' }
        inst.openCreate()
        return { ok: true, message: '已打开新增表单' }
      case 'fillForm': {
        if (typeof inst.setFields !== 'function') return { ok: false, message: '该组件不支持填值' }
        const values = (args?.values ?? {}) as Record<string, unknown>
        if (!values || typeof values !== 'object' || Object.keys(values).length === 0) {
          return { ok: false, message: '缺少 values（{字段: 值}）' }
        }
        inst.setFields(values)
        return { ok: true, message: `已填充 ${Object.keys(values).length} 个表单字段` }
      }
      case 'validateForm':
        if (typeof inst.validate !== 'function') return { ok: false, message: '该组件不支持校验' }
        {
          const pass = await inst.validate()
          return pass
            ? { ok: true, message: '表单校验通过' }
            : { ok: false, message: '表单校验未通过（页面已标红错误项）' }
        }
      case 'getFormData':
        if (typeof inst.getFormData !== 'function') return { ok: false, message: '该组件不支持读取数据' }
        return { ok: true, message: '已读取表单数据', data: inst.getFormData() }
      case 'submitForm': {
        if (typeof inst.submit !== 'function') return { ok: false, message: '该组件不支持提交' }
        const confirmed = opts?.autoConfirm === true || (await confirmDangerous('AI 即将提交当前表单数据，是否继续？'))
        if (!confirmed) return { ok: false, message: '用户取消了提交' }
        const ok = await inst.submit()
        return ok
          ? { ok: true, message: '表单已提交' }
          : { ok: false, message: '提交失败（校验未通过或接口报错，详见页面提示）' }
      }
      default:
        return { ok: false, message: `未知动作: ${action}` }
    }
  } catch (e) {
    return { ok: false, message: `执行异常: ${(e as Error)?.message ?? e}` }
  }
}

// ==================== DOM 语义树 + 原语执行器（dom_action 兜底） ====================

interface DomNode {
  el: HTMLElement
  /** 语义描述（回传给模型的清单行） */
  desc: string
}

let domIndex: DomNode[] = []

const AI_SKIP_SELECTOR = '.ai-orb,.ai-window,[data-ai-skip],script,style,noscript,template'
const INTERACTIVE_SELECTOR = [
  'input:not([type=hidden])',
  'textarea',
  'select',
  'button',
  'a[href]',
  '[role=button]',
  '[role=switch]',
  '.el-switch',
  '.el-checkbox',
  '.el-radio',
  '.el-rate',
  'tr',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/** 危险按钮文案特征：命中后原语 click 需用户确认。 */
const RISKY_TEXT = /(删除|提交|保存|发布|停用|启用|作废|审批|通过|驳回)/

function visible(el: HTMLElement): boolean {
  // offsetParent 为 null 的两种情况：display:none 隐藏链 / position:fixed（fixed 元素视为可见）
  if (el.offsetParent !== null) return true
  return getComputedStyle(el).position === 'fixed'
}

/** 元素语义标签：aria-label > placeholder > 关联 label > 按钮/链接文本 > title > name。 */
function describeElement(el: HTMLElement): string {
  const attr = el.getAttribute('aria-label') ?? el.getAttribute('placeholder') ?? el.getAttribute('title')
  if (attr) return attr.trim().slice(0, 30)
  const formItem = el.closest('.el-form-item')
  const label = formItem?.querySelector('.el-form-item__label')?.textContent
  if (label) return label.trim().slice(0, 30)
  const text = (el.textContent ?? '').trim().replace(/\s+/g, ' ')
  if (text) return text.slice(0, 30)
  return el.getAttribute('name') ?? el.tagName.toLowerCase()
}

function inputKind(el: HTMLElement): string {
  if (el instanceof HTMLTextAreaElement) return 'textarea'
  if (el instanceof HTMLInputElement) return `input(${el.type || 'text'})`
  return el.tagName.toLowerCase()
}

/**
 * 序列化当前页面为「DOM 语义树」（可交互元素编号清单）。
 *
 * - 纳入 body 下 teleported 弹层（element-plus 弹窗/下拉挂 body，属于页面状态的一部分）；
 * - 过滤不可见元素与 AI 自身（悬浮窗/球）；
 * - CodeMirror 编辑器仅标注不索引（原语 input 对 CM 无效，须走结构化动作）；
 * - 表格行提供编号（行点击联动是低代码页面的高频交互）。
 */
export function serializeAiDom(): string {
  domIndex = []
  const lines: string[] = ['当前页面可交互元素（dom_action 的 target 取方括号编号）：']

  const walk = (root: ParentNode, inOverlay: boolean) => {
    const els = root.querySelectorAll<HTMLElement>(INTERACTIVE_SELECTOR)
    for (const el of els) {
      if (el.closest(AI_SKIP_SELECTOR)) continue
      // CodeMirror 内容区：跳过其内部可聚焦元素，整体标注一次
      const cmHost = el.closest('.cm-editor')
      if (cmHost) {
        if (!domIndex.some((n) => n.el === cmHost)) {
          domIndex.push({ el: cmHost as HTMLElement, desc: 'CodeMirror 表达式编辑器' })
          lines.push(`[${domIndex.length}] codemirror "表达式编辑框（请用 page_action 填值，原语无效）"`)
        }
        continue
      }
      if (!visible(el)) continue
      if (el.closest('.el-select-dropdown') && !el.classList.contains('el-select-dropdown__item')) continue

      const desc = describeElement(el)
      // el-select：整框只标一次，类型标为 select（原语应使用 select；input 原语对内层
      // readonly 输入框无效）。带出当前选中/占位文案，供模型判断是否需要切换选项。
      const selectRoot = el.closest('.el-select') as HTMLElement | null
      if (selectRoot) {
        if (domIndex.some((n) => n.el === selectRoot)) continue
        const curEl = selectRoot.querySelector('.el-select__selected-item, .el-select__placeholder')
        const cur = curEl?.textContent?.trim() ?? ''
        // 无表单标签的查询栏下拉：用当前选中/占位文案作为语义描述
        const selectDesc = desc !== 'input' && desc !== 'select' ? desc : (cur || desc)
        const disabled = selectRoot.classList.contains('is-disabled')
        domIndex.push({ el: selectRoot, desc: selectDesc })
        lines.push(`[${domIndex.length}] select "${selectDesc}"${cur ? ` 当前:"${cur.slice(0, 24)}"` : ''}${disabled ? ' [禁用]' : ''}`)
        continue
      }

      let extra = ''
      if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
        extra = el.value ? ` 当前值:"${el.value.slice(0, 20)}"` : ''
        extra += el.disabled ? ' [禁用]' : ''
      }
      const isOverlayItem = !inOverlay && el.closest('.el-select-dropdown') != null
      const kind = isOverlayItem ? 'option' : inputKind(el)
      domIndex.push({ el, desc })
      lines.push(`[${domIndex.length}] ${kind} "${desc}"${extra}`)
    }
  }

  walk(document.body, false)
  // teleported 弹层（el-popper / el-overlay 挂 body 下的浮层）
  for (const pop of document.body.querySelectorAll<HTMLElement>('.el-popper, .el-overlay')) {
    if (visible(pop)) walk(pop, true)
  }
  if (domIndex.length === 0) lines.push('（页面无可交互元素）')
  return lines.join('\n')
}

function parseTargetIndex(target: string): number {
  const m = /\[(\d+)\]/.exec(String(target ?? '').trim())
  return m ? Number(m[1]) : NaN
}

/** 原生 setter + input 事件：Vue v-model / element-plus 唯一认可的"用户输入"。 */
function setNativeValue(el: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  const proto = el instanceof HTMLTextAreaElement
    ? HTMLTextAreaElement.prototype
    : HTMLInputElement.prototype
  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set
  if (setter) {
    setter.call(el, value)
  } else {
    el.value = value
  }
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.dispatchEvent(new Event('change', { bubbles: true }))
}

/** el-select 选择：定位触发器 → 点开 → 轮询**可见**弹层选项（关闭的弹层仍留在 DOM，必须过滤）→ 按文本点选。 */
async function selectOption(el: HTMLElement, value: string): Promise<string> {
  // 命中的可能是内层 input/span——向上找 el-select 根，再取真正的点击触发器
  const selectRoot = el.closest('.el-select') ?? el
  const trigger = selectRoot.querySelector<HTMLElement>('.el-select__wrapper') ?? (el as HTMLElement)
  trigger.scrollIntoView({ block: 'center' })
  trigger.click()

  const item = await new Promise<HTMLElement | null>((resolve) => {
    let tries = 0
    const timer = setInterval(() => {
      tries++
      // 只考虑当前可见的弹层选项：关闭的 el-select-dropdown 仍留在 DOM（display:none），
      // 不过滤就会点中隐藏选项——表现为“点了没反应”
      const items = [...document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')]
        .filter((d) => visible(d))
      const hit = items.find((d) => {
        const t = (d.textContent ?? '').trim()
        return t === value || t.includes(value)
      })
      if (hit) {
        clearInterval(timer)
        resolve(hit)
      } else if (tries > 25) {
        clearInterval(timer)
        resolve(null)
      }
    }, 100)
  })
  if (!item) return `未在下拉中找到可见选项"${value}"`
  item.click()
  await new Promise((r) => setTimeout(r, 150))
  return `已选择"${value}"`
}

/**
 * 执行 DOM 原语（dom_action 的前端执行体）。
 *
 * @returns 执行结果 + 执行后的新语义树（模型据其决定下一步）
 */
export async function executeDomPrimitive(args: Record<string, any>): Promise<ActionResult & { tree?: string }> {
  const action = String(args?.action ?? '')
  if (action === 'readPage') {
    const tree = serializeAiDom()
    return { ok: true, message: '已读取当前页面语义树', tree }
  }

  const idx = parseTargetIndex(args?.target)
  if (Number.isNaN(idx) || idx < 1 || idx > domIndex.length) {
    return { ok: false, message: `目标编号无效: ${args?.target}（请先用 readPage 或上次回传的语义树）` }
  }
  const node = domIndex[idx - 1]

  try {
    let message: string
    if (action === 'click') {
      message = await domClick(node, false)
    } else if (action === 'input') {
      message = await domInput(node, String(args?.value ?? ''))
    } else if (action === 'select') {
      message = await domSelect(node, String(args?.value ?? ''))
    } else {
      return { ok: false, message: `未知原语: ${action}` }
    }
    // DOM 稳定一拍后再取树（表格刷新/弹层开合是异步的）
    await new Promise((r) => setTimeout(r, 400))
    return { ok: true, message, tree: serializeAiDom() }
  } catch (e) {
    return { ok: false, message: `执行异常: ${(e as Error)?.message ?? e}`, tree: serializeAiDom() }
  }
}

/** 原语执行体（编号寻址与回放共用）：click 含危险按钮确认。 */
async function domClick(node: DomNode, autoConfirm: boolean): Promise<string> {
  if (!autoConfirm && RISKY_TEXT.test(node.desc)) {
    const confirmed = await confirmDangerous(`AI 即将点击「${node.desc}」，是否继续？`)
    if (!confirmed) throw new Error('用户取消了点击')
  }
  node.el.scrollIntoView({ block: 'center' })
  node.el.click()
  return `已点击 "${node.desc}"`
}

async function domInput(node: DomNode, value: string): Promise<string> {
  const el = node.el
  // 下拉框的内层 input 是 readonly，input 原语改不动模型——明确指路 select 原语
  if (el.closest('.el-select')) {
    throw new Error(`"${node.desc}" 是下拉框，请改用 select 原语（dom_action action=select）`)
  }
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    setNativeValue(el, value)
    return `已向 "${node.desc}" 填入 "${value}"`
  }
  throw new Error(`"${node.desc}" 不是可输入元素`)
}

async function domSelect(node: DomNode, value: string): Promise<string> {
  return selectOption(node.el, value)
}

// ==================== 统一工具调用入口（client_tool 分发） ====================

/**
 * 执行一个客户端工具调用（AiAssistantOrb 的 client_tool 事件入口）。
 *
 * @param name 工具名：page_action / dom_action
 * @param args 工具参数
 */
export async function executeToolCall(name: string, args: Record<string, any>): Promise<Record<string, unknown>> {
  const result =
    name === 'page_action'
      ? await executePageAction(args)
      : name === 'dom_action'
        ? await executeDomPrimitive(args)
        : { ok: false, message: `未知客户端工具: ${name}` }
  record(name, args, result)
  return result as Record<string, unknown>
}

// ==================== 动作录制 → Playwright spec（自动化测试工具） ====================
//
// 两个来源统一入录制流：
// - source='ai'：AI 通过 page_action/dom_action 执行的动作（executeToolCall 回调）；
// - source='manual'：用户传统手动操作（captureRecorder 全局捕获 click/input/select/nav）。
// 录制结束后可确定性转译（toPlaywrightSpec）或交 LLM 生成用例（/api/v1/ai/test-spec）。

export interface ActionRecord {
  time: string
  source: 'ai' | 'manual'
  /** AI 动作的工具名（source=ai）；手动操作为 kind：click/input/select/nav */
  tool?: string
  kind?: string
  args: Record<string, any>
  result: { ok: boolean; message: string }
}

let recording = false
let records: ActionRecord[] = []
/** 录制变化订阅（bridge 是普通模块，UI 层借此拿到响应式更新） */
const recordListeners: (() => void)[] = []

function notifyRecordsChanged(): void {
  recordListeners.forEach((cb) => {
    try {
      cb()
    } catch {
      /* 单个订阅者异常不影响其他 */
    }
  })
}

/** 订阅录制变化（新增/清空/停止），返回取消订阅函数。 */
export function onRecordsChanged(cb: () => void): () => void {
  recordListeners.push(cb)
  return () => {
    const i = recordListeners.indexOf(cb)
    if (i >= 0) recordListeners.splice(i, 1)
  }
}

export function startRecording(): void {
  records = []
  recording = true
  startCapture()
  notifyRecordsChanged()
}

export function stopRecording(): void {
  recording = false
  stopCapture()
  notifyRecordsChanged()
}

export function isRecording(): boolean {
  return recording
}

export function getRecords(): ActionRecord[] {
  return [...records]
}

export function clearRecords(): void {
  records = []
  notifyRecordsChanged()
}

function record(tool: string, args: Record<string, any>, result: unknown): void {
  if (!recording) return
  const r = result as { ok?: boolean; message?: string }
  records.push({
    time: new Date().toISOString(),
    source: 'ai',
    tool,
    args,
    result: { ok: r?.ok !== false, message: r?.message ?? '' },
  })
  notifyRecordsChanged()
}

// ==================== 手动操作捕获（captureRecorder） ====================

let captureActive = false
const inputTimers = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>()

/** 手动操作入录制流（含 UI 通知）。 */
function pushManual(kind: string, args: Record<string, any>, message: string): void {
  records.push({
    time: new Date().toISOString(),
    source: 'manual',
    kind,
    args,
    result: { ok: true, message },
  })
  notifyRecordsChanged()
}

/** history 导航打点：包一层 pushState/replaceState + popstate。 */
function recordNav(path: string): void {
  if (!recording) return
  pushManual('nav', { path }, path)
}

function onCaptureClick(e: Event): void {
  if (!recording) return
  const el = (e.target as HTMLElement | null)?.closest?.(INTERACTIVE_SELECTOR) as HTMLElement | null
  if (!el || el.closest(AI_SKIP_SELECTOR)) return
  pushManual('click', { desc: describeElement(el), risky: RISKY_TEXT.test(describeElement(el)) }, describeElement(el))
}

function onCaptureInput(e: Event): void {
  if (!recording) return
  const el = e.target as HTMLInputElement | HTMLTextAreaElement | null
  if (!el || el.disabled || el.closest(AI_SKIP_SELECTOR)) return
  if (!(el instanceof HTMLInputElement) && !(el instanceof HTMLTextAreaElement)) return
  // 防抖：同一元素的连续输入只记最终值
  const prev = inputTimers.get(el)
  if (prev) clearTimeout(prev)
  inputTimers.set(
    el,
    setTimeout(() => {
      inputTimers.delete(el)
      pushManual('input', { desc: describeElement(el), value: el.value }, describeElement(el))
    }, 600),
  )
}

function onCaptureChange(e: Event): void {
  if (!recording) return
  const el = e.target as HTMLSelectElement | null
  if (!el || !(el instanceof HTMLSelectElement) || el.closest(AI_SKIP_SELECTOR)) return
  pushManual('select', { desc: describeElement(el), value: el.selectedOptions[0]?.text ?? el.value }, describeElement(el))
}

function startCapture(): void {
  if (captureActive) return
  captureActive = true
  document.addEventListener('click', onCaptureClick, true)
  document.addEventListener('input', onCaptureInput, true)
  document.addEventListener('change', onCaptureChange, true)
  // SPA 导航打点：包裹 pushState/replaceState + popstate
  const wrap = (name: 'pushState' | 'replaceState') => {
    const orig = history[name].bind(history)
    history[name] = (data: unknown, unused: string, url?: string | URL | null) => {
      orig(data, unused, url)
      if (url) recordNav(String(new URL(url, location.origin).pathname))
    }
  }
  wrap('pushState')
  wrap('replaceState')
  window.addEventListener('popstate', () => recordNav(location.pathname))
}

function stopCapture(): void {
  if (!captureActive) return
  captureActive = false
  document.removeEventListener('click', onCaptureClick, true)
  document.removeEventListener('input', onCaptureInput, true)
  document.removeEventListener('change', onCaptureChange, true)
}

const e = (s: unknown): string => JSON.stringify(String(s ?? ''))

/**
 * 把录制的动作序列转译为 Playwright spec（best-effort）：
 * 结构化动作映射到 getByRole/getByLabel 定位；DOM 原语映射到 getByText/locator 编号注释。
 * 生成后需人工校对选择器与断言，作为回归基线维护。
 *
 * @param list 缺省用当前录制缓冲（getRecords）；可传入用例的记录集
 */
export function toPlaywrightSpec(routeHint = '/', list?: ActionRecord[]): string {
  const caseRecords = list ?? getRecords()
  const lines: string[] = [
    `// 由 AI 页面操作录制生成（${new Date().toLocaleString()}），请人工校对定位器与补全断言。`,
    `import { test, expect } from '@playwright/test'`,
    '',
    `test('recorded flow', async ({ page }) => {`,
    `  await page.goto(${e(routeHint)})`,
  ]
  for (const r of caseRecords) {
    const a = r.args ?? {}
    if (r.source === 'manual') {
      // 手动操作：click/input/select/nav
      switch (r.kind) {
        case 'nav':
          lines.push(`  await page.goto(${e(a.path)})`)
          break
        case 'click':
          if (a.risky) lines.push(`  // ⚠ 危险操作，确认弹窗需处理：${a.desc}`)
          lines.push(`  await page.getByText(${e(a.desc)}).first().click()`)
          break
        case 'input':
          lines.push(`  await page.getByPlaceholder(${e(a.desc)}).or(page.getByLabel(${e(a.desc)})).first().fill(${e(a.value)})`)
          break
        case 'select':
          lines.push(`  await page.getByRole('combobox', { name: ${e(a.desc)} }).click()`)
          lines.push(`  await page.getByRole('option', { name: ${e(a.value)} }).click()`)
          break
        default:
          lines.push(`  // TODO: 未转译手动操作 ${r.kind}`)
      }
      lines.push(`  // 手动操作: ${a.desc}${a.value != null ? ' = ' + a.value : ''}`)
      continue
    }
    if (r.tool === 'page_action') {
      switch (a.action) {
        case 'navigate':
          lines.push(`  await page.goto(${e(a.path)})`)
          break
        case 'setFilter':
          for (const [k, v] of Object.entries(a.filter ?? {})) {
            lines.push(`  // setFilter ${k}=${JSON.stringify(v)}（组件 ${a.componentId}）`)
            lines.push(`  await page.getByPlaceholder(/${k}/i).first().fill(${e(v)})`)
          }
          lines.push(`  await page.getByRole('button', { name: '查询' }).click()`)
          break
        case 'refresh':
          lines.push(`  await page.reload()`)
          break
        case 'openCreate':
          lines.push(`  await page.getByRole('button', { name: /新增|新建/ }).first().click()`)
          break
        case 'fillForm':
          for (const [k, v] of Object.entries(a.values ?? {})) {
            lines.push(`  await page.getByLabel(${e(k)}).or(page.getByPlaceholder(/${k}/i)).first().fill(${e(v)})`)
          }
          break
        case 'submitForm':
          lines.push(`  await page.getByRole('button', { name: /提交|保存/ }).first().click()`)
          break
        default:
          lines.push(`  // TODO: 未转译动作 ${r.tool}.${a.action}`)
      }
    } else if (r.tool === 'dom_action') {
      const idx = parseTargetIndex(a.target)
      const node = domIndex[idx - 1]
      const desc = node?.desc ?? a.target
      switch (a.action) {
        case 'click':
          lines.push(`  await page.getByText(${e(desc)}).first().click()`)
          break
        case 'input':
          lines.push(`  await page.getByPlaceholder(${e(desc)}).fill(${e(a.value)})`)
          break
        case 'select':
          lines.push(`  await page.getByText(${e(desc)}).first().click()`)
          lines.push(`  await page.getByRole('option', { name: ${e(a.value)} }).click()`)
          break
        default:
          lines.push(`  // TODO: 未转译原语 ${a.action} ${a.target}`)
      }
    }
    lines.push(`  // 结果: ${r.result.ok ? 'ok' : 'fail'} ${r.result.message}`)
  }
  lines.push('})')
  return lines.join('\n')
}

/** 把一条录制记录转成中文自然语言步骤（用例查看/LLM 输入共用口径）。 */
export function describeRecord(r: ActionRecord): string {
  const a = r.args ?? {}
  const value = a.value != null ? `，值“${a.value}”` : ''
  if (r.source === 'ai') {
    switch (r.tool) {
      case 'page_action':
        switch (a.action) {
          case 'setFilter':
            return `设置查询条件并查询：${Object.entries(a.filter ?? {}).map(([k, v]) => `${k} = “${v}”`).join('、') || '（空条件）'}`
          case 'resetFilter': return '清空查询条件并刷新列表'
          case 'refresh': return '刷新列表数据'
          case 'openCreate': return '打开新增表单弹窗'
          case 'fillForm': return `填写表单字段：${Object.entries(a.values ?? {}).map(([k, v]) => `${k} = “${v}”`).join('、') || '（无字段）'}`
          case 'validateForm': return '校验表单'
          case 'submitForm': return '提交表单'
          case 'navigate': return `跳转到页面 ${a.path ?? ''}`
          default: return `执行 ${r.tool}.${a.action ?? ''}`
        }
      case 'dom_action':
        switch (a.action) {
          case 'click': return `点击「${a.target ?? ''}」`
          case 'input': return `在「${a.target ?? ''}」输入${value}`
          case 'select': return `在「${a.target ?? ''}」选择“${a.value ?? ''}”`
          case 'readPage': return '读取页面结构'
          default: return `执行 ${r.tool}.${a.action ?? ''}`
        }
      default:
        return r.result.message || `执行 ${r.tool}`
    }
  }
  switch (r.kind) {
    case 'nav': return `打开页面 ${a.path ?? ''}`
    case 'click': return `点击「${a.desc ?? ''}」`
    case 'input': return `在「${a.desc ?? ''}」输入${value}`
    case 'select': return `在「${a.desc ?? ''}」选择“${a.value ?? ''}”`
    default: return r.result.message || String(r.kind ?? '')
  }
}

// ==================== 测试用例库（多用例持久化 + 回放） ====================

export interface TestCaseMeta {
  id: string
  name: string
  route: string
  createdAt: string
  steps: number
}

interface TestCase extends TestCaseMeta {
  records: ActionRecord[]
}

const CASES_KEY = 'ai_test_cases'

function loadCases(): TestCase[] {
  try {
    const raw = localStorage.getItem(CASES_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function persistCases(list: TestCase[]): void {
  try {
    localStorage.setItem(CASES_KEY, JSON.stringify(list))
  } catch {
    /* 存储不可用时用例仅本次会话有效 */
  }
}

/** 保存当前录制为用例（同名覆盖创建时间）。 */
export function saveTestCase(name: string, route: string, records: ActionRecord[]): TestCaseMeta {
  const list = loadCases()
  const existing = list.findIndex((c) => c.name === name)
  const meta: TestCaseMeta = {
    id: existing >= 0 ? list[existing].id : `tc_${Date.now()}_${Math.floor(Math.random() * 1e4)}`,
    name,
    route,
    createdAt: new Date().toISOString(),
    steps: records.length,
  }
  const testCase: TestCase = { ...meta, records: JSON.parse(JSON.stringify(records)) }
  if (existing >= 0) list[existing] = testCase
  else list.push(testCase)
  persistCases(list)
  return meta
}

export function listTestCases(): TestCaseMeta[] {
  return loadCases().map((c) => ({
    id: c.id,
    name: c.name,
    route: c.route,
    createdAt: c.createdAt,
    steps: c.records.length,
  }))
}

export function getTestCaseRecords(id: string): ActionRecord[] {
  return loadCases().find((c) => c.id === id)?.records ?? []
}

export function deleteTestCase(id: string): void {
  persistCases(loadCases().filter((c) => c.id !== id))
}

/** 删除用例中的单个步骤（UI 侧负责二次确认），同步步数。 */
export function deleteTestCaseStep(id: string, index: number): void {
  const list = loadCases()
  const c = list.find((x) => x.id === id)
  if (!c || index < 0 || index >= c.records.length) return
  c.records.splice(index, 1)
  c.steps = c.records.length
  persistCases(list)
}

export function renameTestCase(id: string, name: string): void {
  const list = loadCases()
  const c = list.find((x) => x.id === id)
  if (c) {
    c.name = name
    persistCases(list)
  }
}

// ==================== 回放引擎 ====================

export interface PlaybackCallbacks {
  onStep?: (index: number, total: number, desc: string, ok: boolean, message: string) => void
  onDone?: (summary: { total: number; passed: number; failed: number; aborted: boolean }) => void
}

let playing = false

export function isPlaying(): boolean {
  return playing
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** 按语义描述在当前页面重定位元素（录制的 desc 即语义指纹）。 */
function matchNodeByDesc(desc: string): DomNode {
  serializeAiDom()
  const hit = domIndex.find((n) => n.desc === desc) ?? domIndex.find((n) => n.desc.includes(desc))
  if (!hit) {
    throw new Error(`页面上未找到元素 "${desc}"（页面结构可能已变化）`)
  }
  return hit
}

/**
 * 回放用例：跨页导航 → 逐步执行（手动记录按语义重定位元素；AI 记录直接走结构化动作，
 * 写操作自动确认——录制时用户已确认过一次）。
 *
 * @param id     用例 id（saveTestCase 返回）
 * @param abortOnFail 步骤失败即中止（默认 true）
 */
export async function playbackTestCase(
  id: string,
  cb: PlaybackCallbacks = {},
  abortOnFail = true,
): Promise<void> {
  if (playing) {
    cb.onDone?.({ total: 0, passed: 0, failed: 0, aborted: true })
    return
  }
  const testCase = loadCases().find((c) => c.id === id)
  if (!testCase || testCase.records.length === 0) {
    cb.onDone?.({ total: 0, passed: 0, failed: 0, aborted: true })
    return
  }

  playing = true
  // 回放动作会被捕获器录成"手动操作"——回放期间暂停捕获，结束后恢复
  const wasRecording = recording
  if (wasRecording) stopCapture()

  const total = testCase.records.length
  let passed = 0
  let failed = 0
  let aborted = false

  try {
    // 跨页：先回到录制来源路由
    if (testCase.route && location.pathname !== testCase.route) {
      navigateHandler?.(testCase.route)
      await wait(1200)
    }

    for (let i = 0; i < total; i++) {
      const r = testCase.records[i]
      const desc = String(r.args?.desc ?? r.args?.path ?? r.result?.message ?? `步骤 ${i + 1}`)
      try {
        if (r.source === 'ai') {
          const res = await executePageAction(r.args, { autoConfirm: true })
          if (!res.ok) throw new Error(res.message)
        } else {
          switch (r.kind) {
            case 'nav': {
              if (location.pathname !== String(r.args?.path)) {
                navigateHandler?.(String(r.args?.path))
                await wait(1000)
              }
              break
            }
            case 'click': {
              const node = matchNodeByDesc(String(r.args?.desc ?? ''))
              await domClick(node, true)
              break
            }
            case 'input': {
              const node = matchNodeByDesc(String(r.args?.desc ?? ''))
              await domInput(node, String(r.args?.value ?? ''))
              break
            }
            case 'select': {
              const node = matchNodeByDesc(String(r.args?.desc ?? ''))
              await domSelect(node, String(r.args?.value ?? ''))
              break
            }
            default:
              throw new Error(`未支持的步骤类型: ${r.kind}`)
          }
        }
        passed++
        cb.onStep?.(i + 1, total, desc, true, 'ok')
      } catch (e) {
        failed++
        const msg = (e as Error)?.message ?? String(e)
        cb.onStep?.(i + 1, total, desc, false, msg)
        if (abortOnFail) {
          aborted = true
          break
        }
      }
      await wait(400)
    }
  } finally {
    playing = false
    if (wasRecording) startCapture()
    cb.onDone?.({ total, passed, failed, aborted })
  }
}

/** 导航回调注入（悬浮窗 mounted 时注册 router.push）。 */
export function initAiPageBridge(handler: (path: string) => void): void {
  setNavigateHandler(handler)
  if (typeof window !== 'undefined') {
    ;(window as any).__aiPageBridge = {
      executeToolCall,
      describePage,
      serializeAiDom,
      startRecording,
      stopRecording,
      getRecords,
      clearRecords,
      toPlaywrightSpec,
      saveTestCase,
      listTestCases,
      playbackTestCase,
    }
  }
}
