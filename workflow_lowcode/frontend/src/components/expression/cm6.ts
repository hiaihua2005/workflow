/**
 * 表达式/脚本编辑器扩展工厂（CodeMirror 6 精简集成，全项目共用）。
 *
 * 形态（syntax × lang 组合）：
 * - mustache：{{var}} 占位符模板（逻辑流值表达式）——占位符整体高亮 + 占位符内变量补全
 * - uel：${var} UEL 表达式（BPMN 审批人/服务任务/网关条件）——同 mustache 语义
 * - lang=groovy：Groovy 脚本/条件（JS 语法近似高亮），标识符处即输即补
 * - lang=sql：SQL 脚本（轻量关键字高亮）+ {{var}} 模板占位符 + 关键字/变量补全
 *
 * 体积口径：state/view/language/autocomplete/commands + lang-javascript ≈ 480KB min / 160KB gzip，
 * 仅进逻辑流设计器与 BPMN 设计器两个懒加载 chunk，不影响主包。
 */
import {
  autocompletion,
  closeCompletion,
  completionKeymap,
  startCompletion,
  type Completion,
  type CompletionContext,
  type CompletionResult,
  type CompletionSource,
} from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { javascript } from '@codemirror/lang-javascript'
import { defaultHighlightStyle, HighlightStyle, StreamLanguage, syntaxHighlighting } from '@codemirror/language'
import { Compartment, EditorState, type Extension } from '@codemirror/state'
import {
  EditorView,
  drawSelection,
  highlightSpecialChars,
  keymap,
  type KeyBinding,
  placeholder as cmPlaceholder,
  tooltips,
} from '@codemirror/view'
import { tags as t } from '@lezer/highlight'
import type { FlowVarItem } from '@/views/logicflow/utils/flowVars'

export type ExpressionSyntax = 'mustache' | 'uel'
export type ExpressionLang = 'none' | 'groovy' | 'sql'
export type ExpressionMode = 'placeholder' | 'bare'

/** 分组缺省展示名（groupLabel 可覆盖，空串隐藏） */
const GROUP_LABEL: Record<FlowVarItem['group'], string> = {
  input: '入参',
  loop: '循环',
  upstream: '上游产出',
  form: '表单',
}

/** 变量条目 → 补全选项（树拍平：根 + 全路径子字段，label 即插入文本） */
export function flattenVarOptions(items: FlowVarItem[]): Completion[] {
  const out: Completion[] = []
  const seen = new Set<string>()
  const walk = (item: FlowVarItem) => {
    if (item?.name && !seen.has(item.name)) {
      seen.add(item.name)
      const gl = item.groupLabel !== undefined ? item.groupLabel : GROUP_LABEL[item.group]
      const detail = gl && item.detail ? `${gl} · ${item.detail}` : gl || item.detail || ''
      out.push({
        label: item.name,
        detail,
        type: item.children?.length ? 'namespace' : 'variable',
        boost: 5,
      })
    }
    for (const c of item.children || []) walk(c)
  }
  for (const it of items || []) walk(it)
  return out
}

export interface ExpressionSourceConfig {
  items: FlowVarItem[]
  mode: ExpressionMode
  syntax: ExpressionSyntax
}

/** 片段补全条目：label 为展示/匹配文本，apply 为插入文本或自定义插入函数（可定位光标） */
export interface SnippetOption {
  label: string
  detail?: string
  type?: string
  apply: Completion['apply']
}

/**
 * 通用片段/函数补全源（label ≠ 插入文本的场景，如公式编辑器：
 * 键入 pri → 选 price → 插 ${price}；键入 SU → 选 SUM → 插 SUM() 且光标落括号内）。
 * match 定义触发前缀；与变量补全源共存（互不干扰，各自命中各自区间）。
 */
export function makeSnippetCompletionSource(options: SnippetOption[], match: RegExp = /[\w$]*$/): CompletionSource {
  if (!options.length) return () => null
  const opts: Completion[] = options.map((o) => ({
    label: o.label,
    detail: o.detail,
    type: o.type,
    apply: o.apply,
    boost: 1,
  }))
  return (ctx: CompletionContext): CompletionResult | null => {
    const w = ctx.matchBefore(match)
    if (!w || (w.from === w.to && !ctx.explicit)) return null
    return { from: w.from, options: opts, validFor: /^[\w$]*$/ }
  }
}

const PATH_WORD = /^[\w.[\]]*$/

/** # 呼出字段条目：key 为物理列名（插入引用用），label/type 为展示标注（中文名/列类型） */
export interface HashFieldOption {
  key: string
  label?: string
  type?: string
}

