export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}

export interface Project {
  id: string
  projectCode: string
  projectName: string
  description: string
  projectType: string
  repoUrl: string
  defaultBranch: string
  owner: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface ScanVersion {
  id: string
  projectId: string
  versionNo: string
  branchName: string
  commitId: string
  scanStatus: string
  startedAt: string
  finishedAt: string
  errorMessage: string
}

export interface GraphNode {
  id: string
  nodeType: string
  nodeKey: string
  nodeName: string
  displayName: string
  description: string
  confidence: number
  status: string
  properties: Record<string, any>
}

export interface GraphEdge {
  id: string
  fromNodeId: string
  toNodeId: string
  edgeType: string
  confidence: number
  status: string
}
