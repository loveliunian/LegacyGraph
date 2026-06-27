import { get, post } from '@/utils/request'
import type { PageResult, PageQuery } from '@/types'

export interface Fact {
  id: string
  projectId: string
  factType: string
  factText: string
  sourceType: string
  sourceId: string
  confidence: number
  status: string
  createdAt: string
}

export interface Evidence {
  id: string
  projectId: string
  evidenceType: string
  sourceName: string
  summary: string
  content: string
  relatedNodeIds: string
  createdAt: string
}

export const factApi = {
  listFacts: (projectId: string, params: PageQuery & {
    factType?: string
    sourceType?: string
    minConfidence?: number
  }) => {
    return get<PageResult<Fact>>(`/lg/projects/${projectId}/facts`, params)
  },

  getFact: (projectId: string, id: string) => {
    return get<Fact>(`/lg/projects/${projectId}/facts/${id}`)
  },

  getRelatedNodes: (projectId: string, id: string) => {
    return get<string[]>(`/lg/projects/${projectId}/facts/${id}/related-nodes`)
  },

  searchEvidence: (projectId: string, params: PageQuery & {
    evidenceType?: string
    keyword?: string
  }) => {
    return get<PageResult<Evidence>>(`/lg/projects/${projectId}/evidence`, params)
  },

  getEvidence: (projectId: string, id: string) => {
    return get<Evidence>(`/lg/projects/${projectId}/evidence/${id}`)
  },

  extractCodeFacts: (projectId: string, data: {
    repoId: string
    filePath: string
    content: string
  }) => {
    return post(`/lg/projects/${projectId}/extract/facts/code`, data)
  },

  extractDocFacts: (projectId: string, data: {
    docId: string
    content: string
  }) => {
    return post(`/lg/projects/${projectId}/extract/facts/doc`, data)
  }
}