/** # 选中插入形态：placeholder = {{formData.字段}}（WHERE 值等）；bare = 裸列名 + 自动补 = 前缀（SET 值表达式） */
export type HashInsertMode = 'placeholder' | 'bare'

/**
 * 「# 呼出字段补全」源：值表达式中键入 # 列出所选目标表全部字段，选中插入可运行引用。
 * - placeholder（默认）：插 {{formData.字段}}（uel 为 ${formData.字段}），插入即可运行（引擎点路径解析）；
 *   占位符内（{{…# 至光标）从占位符内容起点整体替换（# 前残缺路径一并清掉），
 *   闭合标记自动补齐语义与变量补全一致（后方已有 }} 只补路径）
 * - bare（值表达式场景：UPDATE SET / INSERT 值 / DELETE·QUERY 条件值）：插裸列名（与 = 表达式列引用
 *   语法一致，不包 {{}}，也不插 "=" 前缀——编辑器显示层永不出现 =，存储层由 VarInput 统一补 "= "）；
 *   值为空 → 直接插列名；存量旧值（非空且无运算符/占位符的“单词”字面量，与表达式不兼容）→
 *   整体重写为列名（插入即可运行）；显示层以 = 开头（存量 == 转义字面量）上下文不弹
 * - filter:false + 源内自过滤（key/中文名大小写不敏感子串）：中文过滤不被 label 模糊匹配限制，
 *   同时避免 CM 渲染层以「#…」模式串误滤无 # 前缀的 label
 * - fields 支持 getter 闭包：字段元数据（information_schema/列中文名）异步就绪后无需重建编辑器扩展
 */
