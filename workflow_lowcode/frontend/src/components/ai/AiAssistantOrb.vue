<template>
  <div v-if="showOrb" class="ai-assistant-root">
    <!-- 悬浮球（点击开/关对话窗体；可拖动换位；执行页面动作时显示进度徽标） -->
    <button
      ref="orbEl"
      class="ai-orb"
      :class="{ 'ai-orb-active': windowOpen, 'ai-orb-dragging': orbDragging, 'ai-orb-executing': store.executing }"
      :style="orbStyle"
      :title="windowOpen ? '收起小智' : '打开小智（可拖动）'"
      @pointerdown="onOrbPointerDown"
      @pointermove="onOrbPointerMove"
      @pointerup="onOrbPointerUp"
      @pointercancel="onOrbPointerCancel"
      @click="onOrbClick"
    >
      <RobotIcon class="ai-orb-icon" />
      <span v-if="store.executing && execProgress.total > 0" class="ai-orb-badge">{{ execProgress.current }}/{{ execProgress.total }}</span>
      <span v-else-if="testRecording" class="ai-orb-rec">REC</span>
      <span v-else-if="unread > 0 && !windowOpen" class="ai-orb-unread">{{ unread > 9 ? '9+' : unread }}</span>
      <span v-else-if="store.executing" class="ai-orb-badge ai-orb-badge-dot">…</span>
    </button>

    <!-- 对话窗体：float=完全悬浮可拖动 / dock=右侧停靠栏（不遮挡页面） -->
    <div
      v-if="windowOpen"
      ref="windowEl"
      class="ai-window"
      :class="{ 'ai-window-dock': isDock }"
      :style="isDock ? undefined : winStyle"
    >
      <div
        class="ai-window-header"
        :title="isDock ? '停靠模式' : '拖动移动位置 · 双击复位'"
        @pointerdown="onWindowDragStart"
        @pointermove="onWindowDragMove"
        @pointerup="onWindowDragEnd"
        @pointercancel="onWindowDragEnd"
        @dblclick="resetWindowPos"
      >
        <div class="ai-window-title">
          <span class="ai-header-avatar"><RobotIcon /></span>
          <span class="ai-title">小智·AI 助手</span>
          <span class="ai-tab-group">
            <button class="ai-tab" :class="{ active: store.panelTab === 'chat' }" @click="store.panelTab = 'chat'">对话</button>
            <button class="ai-tab" :class="{ active: store.panelTab === 'tests' }" @click="store.panelTab = 'tests'">用例</button>
          </span>
        </div>
        <div class="ai-window-actions">
          <button
            class="ai-icon-btn"
            :class="{ active: store.pinned }"
            :title="store.pinned ? '取消钉住（点击页面区域自动收起）' : '钉住窗口（点击页面区域不收起）'"
            @click="store.pinned = !store.pinned"
          >
            <el-icon :size="16"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5" /><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z" /></svg></el-icon>
          </button>
          <button
            class="ai-icon-btn"
            :title="isDock ? '切换为悬浮窗' : '停靠到右侧（不遮挡页面）'"
            @click="store.setMode(isDock ? 'float' : 'dock')"
          >
            <el-icon :size="16"><Expand /></el-icon>
          </button>
          <button class="ai-icon-btn" title="清除历史" :disabled="store.messages.length === 0" @click="handleClear">
            <el-icon :size="16"><Delete /></el-icon>
          </button>
          <button class="ai-icon-btn" title="收起" @click="windowOpen = false">
            <el-icon :size="16"><Close /></el-icon>
          </button>
        </div>
      </div>

      <!-- 对话页签 -->
      <template v-if="store.panelTab === 'chat'">
        <div ref="messagesRef" class="ai-messages">
          <div v-if="store.messages.length === 0" class="ai-empty">
            你好，我是小智。可以帮你生成表单、指引功能入口，还能<b>直接操作当前页面</b>——试试说：
            "帮我把订单号填成 SO2025 然后查询" 或 "读取表格数据"。
          </div>

          <div v-for="message in store.messages" :key="message.id" class="ai-msg" :class="message.role">
            <template v-if="message.role === 'assistant'">
              <span class="ai-msg-avatar"><RobotIcon /></span>
              <div class="ai-msg-body">
                <div class="ai-bubble ai-bubble-md">
                  <MarkdownRenderer :text="message.content" :pages="menuPages" @navigate="navigate" />
                </div>
                <!-- 页面动作执行芯片 -->
                <div v-if="message.actionResults && message.actionResults.length" class="ai-action-list">
                  <span
                    v-for="(r, i) in message.actionResults"
                    :key="i"
                    class="ai-action-chip"
                    :class="{ fail: !r.ok }"
                    :title="r.error || JSON.stringify(r.data ?? '')"
                  >{{ r.op }}{{ r.target ? `(${r.target})` : '' }} {{ r.ok ? '✓' : '✗' }}</span>
                </div>
                <div v-if="message.formResult" class="ai-form-card">
                  <span v-if="message.formResult.applied">✅ 已应用到当前表单</span>
                  <span v-else>表单已生成。打开表单设计器后可应用。</span>
                </div>
                <div v-if="message.navigations && message.navigations.length" class="ai-nav-list">
                  <el-tag
                    v-for="nav in message.navigations"
                    :key="nav.path"
                    class="ai-nav-tag"
                    type="primary"
                    effect="plain"
                    @click="navigate(nav.path)"
                  >
                    → {{ nav.label }}
                  </el-tag>
                </div>
              </div>
            </template>
            <!-- observation 回传：隐藏 JSON 正文，仅显示占位行 -->
            <div v-else-if="message.observation" class="ai-obs-line">🔁 已回传页面执行结果</div>
            <div v-else class="ai-bubble">{{ message.content }}</div>
          </div>

          <div v-if="sending" class="ai-msg assistant">
            <span class="ai-msg-avatar"><RobotIcon /></span>
            <div class="ai-msg-body"><div class="ai-bubble">正在处理…</div></div>
          </div>

          <!-- 执行页面动作状态行 -->
          <div v-if="store.executing" class="ai-exec-line">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在操作页面 {{ execProgress.total > 0 ? `${execProgress.current}/${execProgress.total}` : '' }}
            <span v-if="execActionLabel" class="ai-exec-op">{{ execActionLabel }}</span>
          </div>
        </div>

        <div class="ai-input">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            resize="none"
            :disabled="sending"
            placeholder="输入问题或页面操作指令，Enter 发送"
            @keydown.enter.exact.prevent="send"
          />
          <el-button class="ai-send-btn" type="primary" :loading="sending" :disabled="!canSend" @click="send">
            <el-icon v-if="!sending"><Position /></el-icon>
          </el-button>
        </div>
      </template>

      <!-- 测试用例页签 -->
      <AiTestPanel v-else :generating="sending" @generate="onGenerateCases" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Close, Position, Expand, Loading } from '@element-plus/icons-vue'
