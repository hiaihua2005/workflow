/**
 * Vitest 全局 setup：jsdom 环境缺失的浏览器 API stub。
 * CodeMirror 6 的 EditorView 依赖 ResizeObserver 观测编辑器容器尺寸。
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

const g = globalThis as unknown as Record<string, unknown>
if (!g.ResizeObserver) g.ResizeObserver = ResizeObserverStub