export function makeHashFieldCompletionSource(
  fields: HashFieldOption[] | (() => HashFieldOption[]),
  syntax: ExpressionSyntax = 'mustache',
  insert: HashInsertMode = 'placeholder'
): CompletionSource {
  const list = (): HashFieldOption[] => (Array.isArray(fields) ? fields : fields())
  return (ctx: CompletionContext): CompletionResult | null => {
    const all = list()
    if (!all.length) return null
    const hash = ctx.matchBefore(/#[\w\u4e00-\u9fa5]*$/)
    if (!hash) return null
    if (insert === 'bare') {
      const doc = ctx.state.doc.toString()
      const trimmed = doc.trim()
      // 显示层以 = 开头（存量 == 转义字面量 / 手输 =）：字面量上下文，# 呼出无意义
      if (trimmed.startsWith('=')) return null
      // 存量旧值（非空“单词”：无空白/占位符/运算符/括号，如 0、待审核、旧列名）与表达式不兼容，
      // 选中字段时整体重写为列名；正常表达式（含运算符/占位符）则在 # 处追加
      const rewriteAll = trimmed.length > 0 && !/[\s{{(=+\-*/%',]/.test(trimmed)
      // 源内自过滤（key/中文名大小写不敏感子串）：# 后续输入即滤（与 placeholder 分支同款）
      const q = ctx.state.sliceDoc(hash.from + 1, ctx.pos).trim().toLowerCase()
      const hit = q
        ? all.filter((f) => f.key.toLowerCase().includes(q) || (f.label ?? '').toLowerCase().includes(q))
        : all
      if (!hit.length) return null
      const buildOption = (f: HashFieldOption): Completion => {
        const suffix = `(${f.key})`
        const cn =
          f.label && f.label !== f.key && f.label.endsWith(suffix)
            ? f.label.slice(0, f.label.length - suffix.length)
            : ''
        return {
          label: f.key,
          detail: [cn, f.type].filter(Boolean).join(' · ') || undefined,
          type: 'property',
          apply: f.key,
        }
      }
      const options = hit.map(buildOption)
      return { from: rewriteAll ? 0 : hash.from, to: rewriteAll ? doc.length : ctx.pos, options, filter: false }
    }
    const open = ctx.matchBefore(syntax === 'uel' ? /\$\{[^}]*$/ : /\{\{[^{}]*$/)
    const inOpen = Boolean(open)
    const CLOSE = syntax === 'uel' ? '}' : '}}'
    const from = inOpen ? open!.from + 2 : hash.from
    // 过滤串取 # 之后的输入（占位符内 # 前残缺内容将被整体替换，不参与过滤）；空串列全部
    const q = ctx.state.sliceDoc(hash.from + 1, ctx.pos).trim().toLowerCase()
    const hit = q
      ? all.filter((f) => f.key.toLowerCase().includes(q) || (f.label ?? '').toLowerCase().includes(q))
      : all
    if (!hit.length) return null
    const after = ctx.state.sliceDoc(ctx.pos)
    const closedAhead = syntax === 'uel' ? /^\s*}/.test(after) : /^\s*}}/.test(after)
    const options: Completion[] = hit.map((f) => {
      const path = `formData.${f.key}`
      const suffix = `(${f.key})`
      const cn = f.label && f.label !== f.key && f.label.endsWith(suffix) ? f.label.slice(0, f.label.length - suffix.length) : ''
      const apply = inOpen ? (closedAhead ? path : path + CLOSE) : syntax === 'uel' ? `\${${path}}` : `{{${path}}}`
      return {
        label: f.key,
        detail: [cn, f.type].filter(Boolean).join(' · ') || undefined,
        type: 'property',
        apply,
      }
    })
    return { from, options, filter: false }
  }
}

/** 占位符包裹插入（占位符外 Ctrl+Space 场景） */
function wrapToken(syntax: ExpressionSyntax, path: string): string {
  return syntax === 'uel' ? `\${${path}}` : `{{${path}}}`
}

/** 占位符起始标记 */
const OPEN: Record<ExpressionSyntax, string> = { mustache: '{{', uel: '${' }

/** 变量补全源：
 * - mustache/uel：仅占位符内自动弹出（输入 {{ / ${ 即列全部变量），占位符外 Ctrl+Space 插完整占位符；
 *   光标后方无闭合标记时自动补 }}（插入即可用，与变量选择器同语义）
 * - bare：标识符处即输即补（变量名字段/Groovy 脚本）
 */
export function makeVarCompletionSource(cfg: ExpressionSourceConfig): CompletionSource {
  const options = flattenVarOptions(cfg.items)
  if (!options.length) return () => null
  const CLOSE = cfg.syntax === 'uel' ? '}' : '}}'
  return (ctx: CompletionContext): CompletionResult | null => {
    // 1) 占位符内（{{… / ${… 至光标）
    const brace = ctx.matchBefore(/\{\{[^{}]*$|\$\{[^}]*$/)
    if (brace) {
      const from = brace.from + 2
      // 光标后方已有闭合标记（编辑既有占位符）→ 只插路径；否则自动闭合
      const after = ctx.state.sliceDoc(ctx.pos)
      const closedAhead = cfg.syntax === 'uel' ? /^\s*}/.test(after) : /^\s*}}/.test(after)
      const finalOptions = closedAhead ? options : options.map((o) => ({ ...o, apply: o.label + CLOSE }))
      return { from, options: finalOptions, validFor: PATH_WORD }
    }
    // 2) bare 模式：光标前标识符路径片段（空匹配仅显式触发时给全列表）
    if (cfg.mode === 'bare') {
      const w = ctx.matchBefore(/[\w.[\]]*$/)
      if (w && (w.from < w.to || ctx.explicit)) {
        return { from: w.from, options, validFor: PATH_WORD }
      }
      return null
    }
    // 3) 占位符外：仅显式 Ctrl+Space，插完整占位符
    if (ctx.explicit) {
      return {
        from: ctx.pos,
        options: options.map((o) => ({ ...o, apply: wrapToken(cfg.syntax, o.label) })),
        validFor: PATH_WORD,
      }
    }
    return null
  }
}

/** SQL 关键字表（补全 + 高亮共用） */
export const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'AND', 'OR',
  'NOT', 'NULL', 'IS', 'IN', 'LIKE', 'BETWEEN', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'ON',
  'GROUP', 'BY', 'ORDER', 'HAVING', 'LIMIT', 'OFFSET', 'AS', 'DISTINCT', 'COUNT', 'SUM', 'AVG',
  'MIN', 'MAX', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'ASC', 'DESC', 'EXISTS', 'UNION', 'ALL',
  'CREATE', 'TABLE', 'INDEX', 'DROP', 'ALTER', 'COMMIT', 'ROLLBACK', 'WITH', 'RETURNING',
]

/** SQL 关键字补全（不在 {{ 占位符内时生效） */
export function makeKeywordCompletionSource(keywords: string[]): CompletionSource {
  const options: Completion[] = keywords.map((k) => ({ label: k, type: 'keyword' }))
  return (ctx: CompletionContext): CompletionResult | null => {
    if (ctx.matchBefore(/\{\{[^{}]*$/)) return null
    const w = ctx.matchBefore(/[\w$]*$/)
    if (!w || (w.from === w.to && !ctx.explicit)) return null
    return { from: w.from, options, validFor: /^[\w$]*$/ }
  }
}

// ===== 高亮 =====

/** 表达式主题色（占位符紫、SQL 关键字蓝等；其余 tag 落回 defaultHighlightStyle） */
export const exprHighlightStyle = HighlightStyle.define([
  { tag: t.meta, color: '#8250df', fontWeight: '600' },
  { tag: t.keyword, color: '#0550ae', fontWeight: '600' },
  { tag: t.operatorKeyword, color: '#0550ae' },
  { tag: t.lineComment, color: '#6e7781', fontStyle: 'italic' },
  { tag: t.string, color: '#0a7d32' },
  { tag: t.number, color: '#b35900' },
])

const MONO_FONT = "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace"

/** {{var}} / ${var} 模板占位符分词（整段一个 meta token） */
function templateLang(syntax: ExpressionSyntax) {
  const open = OPEN[syntax]
  const close = syntax === 'uel' ? '}' : '}}'
  return StreamLanguage.define({
    name: syntax === 'uel' ? 'uel-template' : 'mustache-template',
    tokenTable: { templateVar: t.meta },
    token(stream) {
      if (stream.match(open)) {
        while (!stream.eol() && !stream.match(close)) stream.next()
        stream.match(close)
        return 'templateVar'
      }
      stream.next()
      return null
    },
  })
}

/** SQL + {{var}} 模板混合分词（SQL_SCRIPT 脚本：SQL 关键字高亮 + 占位符插值高亮） */
const sqlTemplateLang = StreamLanguage.define({
  name: 'sql-template',
  tokenTable: {
    templateVar: t.meta,
    keyword: t.keyword,
    string: t.string,
    number: t.number,
    lineComment: t.lineComment,
    operator: t.operatorKeyword,
  },
  token(stream) {
    if (stream.match('{{')) {
      while (!stream.eol() && !stream.match('}}')) stream.next()
      stream.match('}}')
      return 'templateVar'
    }
    if (stream.match(/^--[^\n]*/)) return 'lineComment'
    if (stream.match(/^'(?:[^']|'')*'/)) return 'string'
    if (stream.match(/^[0-9]+(\.[0-9]+)?/)) return 'number'
    if (stream.match(/^[A-Za-z_][\w$]*/)) {
      return SQL_KEYWORDS.includes(stream.current().toUpperCase()) ? 'keyword' : null
    }
    if (stream.match(/^[<>!=+\-*/%(),.;|]/)) return 'operator'
    stream.next()
    return null
  },
})

export interface ExpressionExtensionConfig {
  mode: ExpressionMode
  syntax: ExpressionSyntax
  lang: ExpressionLang
  items: FlowVarItem[]
  placeholder?: string
  disabled?: boolean
  multiline?: boolean
  /** 单行形态（Enter 不换行、粘贴去换行） */
  singleLine?: boolean
  small?: boolean
  rows?: number
  maxRows?: number
  /** 右侧留白（选择器/清空锚位） */
  reserveRight?: number
  /** 追加补全源（公式函数等自定义场景，与变量补全源共存） */
  extraSources?: CompletionSource[]
}

/** 行高与容器主题（尺寸随 props 重建，经 Compartment 热替换） */
export function buildTheme(cfg: ExpressionExtensionConfig): Extension {
  const pad = cfg.reserveRight ?? 0
  const contentPadRight = pad ? `${pad}px` : '8px'
  const sizeBlock: Record<string, string> = cfg.multiline
    ? {
        minHeight: `${(cfg.rows ?? 3) * 1.5}em`,
        maxHeight: `${(cfg.maxRows ?? 26) * 1.5}em`,
      }
    : { minHeight: cfg.small ? '24px' : '32px' }
  const contentBlock: Record<string, string> = cfg.multiline
    ? { lineHeight: '1.5', padding: '4px 0 4px 8px' }
    : { lineHeight: cfg.small ? '22px' : '30px', padding: '0 0 0 8px' }
  return EditorView.theme(
    {
      '&': {
        fontSize: '13px',
        border: '1px solid var(--el-border-color)',
        borderRadius: '4px',
        backgroundColor: 'var(--el-fill-color-blank)',
        color: 'var(--el-text-color-regular)',
        transition: 'border-color 0.15s',
        width: '100%',
        overflow: 'hidden',
        ...sizeBlock,
      },
      '&:hover': { borderColor: 'var(--el-border-color-hover)' },
      '&.cm-focused': { outline: 'none', borderColor: 'var(--el-color-primary)' },
      '.cm-scroller': { overflow: 'auto', scrollbarWidth: 'thin' },
      '.cm-content': {
        fontFamily: MONO_FONT,
        paddingRight: contentPadRight,
        caretColor: 'var(--el-color-primary)',
        ...contentBlock,
      },
      '.cm-line': { padding: '0' },
      '.cm-placeholder': { color: 'var(--el-text-color-placeholder)', fontFamily: MONO_FONT },
      '.cm-selectionBackground': {
        backgroundColor: 'color-mix(in srgb, var(--el-color-primary) 14%, transparent) !important',
      },
      '.cm-cursor': { borderLeftColor: 'var(--el-color-primary)' },
      // 补全浮层（挂 body，避免面板 overflow 裁剪）
      '.cm-tooltip.cm-tooltip-autocomplete': {
        border: '1px solid var(--el-border-color-light)',
        backgroundColor: 'var(--el-bg-color-overlay)',
        borderRadius: '8px',
        boxShadow: '0 6px 18px rgba(31, 36, 55, 0.16)',
        overflow: 'hidden',
        fontFamily: MONO_FONT,
        fontSize: '12px',
      },
      '.cm-tooltip-autocomplete > ul': {
        maxHeight: '15em',
        fontFamily: 'inherit',
        margin: '0',
        padding: '0',
      },
      '.cm-tooltip-autocomplete > ul > li': {
        padding: '5px 10px',
        color: 'var(--el-text-color-primary)',
        display: 'flex',
        alignItems: 'baseline',
        gap: '8px',
      },
      '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
        backgroundColor: 'color-mix(in srgb, var(--el-color-primary) 12%, transparent)',
        color: 'var(--el-color-primary)',
      },
      '.cm-completionDetail': {
        color: 'var(--el-text-color-secondary)',
        fontStyle: 'normal',
        fontSize: '11px',
        marginLeft: 'auto',
        paddingLeft: '10px',
      },
      '.cm-completionLabel': { wordBreak: 'break-all' },
    },
    { dark: false }
  )
}

/** 语言层（语法分词/高亮） */
export function buildLang(cfg: ExpressionExtensionConfig): Extension {
  if (cfg.lang === 'groovy') return javascript()
  if (cfg.lang === 'sql') return sqlTemplateLang
  return templateLang(cfg.syntax)
}

/** Compartment 位（组件按 props 变更热替换对应扩展） */
export function assembleCompartments() {
  return {
    lang: new Compartment(),
    autoComplete: new Compartment(),
    placeholder: new Compartment(),
    readOnly: new Compartment(),
    theme: new Compartment(),
  }
}

export type ExpressionCompartments = ReturnType<typeof assembleCompartments>

const PREVENT_ENTER: KeyBinding = { key: 'Enter', run: () => true }

export function buildStaticExtensions(cfg: ExpressionExtensionConfig): Extension[] {
  const exts: Extension[] = [
    highlightSpecialChars(),
    history(),
    drawSelection(),
    syntaxHighlighting(exprHighlightStyle),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    tooltips({ parent: document.body }),
    keymap.of([
      ...completionKeymap,
      ...(cfg.singleLine ? [PREVENT_ENTER] : []),
      ...defaultKeymap,
      ...historyKeymap,
    ]),
  ]
  if (cfg.multiline) exts.push(keymap.of([indentWithTab]))
  return exts
}

/** 组合动态 Compartment 初值（onMounted 与 props watcher 共用） */
export function buildDynamicExtensions(compartments: ExpressionCompartments, cfg: ExpressionExtensionConfig): Extension[] {
  const acSources: CompletionSource[] = [makeVarCompletionSource(cfg)]
  if (cfg.lang === 'sql') acSources.push(makeKeywordCompletionSource(SQL_KEYWORDS))
  if (cfg.extraSources?.length) acSources.push(...cfg.extraSources)
  return [
    compartments.lang.of(buildLang(cfg)),
    compartments.autoComplete.of(
      autocompletion({ override: acSources, icons: false, closeOnBlur: true })
    ),
    compartments.placeholder.of(cmPlaceholder(cfg.placeholder || '')),
    compartments.readOnly.of(
      cfg.disabled ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []
    ),
    compartments.theme.of([buildTheme(cfg), ...(cfg.multiline ? [EditorView.lineWrapping] : [])]),
  ]
}

/** 快捷关闭/打开补全（外部按钮等场景） */
export { closeCompletion, startCompletion }

// bare-insert mode for DATA_UPDATE SET expr (hash pick inserts bare column with auto = prefix)
// hmr bump 1791789092
