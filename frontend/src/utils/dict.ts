/**
 * 数据字典工具
 *
 * 登录后调用 loadAllDicts() 一次性从后端加载全部字典到内存，
 * 后续 dictLabel() 同步查询，无需任何网络请求。
 *
 * 后端 API：GET /lg/system/dicts/all/maps → { dictCode: { value: label } }
 * 退出登录时调用 clearDictCache() 清空内存。
 *
 * 使用方式：
 *   // 登录后（app/user store 中调用一次）
 *   await loadAllDicts()
 *   // 任意页面同步使用
 *   import { dictLabel } from '@/utils/dict'
 *   const label = dictLabel('repo_type', 'FULLSTACK')  // → '全栈'
 */

import { get } from '@/utils/request'

/** 字典编码 → 值→标签映射 */
let cache: Record<string, Record<string, string>> = {}

/** 是否已加载 */
let loaded = false

/** 正在加载的 Promise (防并发) */
let loadingPromise: Promise<void> | null = null

/**
 * 从后端一次性加载全量字典到内存
 * 建议在登录成功后调用，全局只调用一次
 */
export async function loadAllDicts(): Promise<void> {
  // 已加载，直接返回
  if (loaded) return

  // 正在加载中，复用同一个 Promise
  if (loadingPromise) return loadingPromise

  loadingPromise = get<Record<string, Record<string, string>>>('/lg/system/dicts/all/maps')
    .then((data) => {
      cache = data || {}
      loaded = true
    })
    .catch((err) => {
      console.warn('[dict] 全量字典加载失败:', err)
      cache = {}
    })
    .finally(() => {
      loadingPromise = null
    })

  return loadingPromise
}

/**
 * 根据字典编码和值获取标签文本（同步，零开销）
 */
export function dictLabel(dictCode: string, value: string): string {
  if (!value) return '-'
  const map = cache[dictCode]
  if (!map) return value
  return map[value] || value
}

/**
 * 获取字典映射表（同步）
 */
export function dictMap(dictCode: string): Record<string, string> {
  return cache[dictCode] || {}
}

/**
 * 清空内存缓存（退出登录时调用）
 */
export function clearDictCache(): void {
  cache = {}
  loaded = false
  loadingPromise = null
}

// 保留旧的按需加载 API 以兼容已有调用（实际已全量加载，直接返回空操作）
/** @deprecated 请使用 loadAllDicts() 一次性全量加载 */
export async function preloadDicts(_dictCodes: string[]): Promise<void> {
  // 全量加载已包含所有字典，此函数保留仅为兼容旧代码
  // 旧页面 onMounted 仍会调用，这里直接返回不做额外请求
}
