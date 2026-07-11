import { get, post } from '@/utils/request'

/**
 * 方案领域类型定义
 * 对应后端 Solution 相关 API：
 *  - POST /lg/projects/{projectId}/solutions/generate
 *  - GET /lg/solutions/{solutionId}
 *  - POST /lg/solutions/{solutionId}/verify
 */

/** 方案生成请求参数 */
export interface SolutionGenerateRequest {
  projectId: string
  /** 关联的需求 ID（已保存的需求） */
  requirementId?: string
  /** 需求目标（未保存时可直接基于目标生成） */
  goal?: string
  [k: string]: unknown
}

/** 文件级实施步骤 */
export interface SolutionStep {
  /** 步骤标题 */
  title: string
  /** 步骤描述 */
  description?: string
  /** 目标文件路径 */
  filePath?: string
  /** 目标符号名（方法/类/函数） */
  symbolName?: string
  /** 操作类型：CREATE / UPDATE / DELETE 等 */
  actionType?: string
  /** 测试说明 */
  testDescription?: string
  /** 回滚说明 */
  rollbackDescription?: string
  /** 代码片段 */
  codeSnippet?: string
  /** 代码片段语言（Java / SQL / Vue 等） */
  codeLanguage?: string
  /** 关联证据 ID 列表 */
  evidenceIds?: string[]
  [k: string]: unknown
}

/** 单个方案 */
export interface Solution {
  id: string
  /** 方案标题 */
  title?: string
  /** 方案摘要 */
  summary?: string
  /** 关联项目 ID */
  projectId?: string
  /** 关联需求 ID */
  requirementId?: string
  /** 方案状态 */
  status?: string
  /** 文件级步骤列表 */
  steps?: SolutionStep[]
  /** 方案级关联证据 ID 列表 */
  evidenceIds?: string[]
  /** 校验错误列表 */
  verificationErrors?: string[]
  /** 校验是否通过 */
  verificationPassed?: boolean
  [k: string]: unknown
}

/**
 * 方案生成响应。
 * 后端可能返回单个方案、方案列表，或含 recommended/alternatives 的方案集，
 * 这里用宽松类型承接，页面侧再归一化为列表展示。
 */
export type SolutionGenerateResponse = Solution | Solution[] | {
  recommended?: Solution
  alternatives?: Solution[]
  solutions?: Solution[]
  [k: string]: unknown
}

/**
 * 方案校验结果
 */
export interface SolutionVerifyResult {
  solutionId?: string
  verificationPassed?: boolean
  verificationErrors?: string[]
  [k: string]: unknown
}

/**
 * 方案 API
 * 封装方案生成、详情获取、方案校验能力
 */
export const solutionApi = {
  /**
   * 基于需求生成方案
   * @param projectId 项目 ID
   * @param data 生成参数（requirementId 或 goal）
   * @returns 方案生成结果（单方案 / 方案列表 / 方案集）
   */
  generate: (projectId: string, data: SolutionGenerateRequest) => {
    return post<SolutionGenerateResponse>(
      `/lg/projects/${encodeURIComponent(projectId)}/solutions/generate`,
      data
    )
  },

  /**
   * 获取方案详情（含步骤列表）
   * @param solutionId 方案 ID
   * @returns 方案详情
   */
  get: (solutionId: string) => {
    return get<Solution>(`/lg/solutions/${encodeURIComponent(solutionId)}`)
  },

  /**
   * 获取项目下所有方案列表（含步骤）
   * @param projectId 项目 ID
   * @returns 方案列表
   */
  listByProject: (projectId: string) => {
    return get<Solution[]>(`/lg/projects/${encodeURIComponent(projectId)}/solutions`)
  },

  /**
   * 校验方案
   * @param solutionId 方案 ID
   * @param data 校验参数（可选）
   * @returns 校验结果（verificationPassed, verificationErrors）
   */
  verify: (solutionId: string, data?: Record<string, unknown>) => {
    return post<SolutionVerifyResult>(
      `/lg/solutions/${encodeURIComponent(solutionId)}/verify`,
      data
    )
  },
}