import { chat, type AiChatTurn } from '@/api/ai'
import { useAiAssistantStore } from '@/stores/aiAssistantStore'
import { useAuthStore } from '@/stores/auth'
import { aiActionBus } from '@/utils/aiActionBus'
import { flattenMenuPages } from '@/utils/menuIndex'
import { executeAiActions, getActiveManifest } from '@/utils/aiPageExecutor'
import { aiTestRecorder } from '@/utils/aiTestRecorder'
import type { AiActionResult, AiPageAction } from '@/types/aiPageAction'
import MarkdownRenderer from './MarkdownRenderer.vue'
import RobotIcon from './RobotIcon.vue'
import AiTestPanel from './AiTestPanel.vue'

const route = useRoute()
const router = useRouter()
const store = useAiAssistantStore()
const authStore = useAuthStore()

const windowOpen = ref(false)
const input = ref('')
const sending = ref(false)
const messagesRef = ref<HTMLElement | null>(null)
const windowEl = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

/** 页面动作循环上限（防 LLM 死循环） */
const MAX_ACTION_ROUNDS = 6
/** 执行进度（悬浮球徽标 + 状态行） */
const execProgress = ref({ current: 0, total: 0 })
const execActionLabel = ref('')

/** 停靠模式 */
const isDock = computed(() => store.mode === 'dock')

/** 登录页不显示悬浮球 */
const showOrb = computed(() => store.visible && route.name !== 'Login')
const canSend = computed(() => input.value.trim().length > 0 && !sending.value)
/** 站内可跳转页面（菜单白名单） */
const menuPages = computed(() => flattenMenuPages(authStore.menus))

function toggleWindow() {
  windowOpen.value = !windowOpen.value
}

