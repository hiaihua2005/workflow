/**
 * AI 页面操作协议类型（page_action 动作桥）。
 *
 * 三层执行架构共用同一动作协议：
 * - L1 结构化动作层：低代码运行时页面（VIEW 查询视图 / PAGE 自定义页面 / 表单渲染器）
 * - L2 页面动作注册层：手写管理页按页注册语义动作（useAiPageActions）
 * - L3 DOM 兜底层：无注册执行器的页面，按 DOM 扫描清单（#ai-N）click/fill
 *
 * 后端 agent 通过 page_control 工具下发动作（SSE page_action 帧），
 * 前端执行后把结果作为 observation 消息回传，形成多步闭环。
 */

/** 单个页面动作指令 */
export interface AiPageAction {
  /** 动作类型 */
  op: string
  /** 目标：字段名 / 数据源id / #ai-N（L3）/ 语义按钮名 / 菜单路径（navigate） */
  target?: string
  /** 字段名（set-value/set-filter；也可并入 target） */
  field?: string
  /** 赋值（set-query/set-value/set-filter/fill） */
  value?: unknown
  /** 附加参数（sort:{sort,order}；read-table:{limit}；expect:{type,expected,comparator}） */
  params?: Record<string, unknown>
}

/** 单个动作执行结果 */
export interface AiActionResult {
  /** 动作序号（从 0 开始） */
  index: number
  op: string
  target?: string
  ok: boolean
  /** 失败原因（ok=false 时） */
  error?: string
  /**
   * 返回数据：get-state → 页面状态快照；read-table → 行数组；
   * expect → { actual, expected, pass }；submit → 提交结果说明
   */
  data?: unknown
  /** 执行层：L1 | L2 | L3 | global（navigate） */
  layer?: 'L1' | 'L2' | 'L3' | 'global'
}

/** 危险动作（执行前需要确认） */
export const DANGER_OPS = new Set(['submit', 'delete-rows', 'save-record', 'save-container'])

/** L3 DOM 扫描出的可交互元素 */
export interface AiDomElement {
  /** 稳定定位索引（写入 data-ai-idx，执行时据此查询） */
  index: number
  tag: string
  /** 控件类型：input/textarea/select/button/… */
  type?: string
  /** 标签文本（label/aria-label/placeholder/相邻文本） */
  label?: string
  placeholder?: string
  /** 当前值（input/textarea/select） */
  value?: string
  disabled?: boolean
}

/** 当前页面组件清单（随对话请求上报，后端注入系统提示） */
export interface AiPageManifest {
  /** 页面类型：view=查询视图 / page=自定义页面 / form=表单渲染 / designer=设计器 / admin=管理页 / other=未知(L3) */
  pageType: 'view' | 'page' | 'form' | 'designer' | 'admin' | 'other'
  title?: string
  /** VIEW 轨查询条件字段 */
  queryFields?: { field: string; title: string; control: 'input' | 'dateRange'; matchType?: string }[]
  /** VIEW 轨表格列 */
  tableColumns?: { prop: string; label: string }[]
  /** PAGE 轨数据组件（dataSourceId → 类型/能力） */
  dataSources?: { id: string; type: string; title?: string; capabilities: string[]; fields?: { field: string; title: string; required?: boolean; options?: string[] }[] }[]
  /** 表单字段（FormRenderer / page-form / L2 管理页表单） */
  formFields?: { field: string; title: string; type: string; required?: boolean; options?: string[] }[]
  /** 语义按钮（search/reset/save/test/…） */
  buttons?: string[]
  /** 附加说明 */
  note?: string
  /** L3 DOM 兜底元素清单（无 L1/L2 清单时自动扫描） */
  domElements?: AiDomElement[]
  /** 执行层提示 */
  layer?: 'L1' | 'L2' | 'L3'
}

/** 动作执行选项 */
export interface ExecuteOptions {
  /** 危险动作自动确认（默认 false，走确认弹窗） */
  autoConfirm?: boolean
  /** 确认回调（危险动作前调用，resolve(true) 放行）；缺省视为同意 */
  confirm?: (message: string) => Promise<boolean>
  /** 进度回调 */
  onProgress?: (index: number, total: number, action: AiPageAction) => void
  /** 是否为测试回放（录制器会跳过记录） */
  replay?: boolean
}
