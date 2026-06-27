import { get, post } from '@/utils/request'
import type { GraphNode } from '@/types'

export interface VectorDocument {
  id: string
  projectId: number
  chunkType: string
  sourceUri: string
  chunkIndex: number
  content: string
  embedding: number[]
  embeddingModel: string
  embeddingDim: number
  createdAt: string
}

export const vectorApi = {
  // 批量upsert向量文档
  batchUpsert: (projectId: number, documents: VectorDocument[]) => {
    return post<void>(`/lg/projects/${projectId}/vector/upsert`, documents)
  },

  // 语义相似度检索
  semanticSearch: (projectId: number, query: string, topK: number = 10, chunkType?: string) => {
    return post<VectorDocument[]>(`/lg/projects/${projectId}/vector/search?topK=${topK}&chunkType=${chunkType || ''}`, query)
  },

  // 查找相似节点
  findSimilarNodes: (projectId: number, nodeName: string, threshold: number = 0.85) => {
    return get<GraphNode[]>(`/lg/projects/${projectId}/vector/similar-nodes`, { nodeName, threshold })
  }
}