// ---------------------------------------------------------------- 悬浮球拖动
const AI_ORB_POS_KEY = 'ai-assistant-orb-pos'
const ORB_SIZE = 52
const DRAG_THRESHOLD = 5
/** 球自定义位置；null = 默认右下（right:24 bottom:24） */
const orbEl = ref<HTMLElement | null>(null)
const orbPos = ref<{ x: number; y: number } | null>(null)
const orbDragging = ref(false)
let orbDrag: { startX: number; startY: number; originX: number; originY: number; moved: boolean } | null = null
/** 拖动结束后的那次 click 不再开关窗口 */
let suppressOrbClick = false

const orbStyle = computed(() =>
  orbPos.value
    ? { left: `${orbPos.value.x}px`, top: `${orbPos.value.y}px`, right: 'auto', bottom: 'auto' }
    : undefined,
)

function clampOrbPos(x: number, y: number): { x: number; y: number } {
  return {
    x: Math.min(Math.max(8, x), Math.max(8, window.innerWidth - ORB_SIZE - 8)),
    y: Math.min(Math.max(8, y), Math.max(8, window.innerHeight - ORB_SIZE - 8)),
  }
}

function onOrbPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  const el = orbEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const origin = orbPos.value ?? { x: rect.left, y: rect.top }
  orbDrag = { startX: e.clientX, startY: e.clientY, originX: origin.x, originY: origin.y, moved: false }
  // capture 后拖出球体/视口事件仍持续派发
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onOrbPointerMove(e: PointerEvent) {
  if (!orbDrag) return
  const dx = e.clientX - orbDrag.startX
  const dy = e.clientY - orbDrag.startY
  if (!orbDrag.moved) {
    // 位移超过阈值才进入拖动：小于阈值仍视为点击
    if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return
    orbDrag.moved = true
    orbDragging.value = true
  }
  orbPos.value = clampOrbPos(orbDrag.originX + dx, orbDrag.originY + dy)
}

function endOrbDrag(commit: boolean) {
  if (!orbDrag) return
  const moved = orbDrag.moved
  orbDrag = null
  orbDragging.value = false
  if (!moved) return
  suppressOrbClick = true
  if (commit && orbPos.value) {
    try {
      localStorage.setItem(AI_ORB_POS_KEY, JSON.stringify(orbPos.value))
    } catch {
      /* 存储不可用时位置仅本次会话有效 */
    }
  }
}

function onOrbPointerUp() {
  endOrbDrag(true)
}

function onOrbPointerCancel() {
  endOrbDrag(false)
}

function onOrbClick() {
  // 拖动结束产生的 click 不切换窗口
  if (suppressOrbClick) {
    suppressOrbClick = false
    return
  }
  toggleWindow()
}

// ---------------------------------------------------------------- 对话窗体拖动（仅 float 模式）
const AI_WIN_POS_KEY = 'ai-assistant-window-pos'
/** 窗体自定义位置；null = 默认右下（right:24 bottom:88，不写内联 style） */
const winPos = ref<{ x: number; y: number } | null>(null)
let dragState: { startX: number; startY: number; originX: number; originY: number } | null = null

const winStyle = computed(() =>
  winPos.value
    ? { left: `${winPos.value.x}px`, top: `${winPos.value.y}px`, right: 'auto', bottom: 'auto' }
    : undefined,
)

/** 拖动越界钳制：窗体任何部分都留在视口内 */
function clampWindowPos(x: number, y: number): { x: number; y: number } {
  const w = windowEl.value?.offsetWidth ?? 340
  const h = windowEl.value?.offsetHeight ?? 480
  return {
    x: Math.min(Math.max(8, x), Math.max(8, window.innerWidth - w - 8)),
    y: Math.min(Math.max(8, y), Math.max(8, window.innerHeight - h - 8)),
  }
}

function onWindowDragStart(e: PointerEvent) {
  if (isDock.value) return // 停靠模式不支持拖动
  if (e.button !== 0) return
  // 右上角图标按钮（清除/收起）不触发拖动
  if ((e.target as HTMLElement).closest('.ai-icon-btn')) return
  const el = windowEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const origin = winPos.value ?? { x: rect.left, y: rect.top }
  dragState = { startX: e.clientX, startY: e.clientY, originX: origin.x, originY: origin.y }
  // capture 后 move/up 持续派发到 header，拖出窗体/视口也不中断
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  el.classList.add('ai-window-dragging')
}

function onWindowDragMove(e: PointerEvent) {
  if (!dragState) return
  winPos.value = clampWindowPos(
    dragState.originX + (e.clientX - dragState.startX),
    dragState.originY + (e.clientY - dragState.startY),
  )
}

