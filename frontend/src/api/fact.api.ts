import { get, post } from '@/utils/request'
import type { PageResult, PageQuery } from '@/types'

/**
 * 知识事实实体
 * 从代码和文档中抽取出来的业务知识事实
 */
export interface Fact {
  /** 事实ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 事实类型：BUSINESS|TECHNICAL|RULE */
  factType: string
  /** 事实文本描述 */
  factText: string
  /** 来源类型：CODE|DOC|DB */
  sourceType: string
  /** 来源ID：对应代码文件/文档/表 */
  sourceId: string
  /** LLM置信度评分 0-1 */
  confidence: number
  /** 状态：PENDING|CONFIRMED|REJECTED */
  status: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 证据实体
 * 支持知识事实的证据，包含来源信息和内容摘要
 */
export interface Evidence {
  /** 证据ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 证据类型：CODE|DOC|COMMENT */
  evidenceType: string
  /** 来源名称 */
  sourceName: string
  /** 内容摘要 */
  summary: string
  /** 完整证据内容 */
  content: string
  /** 关联节点ID列表，逗号分隔 */
  relatedNodeIds: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 知识事实API
 * 管理从代码和文档中抽取的知识事实，支持查询、抽取、关联查询
 */
export const factApi = {
  /**
   * 分页查询知识事实列表
   * @param projectId 项目ID
   * @param params 查询参数，包含分页和筛选条件
   * @returns 分页后的知识事实列表
   */
  listFacts: (projectId: string, params: PageQuery & {
    factType?: string
    sourceType?: string
    minConfidence?: number
  }) => {
    return get<PageResult<Fact>>(`/lg/projects/${projectId}/facts`, params)
  },

  /**
   * 获取知识事实详情
   * @param projectId 项目ID
   * @param id 事实ID
   * @returns 事实详情
   */
  getFact: (projectId: string, id: string) => {
    return get<Fact>(`/lg/projects/${projectId}/facts/${id}`)
  },

  /**
   * 获取知识事实关联的图谱节点ID列表
   * @param projectId 项目ID
   * @param id 事实ID
   * @returns 关联节点ID列表
   */
  getRelatedNodes: (projectId: string, id: string) => {
    return get<string[]>(`/lg/projects/${projectId}/facts/${id}/related-nodes`)
  },

  /**
   * 分页搜索证据列表
   * @param projectId 项目ID
   * @param params 查询参数，包含分页和筛选条件
   * @returns 分页后的证据列表
   */
  searchEvidence: (projectId: string, params: PageQuery & {
    evidenceType?: string
    keyword?: string
  }) => {
    return get<PageResult<Evidence>>(`/lg/projects/${projectId}/evidence`, params)
  },

  /**
   * 获取证据详情
   * @param projectId 项目ID
   * @param id 证据ID
   * @returns 证据详情，包含完整内容
   */
  getEvidence: (projectId: string, id: string) => {
    return get<Evidence>(`/lg/projects/${projectId}/evidence/${id}`)
  },

  /**
   * 从代码片段中抽取知识事实
   * 使用LLM从给定的代码内容中抽取业务知识事实
   * @param projectId 项目ID
   * @param data 抽取参数，包含仓库ID、文件路径和代码内容
   * @returns 抽取结果
   */
  extractCodeFacts: (projectId: string, data: {
    repoId: string
    filePath: string
    content: string
  }) => {
    return post(`/agents/run`, {
      agentType: 'codefact',
      projectId,
      variables: {
        sourcePath: data.filePath,
        codeContent: data.content
      }
    })
  },

  /**
   * 从文档内容中抽取知识事实
   * 使用LLM从给定的文档内容中抽取业务知识事实
   * @param projectId 项目ID
   * @param data 抽取参数，包含文档ID和文档内容
   * @returns 抽取结果
   */
  extractDocFacts: (projectId: string, data: {
    docId: string
    content: string
  }) => {
    return post(`/agents/run`, {
      agentType: 'docunderstanding',
      projectId,
      variables: {
        sourcePath: data.docId,
        docContent: data.content
      }
    })
  }
}
