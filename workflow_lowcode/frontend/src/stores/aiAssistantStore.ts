import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 对话消息 */
export interface AiMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 助手生成表单的结果信息（用于展示卡片） */
  formResult?: { applied: boolean }
  /** 助手提供的页面入口（点击跳转） */
  navigations?: { label: string; path: string }[]
  /** 页面动作执行结果（多步操作气泡，紧凑芯片展示） */
  actionResults?: import('@/types/aiPageAction').AiActionResult[]
  /** observation 回传消息（隐藏正文，仅显示占位行） */
  observation?: boolean
}

const VISIBLE_KEY = 'ai_assistant_visible'
const MODE_KEY = 'ai_assistant_mode'

let seq = 0
function nextId(): string {
  seq += 1
  return `m_${Date.now()}_${seq}`
}

/**
 * AI 助手状态：显隐（持久化）、对话历史、当前页上下文。
 */
export const useAiAssistantStore = defineStore('aiAssistant', () => {
  // 默认开启：仅当显式存过 '0' 时隐藏
  const visible = ref(localStorage.getItem(VISIBLE_KEY) !== '0')
  const messages = ref<AiMessage[]>([])
  const context = ref<Record<string, unknown> | null>(null)
  /** 面板形态：float=悬浮窗（默认）/ dock=右侧停靠栏（不遮挡页面） */
  const mode = ref<'float' | 'dock'>((localStorage.getItem(MODE_KEY) as 'float' | 'dock') || 'float')
  /** AI 正在执行页面动作（面板收起让出视野 + 悬浮球进度态） */
  const executing = ref(false)
  /** 面板页签：chat=对话 / tests=测试用例 */
  const panelTab = ref<'chat' | 'tests'>('chat')
  /** 危险动作自动确认（测试回放场景可开；默认关闭走确认弹窗） */
  const autoConfirm = ref(false)
  /** 钉住窗口：点击页面区域不自动收起（默认不钉，点击页面即收起让位） */
  const pinned = ref(false)
  /** 收起请求信号（跨组件）：录制开始等场景请求把浮窗收起成球 */
  const collapseTick = ref(0)
  function requestCollapse(): void {
    collapseTick.value += 1
  }

  function setMode(value: 'float' | 'dock'): void {
    mode.value = value
    localStorage.setItem(MODE_KEY, value)
  }

  function toggle(): void {
    setVisible(!visible.value)
  }

  function setVisible(value: boolean): void {
    visible.value = value
    localStorage.setItem(VISIBLE_KEY, value ? '1' : '0')
  }

  function addMessage(
    role: AiMessage['role'],
    content: string,
    formResult?: AiMessage['formResult'],
    navigations?: AiMessage['navigations'],
    extra?: Pick<AiMessage, 'actionResults' | 'observation'>,
  ): AiMessage {
    const message: AiMessage = { id: nextId(), role, content, formResult, navigations, ...extra }
    messages.value.push(message)
    return message
  }

  /** 手动清空对话历史 */
  function clear(): void {
    messages.value = []
  }

  function setContext(value: Record<string, unknown> | null): void {
    context.value = value
  }

  return {
    visible, messages, context, mode, executing, panelTab, autoConfirm, pinned, collapseTick,
    toggle, setVisible, setMode, addMessage, clear, setContext, requestCollapse,
  }
})