function onWindowDragEnd() {
  if (!dragState) return
  dragState = null
  windowEl.value?.classList.remove('ai-window-dragging')
  try {
    if (winPos.value) localStorage.setItem(AI_WIN_POS_KEY, JSON.stringify(winPos.value))
  } catch {
    /* 隐私模式等存储不可用时静默忽略（本次会话内拖动仍然有效） */
  }
}

/** 双击 header 复位到默认右下位置 */
function resetWindowPos() {
  winPos.value = null
  try {
    localStorage.removeItem(AI_WIN_POS_KEY)
  } catch {
    /* ignore */
  }
}

onMounted(() => {
  try {
    const orbRaw = localStorage.getItem(AI_ORB_POS_KEY)
    if (orbRaw) {
      const saved = JSON.parse(orbRaw) as { x: number; y: number }
      if (typeof saved?.x === 'number' && typeof saved?.y === 'number') {
        orbPos.value = clampOrbPos(saved.x, saved.y)
      }
    }
  } catch {
    /* ignore */
  }
  try {
    const raw = localStorage.getItem(AI_WIN_POS_KEY)
    if (raw) {
      const saved = JSON.parse(raw) as { x: number; y: number }
      if (typeof saved?.x === 'number' && typeof saved?.y === 'number') winPos.value = saved
    }
  } catch {
    /* ignore */
  }
})

// 打开窗体时钳制一次：视口比上次保存位置时小（如窗口缩小）不会越界
watch(windowOpen, async (open) => {
  if (!open || !winPos.value || isDock.value) return
  await nextTick()
  winPos.value = clampWindowPos(winPos.value.x, winPos.value.y)
})

// ---------------------------------------------------------------- 停靠模式：body 让位
watch(
  [() => store.mode, showOrb, windowOpen],
  () => {
    document.documentElement.classList.toggle('ai-dock-active', isDock.value && showOrb.value && windowOpen.value)
  },
  { immediate: true },
)
onUnmounted(() => document.documentElement.classList.remove('ai-dock-active'))

// ---------------------------------------------------------------- 执行时自动收起（float 模式让出视野）
let wasOpenBeforeExec = false
watch(
  () => store.executing,
  (executing) => {
    if (isDock.value) return
    if (executing) {
      wasOpenBeforeExec = windowOpen.value
      windowOpen.value = false
    } else if (wasOpenBeforeExec) {
      windowOpen.value = true
      wasOpenBeforeExec = false
    }
  },
)

// ---------------------------------------------------------------- 点击页面区域自动收起（遮挡让位）
// 用户点击 AI 窗口外的页面区域 = 回去操作页面了，浮窗自动收起成球，不再遮挡数据区；
// 钉住（store.pinned）/ 停靠模式 / 执行中豁免。收起后 AI 回复完成会有未读徽标提示。
const FLOATING_UI_SELECTOR = [
  '.el-overlay', '.el-message-box', '.el-popper', '.el-message', '.el-notification',
  '.el-picker-panel', '.el-color-dropdown', '.el-dropdown-menu', '.el-select-dropdown',
  '.el-date-picker', '.el-dialog', '.el-drawer', '.el-tooltip__popper',
].join(',')

function onDocClickForCollapse(e: MouseEvent): void {
  if (!windowOpen.value || isDock.value || store.pinned || store.executing) return
  const t = e.target as HTMLElement | null
  if (!t) return
  // AI 自身 UI 与 Element Plus 浮层（append-to-body 的弹窗/下拉/日期面板）内的点击不触发收起
  if (t.closest('.ai-assistant-root') || t.closest(FLOATING_UI_SELECTOR)) return
  windowOpen.value = false
}
document.addEventListener('click', onDocClickForCollapse, true)
onUnmounted(() => document.removeEventListener('click', onDocClickForCollapse, true))

// 录制开始等场景的收起请求
watch(
  () => store.collapseTick,
  () => {
    if (windowOpen.value) windowOpen.value = false
  },
)

