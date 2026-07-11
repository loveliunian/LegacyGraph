/**
 * L-20: 版本标签格式化工具。
 * 统一版本下拉框的显示格式，避免 versionNo/branchName 为 undefined 时显示 "undefined - undefined"。
 */

interface VersionLike {
  id: string
  versionNo?: string
  versionNumber?: string
  branchName?: string
  createdAt?: string
  [key: string]: unknown
}

/**
 * 格式化版本标签：优先用 versionNumber，其次 versionNo，都没有时回退到 id 前 8 位。
 * @param v 版本对象
 * @returns 格式化后的标签字符串，如 "v23 (abc12345)" 或 "v23 - main"
 */
export function formatVersionLabel(v: VersionLike | null | undefined): string {
  if (!v) return ''
  const versionLabel = v.versionNumber || v.versionNo || (v.id ? v.id.slice(0, 8) : '?')
  const branch = v.branchName || ''
  return branch ? `${versionLabel} - ${branch}` : versionLabel
}
