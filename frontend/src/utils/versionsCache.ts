/**
 * 扫描版本列表共享缓存
 * 同一项目多个图谱页面复用，避免重复请求 /scan-versions
 */
import { get } from '@/utils/request'
import type { Ref } from 'vue'

export interface ScanVersion {
  id: string
  createdAt: string
  nodeCount: number
  edgeCount: number
  [key: string]: unknown
}

const TTL = 5 * 60 * 1000 // 5 分钟

let cachedVersions: ScanVersion[] = []
let cachedProjectId = ''
let cacheTime = 0

/**
 * 加载扫描版本列表（带缓存）
 * @param projectId 项目 ID
 * @param versionsRef 可选的 ref，直接赋值
 */
export async function loadScanVersions(
  projectId: string,
  versionsRef?: Ref<ScanVersion[]>
): Promise<ScanVersion[]> {
  if (!projectId) return []

  // 同项目 + 未过期 → 直接用缓存
  if (cachedProjectId === projectId && Date.now() - cacheTime < TTL) {
    if (versionsRef) versionsRef.value = cachedVersions
    return cachedVersions
  }

  try {
    const data: unknown = await get(`/lg/projects/${projectId}/scan-versions`)
    const list = Array.isArray(data) ? data : (data as { list?: ScanVersion[] }).list || []
    cachedVersions = list
    cachedProjectId = projectId
    cacheTime = Date.now()
    if (versionsRef) versionsRef.value = cachedVersions
    return cachedVersions
  } catch (error) {
    console.error('加载版本列表失败', error)
    return []
  }
}

/** 清除缓存（创建/删除扫描版本后调用） */
export function clearVersionsCache(projectId?: string) {
  if (projectId) {
    if (cachedProjectId === projectId) {
      cachedVersions = []
      cachedProjectId = ''
      cacheTime = 0
    }
  } else {
    cachedVersions = []
    cachedProjectId = ''
    cacheTime = 0
  }
}
