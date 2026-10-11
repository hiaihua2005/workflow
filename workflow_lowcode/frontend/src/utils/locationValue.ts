/**
 * 定位组件（LocationPicker）值解析/格式化共享工具。
 *
 * 【值契约 v3】modelValue = string（VARCHAR 直存，全链路标量兼容）：
 * - 命名位置：JSON 复合文本 '{"name":"总部仓库","lat":39.9042,"lng":116.4074}'
 *   —— 名称与坐标同时持久化（v3 缺陷修复：v2 命名后只存名称、坐标丢失，
 *   重新打开弹窗无法回放标记）
 * - 未命名：'lat,lng' 坐标文本（v1 兼容，紧凑兜底）
 * - 旧数据自动识别（只读兼容；v2 已丢失的坐标无法恢复，解析为 name-only）：
 *   'lat,lng' 坐标文本 → 坐标无名称；普通文本 → 名称无坐标
 * - '' / null / undefined → 未设置
 *
 * 【为什么不用 JSON 列】MariaDB 的 JSON 列物理为 longtext + CHECK(json_valid)，
 * 存量列中已有的「纯名称」旧值（非合法 JSON）会让发布时 VARCHAR→JSON 的
 * MODIFY COLUMN 失败（数值→JSON 才无碍，见 BIGINT→JSON 特例）；JSON 复合文本在
 * VARCHAR 列直存无约束风险，列映射仅需 VARCHAR(64)→VARCHAR(255) 同大类加宽。
 */

/** 统一解析结果：name 可为空；lat/lng 为 null 表示该值不含坐标（旧纯名称值） */
export interface ParsedLocation {
  name: string
  lat: number | null
  lng: number | null
}

/** 坐标文本 'lat,lng' → [lat, lng]；支持中英文逗号/分号/空白分隔，非法返回 null */
export function parseCoordText(text: string): [number, number] | null {
  const parts = String(text || '').trim().split(/[,，;\s;]+/).filter(Boolean)
  if (parts.length !== 2) return null
  const a = Number(parts[0])
  const b = Number(parts[1])
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null
  if (a < -90 || a > 90 || b < -180 || b > 180) return null
  return [a, b]
}

/** 数值 → 至多 6 位小数字符串（去尾零：39.9 → '39.9'） */
export function trimCoord(n: number): string {
  return String(parseFloat(Number(n).toFixed(6)))
}

/** 对象形态值容错解析（{name?,lat,lng}；坐标非法返回 null） */
function parseLocationObject(o: Record<string, unknown>): ParsedLocation | null {
  const lat = Number(o.lat)
  const lng = Number(o.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null
  return { name: String(o.name ?? ''), lat, lng }
}

/**
 * 任意历史形态的定位值 → 统一解析。
 * 优先级：对象值 > JSON 复合文本 > 'lat,lng' 坐标文本 > 纯名称（旧值）。
 */
export function parseLocationValue(v: unknown): ParsedLocation | null {
  // 1) 运行时可能直接拿到对象（如流程变量/映射注入）
  if (v !== null && typeof v === 'object') {
    return parseLocationObject(v as Record<string, unknown>)
  }
  const s = String(v ?? '').trim()
  if (!s) return null
  // 2) JSON 复合文本（v3 命名值）
  if (s.startsWith('{')) {
    try {
      const o = JSON.parse(s)
      if (o && typeof o === 'object' && !Array.isArray(o)) {
        const parsed = parseLocationObject(o as Record<string, unknown>)
        if (parsed) return parsed
      }
    } catch {
      // 非法 JSON → 落到坐标/名称解析兜底
    }
  }
  // 3) 坐标文本（v1 / v3 未命名值）
  const coord = parseCoordText(s)
  if (coord) return { name: '', lat: coord[0], lng: coord[1] }
  // 4) 纯名称（v2 命名旧值，坐标已丢失）
  return { name: s, lat: null, lng: null }
}

/** 是否携带有效坐标（可回放标记） */
export function hasCoords(p: ParsedLocation | null): p is ParsedLocation & { lat: number; lng: number } {
  return p !== null && p.lat !== null && p.lng !== null
}

/**
 * 构建 v3 值：命名 → JSON 复合文本（名称+坐标同时保存）；未命名 → 'lat,lng' 紧凑兜底。
 * 坐标统一 trimCoord（至多 6 位小数）控制值长度（VARCHAR(64) 存量表未加宽前尽量不溢出）。
 */
export function buildLocationValue(name: string, lat: number, lng: number): string {
  const latText = trimCoord(lat)
  const lngText = trimCoord(lng)
  const trimmed = String(name || '').trim()
  if (!trimmed) return `${latText},${lngText}`
  return JSON.stringify({ name: trimmed, lat: Number(latText), lng: Number(lngText) })
}

/**
 * 列表/文本场景的可读展示：命名 → 名称；未命名 → 'lat, lng'；解析失败 → 原文透传。
 * 空值返回 ''（调用方按需渲染占位符）。
 */
export function formatLocationText(v: unknown): string {
  const s = String(v ?? '').trim()
  if (!s) return ''
  const parsed = parseLocationValue(s)
  if (!parsed) return s
  if (parsed.name) return parsed.name
  if (hasCoords(parsed)) return `${trimCoord(parsed.lat)}, ${trimCoord(parsed.lng)}`
  return s
}
