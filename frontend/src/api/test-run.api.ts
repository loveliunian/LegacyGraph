import { get, post } from '@/utils/request'
import type { PageResult } from '@/types'

/**
 * 测试用例实体
 * 自动生成的测试用例信息
 */
export interface TestCase {
  /** 测试用例ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 关联扫描版本ID */
  versionId: string
  /** 测试用例编码 */
  caseCode: string
  /** 测试用例名称 */
  caseName: string
  /** 测试用例类型：FUNCTION|API|INTEGRATION */
  caseType: string
  /** 关联目标节点ID */
  targetNodeId: string
  /** 测试步骤，JSON格式 */
  steps: string
  /** 前置条件 */
  preconditions: string
  /** 期望结果 */
  expectedResult: string
  /** 状态：ENABLED|DISABLED */
  status: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 测试运行实体
 * 一次测试执行的记录
 */
export interface TestRun {
  /** 测试运行ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 关联扫描版本ID */
  versionId: string
  /** 执行环境 */
  environment: string
  /** 状态：RUNNING|FINISHED|FAILED|CANCELLED */
  status: string
  /** 开始时间 */
  startedAt: string
  /** 结束时间 */
  finishedAt: string
  /** 总用例数 */
  totalCases: number
  /** 通过用例数 */
  passedCases: number
  /** 失败用例数 */
  failedCases: number
}

/**
 * 测试结果实体
 * 单个测试用例的执行结果
 */
export interface TestResult {
  /** 结果ID */
  id: string
  /** 测试用例ID */
  testCaseId: string
  /** 测试运行ID */
  executionId: string
  /** 结果状态：PASSED|FAILED|ERROR|SKIPPED */
  resultStatus: string
  /** 请求数据，JSON格式 */
  requestData: string
  /** 响应数据，JSON格式 */
  responseData: string
  /** 错误信息 */
  errorMessage: string
  /** 执行耗时（毫秒） */
  durationMs: number
  /** 执行时间 */
  executedAt: string
}

/**
 * 测试运行API
 * 管理测试执行运行，支持启动测试、查询结果、重跑失败用例
 */
export const testRunApi = {
  /**
   * 分页查询测试运行列表
   * @param projectId 项目ID
   * @param params 查询参数，包含分页和状态筛选
   * @returns 分页后的测试运行列表
   */
  listTestRuns: (projectId: string, params: {
    pageNum: number
    pageSize: number
    status?: string
  }) => {
    return get<PageResult<TestRun>>(`/lg/projects/${projectId}/test-runs`, params)
  },

  /**
   * 获取测试运行详情
   * @param projectId 项目ID
   * @param runId 测试运行ID
   * @returns 测试运行详情
   */
  getTestRunDetail: (projectId: string, runId: string) => {
    return get<TestRun>(`/lg/projects/${projectId}/test-runs/${runId}`)
  },

  /**
   * 获取测试用例执行结果列表
   * @param projectId 项目ID
   * @param runId 测试运行ID
   * @returns 测试结果列表
   */
  getCaseResults: (projectId: string, runId: string) => {
    return get<TestResult[]>(`/lg/projects/${projectId}/test-runs/${runId}/results`)
  },

  /**
   * 获取测试运行日志
   * @param projectId 项目ID
   * @param runId 测试运行ID
   * @returns 日志内容
   */
  getResultLogs: (projectId: string, runId: string) => {
    return get<string>(`/lg/projects/${projectId}/test-runs/${runId}/logs`)
  },

  /**
   * 启动测试运行
   * 批量执行选中的测试用例
   * @param projectId 项目ID
   * @param data 启动参数，包含版本ID、测试用例ID列表和执行环境
   * @returns 测试运行ID
   */
  startTestRun: (projectId: string, data: {
    versionId: string
    caseIds: string[]
    environment: string
  }) => {
    return post<string>(`/lg/projects/${projectId}/test-runs/start`, data)
  },

  /**
   * 重跑失败的测试用例
   * 重新执行上一次测试运行中失败的用例
   * @param projectId 项目ID
   * @param runId 原测试运行ID
   * @returns 新的测试运行ID
   */
  rerunFailed: (projectId: string, runId: string) => {
    return post<string>(`/lg/projects/${projectId}/test-runs/${runId}/rerun-failed`)
  },

  /**
   * 取消测试运行
   * @param projectId 项目ID
   * @param runId 测试运行ID
   */
  cancelRun: (projectId: string, runId: string) => {
    return post(`/lg/projects/${projectId}/test-runs/${runId}/cancel`)
  },

  /**
   * 获取测试运行统计
   * @param projectId 项目ID
   */
  getRunStats: (projectId: string) => {
    return get(`/lg/projects/${projectId}/test-runs/stats`)
  }
}
