import { get, post } from '@/utils/request'

/**
 * 合并候选对（与后端 GraphMergeService.MergeCandidate 对齐）。
 * 6 维评分：name + semantic + struct + evidence + runtime + history。
 */
export interface MergeCandidate {
  nodeAId: string
  nodeBId: string
  /** 综合相似度 (0-1) */
  similarityScore: number
  /** 名称相似度 */
  nameScore: number
  /** 语义相似度 */
  semanticScore: number
  /** 结构邻域相似度 */
  structScore: number
  /** 共享证据分 */
  evidenceScore: number
  /** 运行时共现分 */
  runtimeCooccurrenceScore: number
  /** 人工历史分 */
  historyScore: number
}

/** 合并决策结果 */
export type MergeDecision = 'AUTO_MERGE' | 'REVIEW' | 'REJECT'

export interface GraphMergeDecision {
  candidateA: string
  candidateB: string
  score: number
  decision: MergeDecision
  reasons: string[]
}

export const mergeApi = {
  /**
   * 获取合并候选对
   * @param projectId 项目 ID
   * @param nodeType 节点类型（BusinessDomain/BusinessProcess/BusinessObject/BusinessRule/Role/Feature/...）
   */
  listCandidates: (projectId: string, nodeType: string) => {
    return get<MergeCandidate[]>(
      `/lg/projects/${encodeURIComponent(projectId)}/graph/merge/candidates`,
      { nodeType },
    )
  },

  /**
   * LLM 决策是否合并（中间档候选送审）
   */
  decideMerge: (projectId: string, candidate: MergeCandidate) => {
    return post<GraphMergeDecision>(
      `/lg/projects/${encodeURIComponent(projectId)}/graph/merge/decide`,
      candidate,
    )
  },

  /**
   * 执行合并：将 mergeNode 合并到 targetNode
   */
  executeMerge: (projectId: string, targetNodeId: string, mergeNodeId: string) => {
    return post<void>(
      `/lg/projects/${encodeURIComponent(projectId)}/graph/merge/execute`,
      undefined,
      { params: { targetNodeId, mergeNodeId } },
    )
  },
}