// ---------------------------------------------------------------- 未读徽标（收起状态下 AI 有新回复）
const unread = ref(0)
/** 录制中状态（球上 REC 标识；用例库订阅驱动） */
const testRecording = ref(aiTestRecorder.isRecording())
onMounted(() => {
  aiTestRecorder.subscribe(() => {
    testRecording.value = aiTestRecorder.isRecording()
  })
})
watch(
  () => store.messages.length,
  (len, prev) => {
    if (!windowOpen.value && len > prev) {
      const last = store.messages[len - 1]
      // observation 占位消息不计未读
      if (last && last.role === 'assistant' && !last.observation) unread.value += 1
    }
  },
)
watch(windowOpen, (open) => {
  if (open) unread.value = 0
})

function handleClear() {
  controller?.abort()
  controller = null
  sending.value = false
  store.clear()
  ElMessage.success('已清空对话')
}
onUnmounted(() => {
  controller?.abort()
  controller = null
})

/** 新消息滚动到底部 */
watch(
  () => store.messages.length,
  async () => {
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  },
)

// ---------------------------------------------------------------- 对话与页面动作循环

/** 单轮对话：发送消息并收集 page_action；返回本轮下发的动作（无则 null） */
function chatOnce(text: string, extra?: { observation?: boolean; actionResults?: AiActionResult[] }): Promise<AiPageAction[] | null> {
  return new Promise((resolve) => {
    // 历史 = 本轮之前的所有消息（含 observation JSON，LLM 需要据此继续）
    const history: AiChatTurn[] = store.messages.map((m) => ({ role: m.role, content: m.content }))
    store.addMessage('user', text, undefined, undefined, { observation: extra?.observation })
    let gotActions: AiPageAction[] | null = null

    let formToolProduced = false
    let formToolApplied = false
    const menus = menuPages.value
    const manifest = getActiveManifest()

    controller = chat(
      {
        message: text,
        history,
        context: { ...(store.context ?? {}), menus, pageManifest: manifest ? JSON.stringify(manifest) : undefined },
      },
      {
        onToolResult: (name, result) => {
          if (name === 'generate_form_schema') {
            formToolProduced = true
            formToolApplied = tryApplyForm(result)
          }
        },
        onPageAction: (actions) => {
          const list = (gotActions ??= [])
          list.push(...(actions as AiPageAction[]))
        },
        onMessage: (assistantText, navigations) => {
          // 正文已内联链接的页面，不再在底部重复出现
          const extraNavs = (navigations ?? []).filter((nav) => !assistantText.includes(`(${nav.path})`))
          // 测试用例捕获：AI 生成用例时消息为 JSON 数组
          const captured = tryCaptureTestCases(assistantText)
          if (captured > 0) {
            store.addMessage('assistant', `已生成 ${captured} 条测试用例并保存到「用例」页签。`)
          } else {
            store.addMessage(
              'assistant',
              assistantText,
              formToolProduced ? { applied: formToolApplied } : undefined,
              extraNavs.length ? extraNavs : undefined,
            )
          }
        },
        onError: (error) => {
          sending.value = false
          controller = null
          store.addMessage('assistant', `⚠️ ${error?.msg || '对话失败，请重试'}`)
          finish(null)
        },
        onDone: () => {
          sending.value = false
          controller = null
          finish(gotActions)
        },
      },
    )
    // abort 兜底：handleClear 中断时 onDone 不会触发，避免 Promise 悬挂
    const timer = window.setInterval(() => {
      if (!sending.value && controller === null) finish(gotActions)
    }, 400)
    let settled = false
    const finish = (value: AiPageAction[] | null) => {
      if (settled) return
      settled = true
      window.clearInterval(timer)
      resolve(value)
    }
  })
}

/** 执行一组页面动作（确认/进度/执行态包装） */
async function runActions(actions: AiPageAction[]): Promise<AiActionResult[]> {
  execProgress.value = { current: 0, total: actions.length }
  store.executing = true
  try {
    return await executeAiActions(actions, {
      autoConfirm: store.autoConfirm,
      confirm: (message) =>
        ElMessageBox.confirm(message, 'AI 页面操作确认', {
          confirmButtonText: '执行',
          cancelButtonText: '取消',
          type: 'warning',
        })
          .then(() => true)
          .catch(() => false),
      onProgress: (i, total, action) => {
        execProgress.value = { current: i + 1, total }
        execActionLabel.value = action.op + (action.target ? `(${action.target})` : '')
      },
    })
  } finally {
    store.executing = false
    execProgress.value = { current: 0, total: 0 }
    execActionLabel.value = ''
  }
}

/** 入口：发送输入框内容 */
async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  await converse(text)
}

