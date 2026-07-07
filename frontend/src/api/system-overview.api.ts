import { downloadFile, get, post } from '@/utils/request'

/** 单业务域四层映射 */
export interface LayerMapping {
  businessDomain: string
  capability: string
  feature: string
  controller: string
  apiPath: string
  codeModule: string
  dataTables: string[]
  edgeType: string
}

/** 系统关系总览 */
export interface SystemOverview {
  projectId: string
  versionId: string
  mappings: LayerMapping[]
  corePaths: string[]
  totalDomains: number
}

/** 事实底座导入结果 */
export interface IngestResult {
  projectId: string
  versionId: string
  vectorCount: number
  claimCount: number
  faqCount: number
  skipped: number
}

/** 获取系统关系总览 */
export function getSystemOverview(projectId: string, versionId?: string) {
  return get<SystemOverview>(`/lg/projects/${projectId}/system-overview`, versionId ? { versionId } : undefined)
}

/** 按业务域查询映射（模糊匹配） */
export function getDomainMapping(projectId: string, domainId: string, versionId?: string) {
  return get<LayerMapping[]>(`/lg/projects/${projectId}/system-overview/domains/${domainId}`, versionId ? { versionId } : undefined)
}

/** 获取核心贯穿链路 */
export function getCorePaths(projectId: string, versionId?: string, from?: string, to?: string) {
  const params = {
    ...(versionId ? { versionId } : {}),
    ...(from ? { from } : {}),
    ...(to ? { to } : {}),
  }
  return get<string[]>(
    `/lg/projects/${projectId}/system-overview/paths`,
    Object.keys(params).length > 0 ? params : undefined
  )
}

/** 一键导入内置事实底座（12 业务域映射 + 核心 FAQ） */
export function ingestBuiltins(projectId = 'self', versionId?: string) {
  return post<IngestResult>('/lg/system-overview/ingest-builtins', {}, { params: { projectId, ...(versionId ? { versionId } : {}) } })
}

/** 导出系统关系总览报告 */
export function exportSystemOverviewReport(projectId: string, versionId = 'default', format: 'MD' | 'PDF' | 'EXCEL' = 'MD') {
  return downloadFile(`/reports/system-overview/${projectId}/${versionId}`, { format })
}
