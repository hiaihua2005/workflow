/**
 * AI 用例录制/回放（自动化测试扩展）。
 *
 * - 录制：AI 助手执行的每个 page_action 动作（含参数与结果）按序写入当前录制会话；
 * - 用例：{name, pageRoute, actions[]} 存 localStorage，可导出/导入 JSON 共享；
 * - 回放：调 executeAiActions（replay 模式不重录）顺序执行 + expect 断言，
 *   输出逐步通过/失败报告。
 */
import type { AiActionResult, AiPageAction } from '@/types/aiPageAction'

export interface AiTestCase {
  id: string
  name: string
  /** 录制时所在页面路由（回放前可提示导航） */
  pageRoute: string
  createdAt: number
  actions: AiPageAction[]
}

export interface AiTestReport {
  caseName: string
  startedAt: number
  durationMs: number
  steps: { index: number; op: string; target?: string; ok: boolean; error?: string; data?: unknown }[]
  passed: number
  failed: number
  allPassed: boolean
}

const STORAGE_KEY = 'ai_test_cases'
const RECORDING_KEY = 'ai_test_recording'

function loadCases(): AiTestCase[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') as AiTestCase[]
  } catch {
    return []
  }
}

function saveCases(cases: AiTestCase[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(cases))
}

function nextId(): string {
  return `tc_${Date.now()}_${Math.floor(Math.random() * 1000)}`
}

class AiTestRecorder {
  private recording: AiTestCase | null = null

  constructor() {
    try {
      const raw = localStorage.getItem(RECORDING_KEY)
      if (raw) this.recording = JSON.parse(raw) as AiTestCase
    } catch {
      /* ignore */
    }
  }

  isRecording(): boolean {
    return this.recording !== null
  }

  recordingName(): string {
    return this.recording?.name ?? ''
  }

  /** 开始录制（同一时间仅一个会话） */
  start(name: string, pageRoute: string): void {
    this.recording = { id: nextId(), name: name || `用例 ${new Date().toLocaleString()}`, pageRoute, createdAt: Date.now(), actions: [] }
    localStorage.setItem(RECORDING_KEY, JSON.stringify(this.recording))
  }

  /** 停止录制并保存用例；返回用例（未录制过返回 null） */
  stop(): AiTestCase | null {
    const done = this.recording
    this.recording = null
    localStorage.removeItem(RECORDING_KEY)
    if (done && done.actions.length > 0) {
      const cases = loadCases()
      cases.unshift(done)
      saveCases(cases.slice(0, 50))
      return done
    }
    return null
  }

  /** 丢弃当前录制（不保存） */
  discard(): void {
    this.recording = null
    localStorage.removeItem(RECORDING_KEY)
  }

  /** 录制钩子（执行器调用；replay/expect 结果失败的动作也记录，便于回放断言） */
  recordAction(action: AiPageAction, _result: AiActionResult): void {
    if (!this.recording) return
    // get-state 属探查类动作，录制意义小，跳过
    if (action.op === 'get-state') return
    this.recording.actions.push({ ...action })
    localStorage.setItem(RECORDING_KEY, JSON.stringify(this.recording))
  }

  // ==================== 用例管理 ====================

  list(): AiTestCase[] {
    return loadCases()
  }

  get(id: string): AiTestCase | undefined {
    return loadCases().find((c) => c.id === id)
  }

  add(testCase: Omit<AiTestCase, 'id' | 'createdAt'>): AiTestCase {
    const full: AiTestCase = { ...testCase, id: nextId(), createdAt: Date.now() }
    const cases = loadCases()
    cases.unshift(full)
    saveCases(cases.slice(0, 50))
    return full
  }

  remove(id: string): void {
    saveCases(loadCases().filter((c) => c.id !== id))
  }

  rename(id: string, name: string): void {
    const cases = loadCases()
    const target = cases.find((c) => c.id === id)
    if (target) {
      target.name = name
      saveCases(cases)
    }
  }

  /** 导出全部用例为 JSON 字符串 */
  exportAll(): string {
    return JSON.stringify({ version: 1, exportedAt: Date.now(), cases: loadCases() }, null, 2)
  }

  /** 导入用例 JSON（追加）；返回导入数量 */
  importAll(json: string): number {
    const parsed = JSON.parse(json) as { cases?: AiTestCase[] } | AiTestCase[]
    const incoming = Array.isArray(parsed) ? parsed : parsed?.cases ?? []
    if (!Array.isArray(incoming)) throw new Error('格式错误：需要 {cases:[...]} 或用例数组')
    const existing = loadCases()
    let count = 0
    for (const c of incoming) {
      if (!c || !Array.isArray(c.actions)) continue
      existing.unshift({ ...c, id: nextId(), createdAt: c.createdAt ?? Date.now() })
      count += 1
    }
    saveCases(existing.slice(0, 100))
    return count
  }

  // ==================== 回放 ====================

  /**
   * 回放用例：runner 由调用方注入（aiPageExecutor.executeAiActions），避免循环依赖。
   * expect 断言动作由执行器求值，ok=false 计入失败。
   */
  async replay(
    testCase: AiTestCase,
    runner: (actions: AiPageAction[]) => Promise<AiActionResult[]>,
  ): Promise<AiTestReport> {
    const startedAt = Date.now()
    let steps: AiTestReport['steps'] = []
    try {
      const results = await runner(testCase.actions)
      steps = results.map((r) => ({ index: r.index, op: r.op, target: r.target, ok: r.ok, error: r.error, data: r.data }))
    } catch (e) {
      steps = [{ index: 0, op: 'replay', ok: false, error: (e as Error)?.message || '回放异常' }]
    }
    const passed = steps.filter((s) => s.ok).length
    const failed = steps.length - passed
    return {
      caseName: testCase.name,
      startedAt,
      durationMs: Date.now() - startedAt,
      steps,
      passed,
      failed,
      allPassed: failed === 0 && steps.length > 0,
    }
  }
}

export const aiTestRecorder = new AiTestRecorder()
