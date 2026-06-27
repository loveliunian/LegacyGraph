import { get, post } from '@/utils/request'
import type { PageResult } from '@/types'

export interface TestCase {
  id: string
  projectId: string
  versionId: string
  caseCode: string
  caseName: string
  caseType: string
  targetNodeId: string
  steps: string
  preconditions: string
  expectedResult: string
  status: string
  createdAt: string
}

export interface TestRun {
  id: string
  projectId: string
  versionId: string
  environment: string
  status: string
  startedAt: string
  finishedAt: string
  totalCases: number
  passedCases: number
  failedCases: number
}

export interface TestResult {
  id: string
  testCaseId: string
  executionId: string
  resultStatus: string
  requestData: string
  responseData: string
  errorMessage: string
  durationMs: number
  executedAt: string
}

export const testRunApi = {
  listTestRuns: (projectId: string, params: {
    pageNum: number
    pageSize: number
    status?: string
  }) => {
    return get<PageResult<TestRun>>(`/lg/projects/${projectId}/test-runs`, params)
  },

  getTestRunDetail: (projectId: string, runId: string) => {
    return get<TestRun>(`/lg/projects/${projectId}/test-runs/${runId}`)
  },

  getCaseResults: (projectId: string, runId: string) => {
    return get<TestResult[]>(`/lg/projects/${projectId}/test-runs/${runId}/case-results`)
  },

  getResultLogs: (projectId: string, runId: string) => {
    return get<string>(`/lg/projects/${projectId}/test-runs/${runId}/logs`)
  },

  startTestRun: (projectId: string, data: {
    versionId: string
    caseIds: string[]
    environment: string
  }) => {
    return post<string>(`/lg/projects/${projectId}/test-runs/start`, data)
  },

  rerunFailed: (projectId: string, runId: string) => {
    return post<string>(`/lg/projects/${projectId}/test-runs/${runId}/rerun-failed`)
  }
}
