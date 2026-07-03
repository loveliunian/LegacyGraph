import { get, post } from '@/utils/request'
import type { GraphNode } from '@/types'

/**
 * 向量文档实体
 * 存储向量化后的文档块，用于语义搜索和相似度检索
 */
export interface VectorDocument {
  /** 向量文档ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 块类型：CODE|DOC|COMMENT */
  chunkType: string
  /** 来源文件URI */
  sourceUri: string
  /** 块索引 */
  chunkIndex: number
  /** 块内容 */
  content: string
  /** pgvector 文本表示 */
  embedding: string
  /** 使用的embedding模型 */
  embeddingModel: string
  /** 向量维度 */
  embeddingDim: number
  /** 创建时间 */
  createdAt: string
}

/**
 * 向量检索API
 * 提供向量embedding管理、语义搜索、相似节点查找功能
 * 基于向量相似度实现语义检索，用于知识图谱中的语义关联发现
 */
export const vectorApi = {
  /**
   * 批量插入或更新向量文档
   * 将文档块转换为向量并存储到向量数据库
   * @param projectId 项目ID
   * @param documents 向量文档列表
   * @returns 操作结果
   */
  batchUpsert: (projectId: string, documents: VectorDocument[]) => {
    return post<void>(`/lg/vector/projects/${projectId}/upsert`, documents)
  },

  /**
   * 语义相似度检索
   * 根据查询文本，查找语义相似的向量文档块
   * @param projectId 项目ID
   * @param query 查询文本
   * @param topK 返回最相似的Top-K结果，默认10
   * @param chunkType 过滤块类型，可选
   * @returns 相似的向量文档列表，按相似度降序排列
   */
  semanticSearch: (projectId: string, query: string, topK: number = 10, chunkType?: string) => {
    return post<VectorDocument[]>(`/lg/vector/projects/${projectId}/search?topK=${topK}&chunkType=${chunkType || ''}`, query)
  },

  /**
   * 查找相似节点
   * 根据节点名称，查找语义相似的知识图谱节点
   * @param projectId 项目ID
   * @param nodeName 节点名称
   * @param threshold 相似度阈值，默认0.85
   * @returns 相似节点列表
   */
  findSimilarNodes: (projectId: string, nodeName: string, threshold: number = 0.85) => {
    return get<GraphNode[]>(`/lg/vector/projects/${projectId}/similar-nodes`, { nodeName, threshold })
  }
}