/** 完整对话流：对话 → 执行动作 → 回传观察 → 继续，直到无动作或达上限 */
async function converse(text: string) {
  if (sending.value) return
  sending.value = true
  try {
    let actions = await chatOnce(text)
    let round = 1
    while (actions && actions.length > 0 && round <= MAX_ACTION_ROUNDS) {
      const results = await runActions(actions)
      const okCount = results.filter((r) => r.ok).length
      const summary = okCount === results.length
        ? `已执行 ${results.length} 个页面动作`
        : `页面动作 ${okCount}/${results.length} 成功`
      store.addMessage('assistant', summary, undefined, undefined, { actionResults: results })
      // 回传执行结果，LLM 据此继续（可能再下发动作）
      actions = await chatOnce(
        JSON.stringify({ observation: 'page_action_result', results }),
        { observation: true },
      )
      round += 1
    }
    if (actions && actions.length > 0) {
      store.addMessage('assistant', `⚠️ 连续页面操作已达上限（${MAX_ACTION_ROUNDS} 轮），请检查页面状态或把任务拆成几步。`)
    }
  } finally {
    sending.value = false
  }
}

/** 本轮对话捕获的测试用例数（onGenerateCases 结束后据此反馈） */
let capturedTestCases = 0

/** 用例页签：AI 生成用例（走对话管线；全程在当前页签给足反馈） */
async function onGenerateCases() {
  if (sending.value) return
  // 前置校验：当前页面必须存在组件清单，否则 LLM 无法设计动作
  const manifest = getActiveManifest()
  if (!manifest) {
    ElMessage.warning({
      message: '当前页面没有可操作的组件清单，请先打开具体业务页面（如用户管理、角色管理）后再生成用例',
      duration: 4000,
    })
    return
  }
  capturedTestCases = 0
  ElMessage.info({ message: 'AI 正在基于当前页面组件清单设计测试用例，请稍候…', duration: 3000 })
  try {
    await converse(
      '[TESTGEN] 请基于当前页面组件清单，生成覆盖主要路径与边界校验的测试用例（5 条左右），按约定输出 JSON 数组。',
    )
  } finally {
    if (capturedTestCases > 0) {
      ElMessage.success(`已生成 ${capturedTestCases} 条测试用例，点击用例可查看详情并回放`)
    } else {
      const lastAssistant = [...store.messages].reverse().find((m) => m.role === 'assistant')
      const hint = lastAssistant?.content?.slice(0, 60) ?? ''
      ElMessage.warning({
        message: hint ? `未能生成用例：${hint}…（详情见「对话」页签）` : '未能生成用例，请到「对话」页签查看原因',
        duration: 5000,
      })
    }
  }
}

/** 从 LLM 回复中提取用例 JSON 数组（容错：```json 围栏 / 前后说明文字 / {cases:[...]} 包装） */
function extractCasesJson(text: string): unknown[] | null {
  if (!text || !text.trim()) return null
  let raw = text.trim()
  // 剥 Markdown 代码围栏（```json … ``` 或 ``` … ```）
  const fence = raw.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)
  if (fence && fence[1].trim()) raw = fence[1].trim()
  const parseMaybe = (input: string): unknown[] | null => {
    try {
      const parsed = JSON.parse(input) as unknown
      if (Array.isArray(parsed)) return parsed
      if (parsed && typeof parsed === 'object' && Array.isArray((parsed as { cases?: unknown }).cases)) {
        return (parsed as { cases: unknown[] }).cases
      }
    } catch {
      /* 非法 JSON */
    }
    return null
  }
  const direct = parseMaybe(raw)
  if (direct) return direct
  // 截取首个 '[' 到最后一个 ']'（LLM 可能在 JSON 前后加说明文字）
  const start = raw.indexOf('[')
  const end = raw.lastIndexOf(']')
  if (start >= 0 && end > start) return parseMaybe(raw.slice(start, end + 1))
  return null
}

/** 解析 AI 回复中的测试用例并入库；返回捕获条数 */
function tryCaptureTestCases(text: string): number {
  const list = extractCasesJson(text)
  if (!list) return 0
  let count = 0
  for (const item of list) {
    const c = item as { name?: string; actions?: AiPageAction[] }
    if (c && typeof c.name === 'string' && Array.isArray(c.actions) && c.actions.length > 0) {
      aiTestRecorder.add({ name: c.name, pageRoute: route.path, actions: c.actions })
      count += 1
    }
  }
  if (count > 0) {
    capturedTestCases = count
    // 若从对话页签发起，自动切到用例页签展示结果
    store.panelTab = 'tests'
  }
  return count
}

