import http from '@/utils/http'
import type { R } from '@/types/common'

/** 模块管理 API —— 对齐后端 ModuleController */

/** 可打包元素类型 */
export type ModuleElementType = 'FORM' | 'PAGE' | 'DATA_SOURCE' | 'LOGIC_FLOW' | 'WORKFLOW'

export const ELEMENT_TYPE_LABELS: Record<ModuleElementType, string> = {
  FORM: '表单',
  PAGE: '页面',
  DATA_SOURCE: '数据源',
  LOGIC_FLOW: '逻辑流',
  WORKFLOW: '工作流'
}

/** 闭包预览节点 */
export interface AnalyzeNode {
  refKey: string
  name: string
  dependsOn: string[]
  externalDeps: string[]
  /** 用户手动勾选 */
  selected: boolean
  /** 依赖闭包自动补选 */
  autoAdded: boolean
}

export interface AnalyzeResult {
  nodes: AnalyzeNode[]
  /** 图中不存在的引用（如依赖了未发布元素） */
  missing: string[]
}

/** 预检单元素 */
export interface ElementInspect {
  type: ModuleElementType
  key: string
  name: string
  exists: boolean
  targetVersion: string | null
  missingDeps: string[]
  warnings: string[]
}

export interface InspectResult {
  moduleName: string
  moduleDescription: string | null
  sourceTenant: string
  exportedAt: string
  elements: ElementInspect[]
  globalWarnings: string[]
}

/** 导入单元素结果 */
export interface ElementResult {
  type: ModuleElementType
  key: string
  status: 'IMPORTED' | 'SKIPPED' | 'FAILED'
  message: string
}

export interface ImportResult {
  results: ElementResult[]
  warnings: string[]
}

export const moduleApi = {
  /** 依赖闭包分析（导出预览） */
  analyze(elements: { type: string; key: string }[]): Promise<R<AnalyzeResult>> {
    return http.post('/v1/modules/analyze', { elements })
  },

  /**
   * 导出模块包（zip Blob，调用方触发下载）。
   * includeClosure：是否由后端做依赖闭包兜底补全；前端已展示闭包并允许用户裁剪选择集时传 false。
   * http 拦截器对 blob 响应整体放行（返回值即 Blob 本身）。
   */
  async exportModule(
    name: string,
    description: string,
    elements: { type: string; key: string }[],
    includeClosure?: boolean
  ): Promise<Blob> {
    const resp = (await http.post('/v1/modules/export', { name, description, elements, includeClosure }, {
      responseType: 'blob',
      timeout: 120000
    })) as unknown as Blob
    // 失败时后端返回 R JSON（HTTP 200），blob 通道需显式识别
    if (resp.type === 'application/json') {
      const body = JSON.parse(await resp.text())
      throw new Error(body?.msg || '导出失败')
    }
    return resp
  },

  /** 导入预检（上传 zip，不落库） */
  inspect(file: File): Promise<R<InspectResult>> {
    const form = new FormData()
    form.append('file', file)
    return http.post('/v1/modules/inspect', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  /** 导入执行（逐元素策略） */
  importModule(file: File, actions: { type: string; key: string; strategy: string }[]): Promise<R<ImportResult>> {
    const form = new FormData()
    form.append('file', file)
    form.append('actions', JSON.stringify(actions))
    return http.post('/v1/modules/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
