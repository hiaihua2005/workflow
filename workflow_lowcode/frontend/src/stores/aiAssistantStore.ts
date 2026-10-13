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
}

const VISIBLE_KEY = 'ai_assistant_visible'
const DOCK_KEY = 'ai_assistant_dock'

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
  /** 停靠模式：窗体停靠右侧整栏，布局层让位（AdminLayout 读取此状态加右边距） */
  const dockMode = ref(localStorage.getItem(DOCK_KEY) === '1')
  /** 对话窗体开合（提升到 store：布局层随开合让位/收权；开=隐藏悬浮球，关=显示） */
  const windowOpen = ref(false)

  function toggleDock(): void {
    dockMode.value = !dockMode.value
    localStorage.setItem(DOCK_KEY, dockMode.value ? '1' : '0')
  }

  function setWindowOpen(value: boolean): void {
    windowOpen.value = value
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
  ): AiMessage {
    const message: AiMessage = { id: nextId(), role, content, formResult, navigations }
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
    visible,
    messages,
    context,
    dockMode,
    windowOpen,
    toggleDock,
    setWindowOpen,
    toggle,
    setVisible,
    addMessage,
    clear,
    setContext,
  }
})