/** 页面入口跳转 */
function navigate(path: string) {
  if (!path) return
  if (route.path === path) {
    ElMessage.info('已在该页面')
    return
  }
  router.push(path)
}

/** 上下文绑定：当前在表单设计器时，生成的表单自动回填画布 */
function tryApplyForm(result: unknown): boolean {
  const ctx = store.context as { route?: string } | null
  if (!ctx || ctx.route !== 'form-designer') return false
  const schema = (result as { schema?: unknown })?.schema
  if (typeof schema !== 'string') return false

  let rule: unknown[] = []
  try {
    const parsed = JSON.parse(schema)
    rule = Array.isArray(parsed) ? parsed : parsed?.rule ?? []
  } catch {
    rule = []
  }
  if (!Array.isArray(rule) || rule.length === 0) return false

  aiActionBus.emit('applyFormSchema', rule)
  return true
}
</script>

<style scoped>
.ai-orb {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--el-color-primary), var(--brand-bright, #46c9d6));
  box-shadow: 0 6px 16px color-mix(in srgb, var(--el-color-primary) 35%, transparent);
  z-index: 2500;
  transition: transform 0.15s ease;
  touch-action: none;
  user-select: none;
}
/* 拖动中：抓取光标 + 放大 + 取消过渡（跟手） */
.ai-orb-dragging {
  cursor: grabbing;
  transform: scale(1.1);
  transition: none;
  box-shadow: 0 12px 28px color-mix(in srgb, var(--el-color-primary) 45%, transparent);
}
.ai-orb:hover {
  transform: scale(1.06);
}
.ai-orb-active {
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-primary) 22%, transparent), 0 6px 16px color-mix(in srgb, var(--el-color-primary) 35%, transparent);
}
/* 执行页面动作中：呼吸光环提示 */
.ai-orb-executing {
  animation: ai-orb-pulse 1.2s ease-in-out infinite;
}
@keyframes ai-orb-pulse {
  0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--brand-bright, #46c9d6) 55%, transparent); }
  50% { box-shadow: 0 0 0 10px color-mix(in srgb, var(--brand-bright, #46c9d6) 0%, transparent); }
}
.ai-orb-icon {
  width: 26px;
  height: 26px;
}
/* 执行进度徽标 */
.ai-orb-badge {
  position: absolute;
  bottom: -4px;
  right: -6px;
  min-width: 20px;
  height: 16px;
  padding: 0 5px;
  border-radius: 8px;
  background: #101010;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  font-weight: 600;
}
.ai-orb-badge-dot {
  min-width: 16px;
  padding: 0;
}
/* 收起状态下有新回复的未读徽标（区别于执行进度黑底徽标） */
.ai-orb-unread {
  position: absolute;
  top: -4px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--el-color-danger, #f56c6c);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  font-weight: 700;
  text-align: center;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.9);
  animation: ai-unread-pop 0.3s ease;
}
@keyframes ai-unread-pop {
  from {
    transform: scale(0.4);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}
/* 录制中标识（球上红点闪烁） */
.ai-orb-rec {
  position: absolute;
  top: -5px;
  right: -8px;
  padding: 1px 5px;
  border-radius: 8px;
  background: var(--el-color-danger, #f56c6c);
  color: #fff;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.5px;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.9);
  animation: ai-rec-blink 1.2s ease-in-out infinite;
}
@keyframes ai-rec-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

/* 完全悬浮的对话窗体 */
.ai-window {
  position: fixed;
  right: 24px;
  bottom: 88px;
  width: 340px;
  height: min(480px, calc(100vh - 140px));
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.28);
  z-index: 2499;
  overflow: hidden;
}

/* 深色标题栏，突出存在感（四态恒深色系：主色拼黑，白字始终可读）；同时是拖拽把手 */
.ai-window-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: linear-gradient(120deg, color-mix(in srgb, var(--el-color-primary) 62%, #101010), color-mix(in srgb, var(--el-color-primary) 26%, #0a0a0a));
  color: #fff;
  cursor: move;
  user-select: none;
  touch-action: none;
}
/* 停靠模式 header 不可拖动 */
.ai-window-dock .ai-window-header {
  cursor: default;
}

/* 拖动中反馈：阴影加深 + 轻微放大，与静止状态区分 */
.ai-window-dragging {
  box-shadow: 0 24px 56px rgba(0, 0, 0, 0.38);
  transition: none;
}
.ai-window-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-header-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}
.ai-header-avatar :deep(svg) {
  width: 18px;
  height: 18px;
}
.ai-title {
  font-weight: 600;
  font-size: 15px;
  color: #fff;
}
/* 对话/用例页签 */
.ai-tab-group {
  display: flex;
  margin-left: 6px;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 7px;
  padding: 2px;
}
.ai-tab {
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.75);
  font-size: 12px;
  line-height: 1;
  padding: 4px 10px;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.ai-tab.active {
  background: #fff;
  color: var(--el-color-primary);
  font-weight: 600;
}
.ai-window-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ai-icon-btn {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #e5e7eb;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease;
}

/* 把手上的图标按钮保持 pointer 光标，不随 header 变 move */
.ai-window-header .ai-icon-btn {
  cursor: pointer;
}
.ai-icon-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}
/* 钉住激活态（图钉亮起） */
.ai-icon-btn.active {
  background: rgba(255, 255, 255, 0.22);
  color: #6ee7b7;
}
.ai-icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 14px;
  background: var(--el-bg-color-page);
}
.ai-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.ai-msg {
  display: flex;
  margin-bottom: 12px;
}
.ai-msg.user {
  justify-content: flex-end;
}
.ai-msg.assistant {
  align-items: flex-start;
  gap: 8px;
}
.ai-msg-avatar {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ds-industrial-50);
  color: var(--ds-industrial-500);
}
.ai-msg-avatar :deep(svg) {
  width: 17px;
  height: 17px;
}
.ai-msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: calc(100% - 34px);
}
.ai-bubble {
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-msg.user .ai-bubble {
  background: var(--brand);
  color: #fff;
}
.ai-msg.assistant .ai-bubble {
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
}
.ai-bubble-md {
  width: 100%;
  max-width: 100%;
  white-space: normal;
}
/* observation 回传占位行 */
.ai-obs-line {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color-overlay);
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 3px 10px;
}
/* 页面动作执行芯片 */
.ai-action-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.ai-action-chip {
  font-size: 11px;
  line-height: 1;
  padding: 4px 7px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--brand-bright, #46c9d6) 12%, transparent);
  color: var(--el-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  cursor: default;
}
.ai-action-chip.fail {
  background: color-mix(in srgb, var(--el-color-danger) 12%, transparent);
  color: var(--el-color-danger);
}
/* 执行状态行 */
.ai-exec-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
  padding: 6px 10px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--brand-bright, #46c9d6) 10%, transparent);
}
.ai-exec-op {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--el-text-color-secondary);
}
.ai-form-card {
  margin-top: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--brand-bright, #46c9d6) 13%, transparent);
  color: var(--el-color-primary);
  font-size: 12px;
}
.ai-nav-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ai-nav-tag {
  margin-top: 6px;
  cursor: pointer;
}
.ai-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 14px;
  border-top: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color-overlay);
}
.ai-input .el-textarea {
  flex: 1;
}
.ai-send-btn {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  padding: 0;
  border-radius: 8px;
}
</style>

<style>
/* ===== 全局样式：停靠模式 + 目标高亮闪烁（非 scoped，作用于 documentElement/body） ===== */

/* 停靠模式：窗体占右侧整栏，页面内容让位（不遮挡） */
html.ai-dock-active body {
  padding-right: 380px;
}
html.ai-dock-active .ai-window-dock {
  right: 0;
  top: 0;
  bottom: 0;
  left: auto;
  width: 380px;
  height: 100vh;
  border-radius: 0;
  border-top: none;
  border-right: none;
  border-bottom: none;
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.12);
}
@media (max-width: 640px) {
  html.ai-dock-active body {
    padding-right: 0;
  }
  html.ai-dock-active .ai-window-dock {
    width: 100vw;
    box-shadow: none;
  }
}

/* AI 操作目标高亮：轮廓脉冲闪烁 3 次 */
@keyframes ai-flash-pulse {
  0%, 100% { outline-color: transparent; }
  50% { outline-color: var(--brand-bright, #46c9d6); }
}
.ai-flash-target {
  outline: 3px solid transparent !important;
  outline-offset: 2px;
  border-radius: 4px;
  animation: ai-flash-pulse 0.5s ease-in-out 3;
  z-index: 1;
}
</style>
