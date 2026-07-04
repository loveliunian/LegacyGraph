import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn()
}))

const downloadMocks = vi.hoisted(() => ({
  downloadFile: vi.fn()
}))

vi.mock('@/utils/request', () => ({
  default: {
    get: requestMocks.get,
    post: requestMocks.post,
    put: requestMocks.put,
    delete: requestMocks.del
  },
  get: requestMocks.get,
  post: requestMocks.post,
  put: requestMocks.put,
  del: requestMocks.del
}))

vi.mock('@/utils/download', () => ({
  downloadFile: downloadMocks.downloadFile
}))

describe('API GET 参数封装', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    requestMocks.get.mockResolvedValue({})
    requestMocks.post.mockResolvedValue({})
    downloadMocks.downloadFile.mockResolvedValue(undefined)
  })

  it('scanApi.list 应直接传分页参数，避免 params 双重嵌套', async () => {
    const { scanApi } = await import('@/api')
    const params = { pageNum: 1, pageSize: 20 }

    await scanApi.list('project-1', params)

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/projects/project-1/scan-versions', params)
  })

  it('scanApi.start 未传 baseDir 时不应发送空查询参数', async () => {
    const { scanApi } = await import('@/api')

    await scanApi.start('project-1', 'version-1')

    expect(requestMocks.post).toHaveBeenCalledWith('/lg/projects/project-1/scan-versions/version-1/start')
  })

  it('reviewApi.listPending 应直接传过滤参数，避免 params 双重嵌套', async () => {
    const { reviewApi } = await import('@/api')
    const params = { pageNum: 1, pageSize: 20, graphType: 'CODE' }

    await reviewApi.listPending('project-1', params)

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/projects/project-1/reviews', params)
  })

  it('reviewApi.listHistory 应直接传过滤参数，避免 params 双重嵌套', async () => {
    const { reviewApi } = await import('@/api')
    const params = { pageNum: 1, pageSize: 20, status: 'APPROVED' }

    await reviewApi.listHistory('project-1', params)

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/projects/project-1/reviews/history', params)
  })

  it('testApi.list 应直接传分页参数，避免 params 双重嵌套', async () => {
    const { testApi } = await import('@/api')
    const params = { pageNum: 1, pageSize: 20, status: 'READY' }

    await testApi.list('project-1', params)

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/projects/project-1/test-cases', params)
  })

  it('auditApi.list 应直接传分页参数，避免 params 双重嵌套', async () => {
    const { auditApi } = await import('@/api/audit.api')
    const params = { pageNum: 1, pageSize: 20 }

    await auditApi.list(params)

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/audit/list', params)
  })

  it('auditApi.getDetail 应编码路径参数', async () => {
    const { auditApi } = await import('@/api/audit.api')

    await auditApi.getDetail('audit 1/2')

    expect(requestMocks.get).toHaveBeenCalledWith('/lg/audit/audit%201%2F2')
  })

  it('auditApi.download 应使用带认证头的下载工具并编码路径参数', async () => {
    const { auditApi } = await import('@/api/audit.api')

    await auditApi.download('audit 1')

    expect(downloadMocks.downloadFile).toHaveBeenCalledWith('http://localhost:8080/api/lg/audit/audit%201/download')
  })
})
