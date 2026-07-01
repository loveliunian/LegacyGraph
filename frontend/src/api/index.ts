// 导出拆分后的 API 模块（推荐使用）
// 避免星号导出把不同模块中的同名类型（如 User）合并到同一个 barrel。
export { authApi } from './auth.api'
export { sourceApi } from './source.api'
export { factApi } from './fact.api'
export { testRunApi } from './test-run.api'
export { systemApi } from './system.api'
export { reportApi } from './report.api'
export { vectorApi } from './vector.api'
export { traceApi } from './trace.api'
export { agentApi } from './agent.api'
export { qaApi } from './qa.api'
export { changeTaskApi } from './change-task.api'

// 保留原有导出向后兼容
import { get, post, del, put } from '@/utils/request'

/**
 * 项目管理API
 * 提供项目的列表查询、创建、删除、详情查询功能
 */
export const projectApi = {
  /**
   * 分页查询项目列表
   * @param params 查询参数，包含分页信息和关键词
   * @returns 分页后的项目列表
   */
  list: (params: { pageNum: number, pageSize: number, keyword?: string }) => {
    return get('/lg/projects', { params })
  },

  /**
   * 创建新项目
   * @param data 创建项目数据
   * @returns 创建结果
   */
  create: (data: { projectCode: string, projectName: string, description?: string }) => {
    return post('/lg/projects', data)
  },

  /**
   * 删除项目
   * @param id 项目ID
   * @returns 删除结果
   */
  delete: (id: string) => {
    return del(`/lg/projects/${id}`)
  },

  /**
   * 获取项目详情
   * @param id 项目ID
   * @returns 项目详情
   */
  detail: (id: string) => {
    return get(`/lg/projects/${id}`)
  },

  /** 获取项目概览 */
  overview: (id: string) => {
    return get(`/lg/projects/${id}/overview`)
  }
}

/**
 * 扫描管理API
 * 管理知识图谱扫描任务，支持创建扫描版本、查询进度、启动扫描
 */
export const scanApi = {
  /**
   * 创建扫描版本
   * @param projectId 项目ID
   * @param data 创建扫描版本数据
   * @returns 创建结果
   */
  create: (projectId: string, data: { versionNo: string, branchName?: string }) => {
    return post(`/lg/projects/${projectId}/scan-versions`, data)
  },

  /**
   * 查询扫描进度
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @returns 扫描进度信息
   */
  progress: (projectId: string, versionId: string) => {
    return get(`/lg/projects/${projectId}/scan-versions/${versionId}/progress`)
  },

  /**
   * 启动扫描
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @param baseDir 本地代码基础目录（可选）
   * @returns 启动结果
   */
  start: (projectId: string, versionId: string, baseDir?: string) => {
    // F-M3：baseDir 可能含 & / # / 中文等特殊字符，必须 encodeURIComponent，否则破坏 URL
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/start?baseDir=${encodeURIComponent(baseDir || '')}`)
  },

  /**
   * 暂停扫描
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  pause: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/pause`)
  },

  /**
   * 恢复扫描
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  resume: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/resume`)
  },

  /**
   * 取消扫描
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  cancel: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/cancel`)
  },

  /**
   * 删除扫描版本
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  delete: (projectId: string, versionId: string) => {
    return del(`/lg/projects/${projectId}/scan-versions/${versionId}`)
  }
}

/**
 * 知识图谱查询API
 * 提供各种知识图谱查询功能：调用链、影响分析、视图展示、节点合并
 */
