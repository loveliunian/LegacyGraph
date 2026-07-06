import { get, post } from '@/utils/request'

export interface GraphifyImportRequest {
  graphJsonPath: string
  projectRoot: string
}

export interface GraphifyRunRequest {
  projectRoot: string
  postgresDsn?: string | null
  force?: boolean
}

export interface GraphifyQuestionRequest {
  question: string
  allowedSourceTypes?: string[]
  maxEvidence?: number
}

export interface GraphifyJob {
  id: string
  projectId: string
  versionId: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
  progress?: number
  errorMessage?: string
  nodeCount?: number
  edgeCount?: number
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface GraphifyDiffResult {
  addedNodes: Array<{ id: string; name: string; type: string }>
  removedNodes: Array<{ id: string; name: string; type: string }>
  addedEdges: Array<{ id: string; from: string; to: string; type: string }>
  removedEdges: Array<{ id: string; from: string; to: string; type: string }>
}

export interface GraphifyQuestionResult {
  answer: string
  evidence: Array<{
    sourceType: string
    sourcePath: string
    excerpt: string
    score: number
  }>
  confidence: number
}

export interface GraphifyQualityResult {
  overallScore: number
  nodeCoverage: number
  edgeCoverage: number
  benchmarkResults: Array<{
    name: string
    passed: boolean
    score: number
    threshold: number
    details?: string
  }>
  releaseGatePassed: boolean
  releaseGateReason?: string
}

export interface CrossRepoImpactChain {
  sourceRepo: string
  sourceNode: { id: string; name: string; type: string }
  targetRepo: string
  targetNode: { id: string; name: string; type: string }
  chain: Array<{ id: string; name: string; type: string; repo: string }>
  impactLevel: 'HIGH' | 'MEDIUM' | 'LOW'
}

export const graphifyApi = {
  importGraph(projectId: string, versionId: string, data: GraphifyImportRequest) {
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/graphify/import`, data)
  },
  run(projectId: string, versionId: string, data: GraphifyRunRequest) {
    return post(`/lg/projects/${projectId}/scan-versions/${versionId}/graphify/run`, data)
  },
  getJobs(projectId: string) {
    return get(`/lg/projects/${projectId}/graphify/jobs`)
  },
  retryJob(projectId: string, jobId: string) {
    return post(`/lg/projects/${projectId}/graphify/jobs/${jobId}/retry`, {})
  },
  rollbackJob(projectId: string, jobId: string) {
    return post(`/lg/projects/${projectId}/graphify/jobs/${jobId}/rollback`, {})
  },
  getDiff(projectId: string, oldVersionId: string, newVersionId: string) {
    return get(`/lg/projects/${projectId}/graphify/diff/${oldVersionId}/${newVersionId}`)
  },
  askQuestion(projectId: string, data: GraphifyQuestionRequest) {
    return post(`/lg/projects/${projectId}/graphify/questions`, data)
  },
  getQuality(projectId: string, versionId?: string) {
    return get(`/lg/projects/${projectId}/graphify/quality`, versionId ? { versionId } : {})
  },
  getCrossRepoImpact(projectId: string, versionId?: string) {
    return get(`/lg/projects/${projectId}/graphify/cross-repo-impact`, versionId ? { versionId } : {})
  },
}
