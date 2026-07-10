import { get, post } from '@/utils/request'

/** 工具健康状态 DTO */
export interface ToolHealthDto {
  toolName: string
  toolKind: string
  status: string
  capabilities: string[]
  indexFreshness: string
  message: string
}

/** 工具健康状态响应 */
export interface ToolHealthResponse {
  projectId: string
  tools: ToolHealthDto[]
}

/** 代码理解请求 */
export interface CodeUnderstandingRequest {
  versionId?: string
  question: string
  scope?: {
    paths?: string[]
    symbols?: string[]
    featureKeys?: string[]
  }
  reportType?: string
  format?: string
  toolPolicy?: {
    enabledToolKinds?: string[]
    allowedTools?: string[]
    executionMode?: string
    allowExternalNetwork?: boolean
    allowAiInference?: boolean
    maxFilesToRead?: number
    maxToolRuns?: number
    maxSeconds?: number
    maxOutputBytes?: number
  }
}

/** 代码理解任务结果 */
export interface CodeUnderstandingTaskResult {
  taskId: string
  status: string
  reportId: string
  toolRuns: number
  evidenceCount: number
  claimCount: number
  pendingConfirmCount: number
  downloadUrl: string
}

/** 创建报告响应 */
export interface CreateUnderstandingReportResponse {
  taskId: string
  status: string
  reportId: string
  toolRuns: number
  evidenceCount: number
  claimCount: number
  pendingConfirmCount: number
  downloadUrl: string
  toolStatus: Record<string, string>
}

/**
 * 代码理解 API
 */
export const understandingApi = {
  /**
   * 查询工具健康状态
   */
  getToolHealth: (projectId: string) => {
    return get<ToolHealthResponse>(`/lg/projects/${encodeURIComponent(projectId)}/understanding/tool-health`)
  },

  /**
   * 创建代码理解报告
   */
  createReport: (projectId: string, request: CodeUnderstandingRequest) => {
    return post<CreateUnderstandingReportResponse>(
      `/lg/projects/${encodeURIComponent(projectId)}/understanding/reports`,
      request
    )
  },

  /**
   * 查询报告任务状态
   */
  getReport: (projectId: string, taskId: string) => {
    return get<CodeUnderstandingTaskResult>(
      `/lg/projects/${encodeURIComponent(projectId)}/understanding/reports/${encodeURIComponent(taskId)}`
    )
  },

  /**
   * 下载报告
   */
  downloadReport: (projectId: string, taskId: string) => {
    return get<Blob>(
      `/lg/projects/${encodeURIComponent(projectId)}/understanding/reports/${encodeURIComponent(taskId)}/download`,
      { format: 'MD' },
      { responseType: 'blob' }
    )
  }
}