export const graphApi = {
  /**
   * 查询接口完整调用链
   * 从入口API出发，向下追踪完整的方法调用链路
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @param api API接口路径或方法名
   * @returns 调用链节点列表
   */
  getApiChain: (projectId: string, versionId: string, api: string) => {
    return get(`/lg/projects/${projectId}/graph/api-chain`, { versionId, api })
  },

  /**
   * 查询表影响范围
   * 分析哪些API和模块依赖指定的数据库表，用于评估变更影响
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @param tableName 数据库表名
   * @returns 影响范围列表
   */
  getTableImpact: (projectId: string, versionId: string, tableName: string) => {
    return get(`/lg/projects/${projectId}/graph/table-impact`, { versionId, tableName })
  },

  /**
   * 获取功能图谱视图
   * 按模块展示知识图谱，输出该模块下的所有节点和边
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @param module 模块名称
   * @returns 功能视图数据，包含节点和边
   */
  getFeatureView: (projectId: string, versionId: string, module: string) => {
    return get(`/lg/projects/${projectId}/graph/feature-view`, { versionId, module })
  },

  /**
   * 获取业务图谱视图
   * 按业务域展示知识图谱，输出该业务域下的所有节点和关系
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @param domain 业务域名称
   * @returns 业务视图数据，包含节点和边
   */
  getBusinessView: (projectId: string, versionId: string, domain: string) => {
    return get(`/lg/projects/${projectId}/graph/business-view`, { versionId, domain })
  },

  /**
   * 获取合并候选对
   * 根据相似度算法查找可能需要合并的重复节点对
   * @param projectId 项目ID
   * @param nodeType 节点类型
   * @returns 合并候选对列表
   */
  getMergeCandidates: (projectId: string, nodeType: string) => {
    return get(`/lg/projects/${projectId}/graph/merge/candidates`, { nodeType })
  },

  /**
   * LLM决策是否合并
   * 调用大语言模型判断两个节点是否应该合并
   * @param projectId 项目ID
   * @param candidate 候选对，包含两个节点ID
   * @returns 合并决策结果
   */
  decideMerge: (projectId: string, candidate: { nodeAId: string, nodeBId: string }) => {
    return post(`/lg/projects/${projectId}/graph/merge/decide`, candidate)
  },

  /**
   * 执行节点合并
   * 将源节点合并到目标节点，源节点标记为删除
   * @param projectId 项目ID
   * @param targetNodeId 目标节点ID（保留）
   * @param mergeNodeId 待合并节点ID（删除）
   * @returns 执行结果
   */
  executeMerge: (projectId: string, targetNodeId: string, mergeNodeId: string) => {
    return post(`/lg/projects/${projectId}/graph/merge/execute`, null, {
      params: { targetNodeId, mergeNodeId }
    })
  },

  /** 获取统一图谱全量数据 */
  getUnifiedGraph: (projectId: string, versionId: string, minConfidence: number = 0) => {
    return get(`/lg/projects/${projectId}/graph/unified`, { versionId, minConfidence })
  },

  /**
   * 获取功能切片列表（证据工作台）
   */
  getFeatureSlices: (projectId: string, versionId: string) => {
    return get(`/lg/projects/${projectId}/graph/feature-slices`, { versionId })
  },

  /**
   * 获取单个功能切片详情
   */
  getFeatureSliceDetail: (projectId: string, sliceId: string) => {
    return get(`/lg/projects/${projectId}/graph/feature-slices/${sliceId}`)
  },

  /**
   * 获取图谱质量统计（含无证据节点/AI-only边/runtime-only边等）
   */
  getGraphQualityReport: (projectId: string, versionId?: string) => {
    return get(`/lg/projects/${projectId}/graph/quality`, versionId ? { versionId } : {})
  },

  /**
   * 获取漂移队列
   */
  getDriftQueue: (projectId: string, type?: string) => {
    return get(`/lg/projects/${projectId}/graph/drift-queue`, type ? { type } : {})
  },

  /** 获取数据库表节点列表（仅Table节点，轻量查询） */
  getTables: (projectId: string, versionId: string) => {
    return get(`/lg/projects/${projectId}/graph/tables`, { versionId })
  },

  /** 获取项目扫描版本列表 */
  getScanVersions: (projectId: string) => {
    return get(`/lg/projects/${projectId}/scan-versions`)
  },
}

/**
 * 人工审核API
 * 对待审核的知识图谱节点进行人工审核，支持确认、拒绝、批量操作
 */
