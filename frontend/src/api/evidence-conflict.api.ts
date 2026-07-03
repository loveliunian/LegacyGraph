import { get, post } from '@/utils/request'

export interface EvidenceConflict {
  id: string
  projectId: string
  title: string
  severity: string
  nodeId?: string
  sourceA?: { type: string; content: string }
  sourceB?: { type: string; content: string }
  aiSuggestion?: string
  context?: string
  resolved: boolean
  resolution?: string
  createdAt: string
}

export const evidenceConflictApi = {
  list: (projectId: string, includeResolved = false) => {
    return get<EvidenceConflict[]>('/lg/evidence-conflicts', { projectId, includeResolved })
  },

  resolve: (id: string, resolution: string) => {
    return post<EvidenceConflict>(`/lg/evidence-conflicts/${id}/resolve`, { resolution })
  },
}
