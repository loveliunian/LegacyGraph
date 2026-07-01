/**
 * 数据字典工具
 *
 * 从后端加载字典项映射（value → label），提供统一的标签查询函数。
 * 后端数据模型：SysDict（字典类型） → SysDictItem（字典项：itemValue → itemLabel）
 * 后端 API：GET /lg/system/dicts/code/{dictCode}/map → { value: label }
 *
 * 使用方式：
 *   import { dictLabel, dictMap, preloadDicts } from '@/utils/dict'
 *   await preloadDicts(['repo_type', 'scan_type', 'scan_status'])
 *   const label = dictLabel('repo_type', 'FULLSTACK')  // → '全栈'
 */

import { get } from '@/utils/request'

/** 字典编码 → 值→标签映射 */
const cache = new Map<string, Record<string, string>>()

/** 正在加载中的请求 Promise，防止重复请求 */
const pending = new Map<string, Promise<Record<string, string>>>()

/**
 * 加载单个字典的映射表
 */
export async function loadDictMap(dictCode: string): Promise<Record<string, string>> {
  // 命中缓存
  if (cache.has(dictCode)) {
    return cache.get(dictCode)!
  }

  // 正在加载中，复用同一个 Promise
  if (pending.has(dictCode)) {
    return pending.get(dictCode)!
  }

  const promise = get<Record<string, string>>(
    `/lg/system/dicts/code/${encodeURIComponent(dictCode)}/map`
  )
    .then((map) => {
      const data = map || {}
      cache.set(dictCode, data)
      return data
    })
    .catch((err) => {
      console.warn(`[dict] 加载字典 "${dictCode}" 失败:`, err)
      const fallback: Record<string, string> = {}
      cache.set(dictCode, fallback)
      return fallback
    })
    .finally(() => {
      pending.delete(dictCode)
    })

  pending.set(dictCode, promise)
  return promise
}

/**
 * 预加载多个字典
 */
export async function preloadDicts(dictCodes: string[]): Promise<void> {
  await Promise.all(dictCodes.map(loadDictMap))
}

/**
 * 获取字典映射表（同步，需先 loadDictMap 或 preloadDicts）
 */
export function dictMap(dictCode: string): Record<string, string> {
  return cache.get(dictCode) || {}
}

/**
 * 根据字典值和编码获取标签文本（同步）
 */
export function dictLabel(dictCode: string, value: string): string {
  const map = cache.get(dictCode)
  if (!map) return value || '-'
  return map[value] || value || '-'
}

/**
 * 清空缓存（字典数据更新后调用）
 */
export function clearDictCache(): void {
  cache.clear()
  pending.clear()
}
