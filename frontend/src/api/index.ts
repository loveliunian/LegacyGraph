// 导出拆分后的模块（推荐使用）
export * from './auth.api'
export * from './source.api'
export * from './fact.api'
export * from './test-run.api'
export * from './system.api'
export * from './report.api'
export * from './vector.api'

// 保留原有导出向后兼容
import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器
request.interceptors.request.use(config => {
  return config
}, error => {
  return Promise.reject(error)
})

// 响应拦截器
request.interceptors.response.use(response => {
  const res = response.data
  if (res.code === 0) {
    return res.data
  } else {
    console.error('Request error:', res.message)
    return Promise.reject(new Error(res.message))
  }
}, error => {
  console.error('Request error:', error)
  return Promise.reject(error)
})

// 项目管理
export const projectApi = {
  list: (params: { pageNum: number, pageSize: number, keyword?: string }) => {
    return request.get('/lg/projects', { params })
  },
  create: (data: { projectCode: string, projectName: string, description?: string }) => {
    return request.post('/lg/projects', data)
  },
  delete: (id: string) => {
    return request.delete(`/lg/projects/${id}`)
  },
  detail: (id: string) => {
    return request.get(`/lg/projects/${id}`)
  }
}

// 扫描管理
export const scanApi = {
  create: (projectId: string, data: { versionNo: string, branchName?: string }) => {
    return request.post(`/lg/projects/${projectId}/scan-versions`, data)
  },
  progress: (versionId: string) => {
    return request.get(`/lg/projects/${versionId}/scan-versions/${versionId}/progress`)
  },
  start: (projectId: string, versionId: string, baseDir?: string) => {
    return request.post(`/lg/projects/${projectId}/scan-versions/${versionId}/start?baseDir=${baseDir || ''}`)
  }
}

// 图谱查询
export const graphApi = {
  getApiChain: (projectId: string, versionId: string, api: string) => {
    return request.get(`/lg/projects/${projectId}/graph/api-chain`, { params: { versionId, api } })
  },
  getTableImpact: (projectId: string, versionId: string, tableName: string) => {
    return request.get(`/lg/projects/${projectId}/graph/table-impact`, { params: { versionId, tableName } })
  },
  getFeatureView: (projectId: string, versionId: string, module: string) => {
    return request.get(`/lg/projects/${projectId}/graph/feature-view`, { params: { versionId, module } })
  },
  getBusinessView: (projectId: string, versionId: string, domain: string) => {
    return request.get(`/lg/projects/${projectId}/graph/business-view`, { params: { versionId, domain } })
  },
  // 获取合并候选对
  getMergeCandidates: (projectId: string, nodeType: string) => {
    return request.get(`/lg/projects/${projectId}/graph/merge/candidates`, { params: { nodeType } })
  },
  // LLM决策是否合并
  decideMerge: (projectId: string, candidate: { nodeAId: string, nodeBId: string }) => {
    return request.post(`/lg/projects/${projectId}/graph/merge/decide`, candidate)
  },
  // 执行合并
  executeMerge: (projectId: string, targetNodeId: string, mergeNodeId: string) => {
    return request.post(`/lg/projects/${projectId}/graph/merge/execute`, null, {
      params: { targetNodeId, mergeNodeId }
    })
  }
}

// 人工审核
export const reviewApi = {
  listPending: (projectId: string, params: {
    targetType?: string,
    graphType?: string,
    minConfidence?: number,
    pageNum: number,
    pageSize: number
  }) => {
    return request.get(`/lg/projects/${projectId}/reviews`, { params })
  },
  listHistory: (projectId: string, params: {
    status?: string,
    reviewedBy?: string,
    pageNum: number,
    pageSize: number
  }) => {
    return request.get(`/lg/projects/${projectId}/reviews/history`, { params })
  },
  getDetail: (projectId: string, id: string) => {
    return request.get(`/lg/projects/${projectId}/reviews/${id}`)
  },
  confirmReview: (projectId: string, data: {
    targetId: string,
    targetType: string,
    comment?: string
  }) => {
    return request.post(`/lg/projects/${projectId}/reviews/confirm`, data)
  },
  rejectReview: (projectId: string, data: {
    targetId: string,
    targetType: string,
    comment?: string
  }) => {
    return request.post(`/lg/projects/${projectId}/reviews/reject`, data)
  },
  batchConfirm: (projectId: string, ids: string[], comment?: string) => {
    return request.post(`/lg/projects/${projectId}/reviews/batch-confirm`, ids, { params: { comment } })
  }
}

// 测试管理
export const testApi = {
  generate: (data: { versionId: string, scope: { nodeTypes: string[], priority: string[] } }) => {
    return request.post('/lg/test-cases/generate', data)
  },
  startRun: (data: { versionId: string, caseIds: string[], environment: string }) => {
    return request.post('/lg/test-runs/start', data)
  }
}

// 验证报告
export const validationApi = {
  getReport: (versionId: string) => {
    return request.get(`/lg/validation/report/${versionId}`)
  },
  updateConfidence: (versionId: string) => {
    return request.post(`/lg/validation/update-confidence/${versionId}`)
  },
  confirm: (data: { targetType: string, targetId: string, reviewStatus: string, comment?: string }) => {
    return request.post('/lg/validation/confirm', data)
  }
}

export default request
