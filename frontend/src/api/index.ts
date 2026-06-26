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
  getApiChain: (versionId: string, api: string) => {
    return request.get('/lg/graph/api-chain', { params: { versionId, api } })
  },
  getTableImpact: (versionId: string, tableName: string) => {
    return request.get('/lg/graph/table-impact', { params: { versionId, tableName } })
  },
  getFeatureView: (versionId: string, module: string) => {
    return request.get('/lg/graph/feature-view', { params: { versionId, module } })
  },
  getBusinessView: (versionId: string, domain: string) => {
    return request.get('/lg/graph/business-view', { params: { versionId, domain } })
  }
}

// 人工确认
export const reviewApi = {
  listPending: (versionId: string, minConfidence: number, pageNum: number, pageSize: number) => {
    return request.get('/lg/reviews/pending', { params: { versionId, minConfidence, pageNum, pageSize } })
  },
  confirm: (data: { targetType: string, targetId: string, reviewStatus: string, comment?: string }) => {
    return request.post('/lg/reviews/confirm', data)
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