export const reviewApi = {
  /**
   * 查询待审核列表
   * @param projectId 项目ID
   * @param params 查询参数，包含过滤条件和分页
   * @returns 分页后的待审核列表
   */
  listPending: (projectId: string, params: {
    targetType?: string,
    graphType?: string,
    minConfidence?: number,
    pageNum: number,
    pageSize: number
  }) => {
    return get(`/lg/projects/${projectId}/reviews`, { params })
  },

  /**
   * 查询审核历史记录
   * @param projectId 项目ID
   * @param params 查询参数，包含过滤条件和分页
   * @returns 分页后的审核历史列表
   */
  listHistory: (projectId: string, params: {
    status?: string,
    reviewedBy?: string,
    pageNum: number,
    pageSize: number
  }) => {
    return get(`/lg/projects/${projectId}/reviews/history`, { params })
  },

  /**
   * 获取审核详情
   * @param projectId 项目ID
   * @param id 审核记录ID
   * @returns 审核详情
   */
  getDetail: (projectId: string, id: string) => {
    return get(`/lg/projects/${projectId}/reviews/${id}`)
  },

  /**
   * 确认审核通过
   * @param projectId 项目ID
   * @param data 审核数据
   * @returns 操作结果
   */
  confirmReview: (projectId: string, data: {
    targetId: string,
    targetType: string,
    comment?: string
  }) => {
    return post(`/lg/projects/${projectId}/reviews/confirm`, data)
  },

  /**
   * 拒绝审核不通过
   * @param projectId 项目ID
   * @param data 审核数据
   * @returns 操作结果
   */
  rejectReview: (projectId: string, data: {
    targetId: string,
    targetType: string,
    comment?: string
  }) => {
    return post(`/lg/projects/${projectId}/reviews/reject`, data)
  },

  /**
   * 批量确认审核通过
   * @param projectId 项目ID
   * @param ids 审核记录ID列表
   * @param comment 审核评论（可选）
   * @returns 操作结果
   */
  batchConfirm: (projectId: string, ids: string[], comment?: string) => {
    return post(`/lg/projects/${projectId}/reviews/batch-confirm`, ids, { params: { comment } })
  }
}

/**
 * 测试用例管理API
 * 支持自动生成测试用例、CRUD、启动测试执行
 */
export const testApi = {
  /**
   * 分页查询测试用例列表
   * @param projectId 项目ID
   * @param params 查询参数
   */
  list: (projectId: string, params: {
    pageNum: number
    pageSize: number
    caseType?: string
    status?: string
  }) => {
    return get(`/lg/projects/${projectId}/test-cases`, { params })
  },

  /**
   * 获取测试用例详情
   * @param projectId 项目ID
   * @param id 用例ID
   */
  getDetail: (projectId: string, id: string) => {
    return get(`/lg/projects/${projectId}/test-cases/${id}`)
  },

  /**
   * 创建测试用例
   * @param projectId 项目ID
   * @param data 用例数据
   */
  create: (projectId: string, data: any) => {
    return post(`/lg/projects/${projectId}/test-cases`, data)
  },

  /**
   * 更新测试用例
   * @param projectId 项目ID
   * @param id 用例ID
   * @param data 用例数据
   */
  update: (projectId: string, id: string, data: any) => {
    return put(`/lg/projects/${projectId}/test-cases/${id}`, data)
  },

  /**
   * 删除测试用例
   * @param projectId 项目ID
   * @param id 用例ID
   */
  delete: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/test-cases/${id}`)
  },

  /**
   * 根据功能节点生成测试用例
   * 使用LLM根据功能描述自动生成测试用例
   * @param data 生成参数，包含版本ID和生成范围
   * @returns 生成的测试用例列表
   */
  generate: (projectId: string, data: { versionId: string, scope: { nodeTypes: string[], priority: string[] } }) => {
    return post(`/lg/projects/${projectId}/test-cases/generate`, data)
  },

  /**
   * 启动测试执行
   * 批量执行选中的测试用例
   * @param data 启动参数，包含版本ID、测试用例ID列表和执行环境
   * @returns 启动结果
   */
  startRun: (projectId: string, data: { versionId: string, caseIds: string[], environment: string }) => {
    return post(`/lg/projects/${projectId}/test-runs/start`, data)
  }
}

/**
 * 验证报告API
 * 获取验证报告，更新节点置信度，确认验证结果
 */
export const validationApi = {
  /**
   * 获取验证报告
   * @param versionId 扫描版本ID
   * @returns 验证报告数据
   */
  getReport: (versionId: string) => {
    return get(`/lg/validation/report/${versionId}`)
  },

  /**
   * 更新节点置信度
   * 根据审核结果重新计算所有节点的置信度
   * @param versionId 扫描版本ID
   * @returns 操作结果
   */
  updateConfidence: (versionId: string) => {
    return post(`/lg/validation/update-confidence/${versionId}`)
  },

  /**
   * 确认验证结果
   * @param data 确认参数，包含目标信息和审核状态
   * @returns 操作结果
   */
  confirm: (data: { targetType: string, targetId: string, reviewStatus: string, comment?: string }) => {
    return post('/lg/validation/confirm', data)
  }
}

