import { get, post } from '@/utils/request'

/** 知识断言（对应后端 KnowledgeClaim 实体） */
export interface KnowledgeClaim {
  id: string
  projectId: string
  versionId?: string
  subjectType: string
  subjectKey: string
  predicate: string
  objectType?: string
  objectKey?: string
  objectValue?: string
  qualifiers?: string
  evidenceIds?: string
  supportingClaimIds?: string
  contradictingClaimIds?: string
  sourceType?: string
  extractor?: string
  confidence?: number
  status?: string
  lineage?: string
  compiledNodeId?: string
  compiledEdgeId?: string
  compileStatus?: string
  createdAt?: string
  updatedAt?: string
}

/** 知识缺口（对应后端 GapTaskView DTO） */
export interface GapTaskView {
  id: string
  gapType: string
  gapKey?: string
  title: string
  description?: string
  severity?: string
  status: string
  subjectType?: string
  subjectKey?: string
  relatedClaimIds?: string[]
  relatedNodeIds?: string[]
  evidenceIds?: string[]
  suggestedAction?: string
  priorityScore?: number
  createdAt?: string
  updatedAt?: string
}

export interface KnowledgeClaimQuery {
  versionId?: string
  subjectType?: string
  predicate?: string
  status?: string
  sourceType?: string
  pageNum?: number
  pageSize?: number
}

export interface GapTaskQuery {
  versionId?: string
  gapType?: string
  status?: string
  severity?: string
  pageNum?: number
  pageSize?: number
}

export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}

export const knowledgeApi = {
  listClaims(projectId: string, params?: KnowledgeClaimQuery) {
    return get<KnowledgeClaim[]>(`/lg/projects/${projectId}/knowledge/claims`, params)
  },
  getClaim(projectId: string, id: string) {
    return get<KnowledgeClaim>(`/lg/projects/${projectId}/knowledge/claims/${id}`)
  },
  listGaps(projectId: string, params?: GapTaskQuery) {
    return get<GapTaskView[]>(`/lg/projects/${projectId}/knowledge/gaps`, params)
  },
  resolveGap(projectId: string, id: string) {
    return post(`/lg/projects/${projectId}/knowledge/gaps/${id}/resolve`, {})
  },
}
