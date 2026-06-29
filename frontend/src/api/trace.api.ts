import { get, post } from '@/utils/request'

/** 运行时 span 上报项 */
export interface SpanDto {
  traceId: string
  spanId: string
  parentSpanId?: string
  serviceName: string
  operationName: string
  spanKind?: string
  durationMs?: number
  status?: string
  startEpochMs?: number
}

/** 运行时服务拓扑 */
export interface TraceTopology {
  projectId: string
  versionId: string
  totalSpans: number
  totalTraces: number
  services: Array<{ name: string; spanCount: number; errorCount: number; avgDurationMs: number }>
  calls: Array<{ from: string; to: string; callCount: number; errorCount: number }>
}

/** 运行时 span 记录 */
export interface RuntimeTraceRecord {
  id: string
  traceId: string
  serviceName: string
  operationName: string
  durationMs: number
  status: string
  startedAt: string
  createdAt: string
}

/**
 * 运行时链路 API（P2-1）
 * 对接后端 TraceController：上报 span、查询服务拓扑与链路列表
 */
export const traceApi = {
  /** 批量上报 span */
  ingest: (projectId: string, versionId: string, spans: SpanDto[]) => {
    return post<{ ingested: number }>(`/lg/projects/${projectId}/runtime/traces`, { versionId, spans })
  },

  /** 获取运行时服务拓扑 */
  getTopology: (projectId: string, versionId?: string) => {
    return get<TraceTopology>(`/lg/projects/${projectId}/runtime/topology`,
      versionId ? { versionId } : undefined)
  },

  /** 获取最近链路列表 */
  listTraces: (projectId: string, versionId?: string, limit = 50) => {
    return get<RuntimeTraceRecord[]>(`/lg/projects/${projectId}/runtime/traces`,
      { versionId, limit })
  },
}
