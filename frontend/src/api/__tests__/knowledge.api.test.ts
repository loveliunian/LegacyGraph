import { beforeEach, describe, expect, it, vi } from 'vitest'
import { knowledgeApi } from '../knowledge.api'

const { mockGet, mockPost, mockPut } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
  mockPut: vi.fn()
}))

vi.mock('@/utils/request', () => ({
  get: (...args: any[]) => mockGet(...args),
  post: (...args: any[]) => mockPost(...args),
  put: (...args: any[]) => mockPut(...args),
}))

describe('knowledgeApi', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockPut.mockReset()
    mockGet.mockResolvedValue({})
    mockPost.mockResolvedValue({})
  })

  describe('listClaims', () => {
    it('应调用 get 并传入正确的 URL', () => {
      knowledgeApi.listClaims('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/claims')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      knowledgeApi.listClaims('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 params 查询参数', () => {
      const params = { status: 'CONFIRMED', pageNum: 1, pageSize: 20 }
      knowledgeApi.listClaims('proj-1', params)
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-1/knowledge/claims', params)
    })

    it('未传 params 时第二参数为 undefined', () => {
      knowledgeApi.listClaims('proj-1')
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-1/knowledge/claims', undefined)
    })
  })

  describe('getClaim', () => {
    it('应调用 get 并传入正确的 URL', () => {
      knowledgeApi.getClaim('proj-1', 'claim-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/claims/claim-1')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      knowledgeApi.getClaim('proj-1', 'claim-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确拼接 projectId 和 id 到 URL', () => {
      knowledgeApi.getClaim('proj-2', 'claim-9')
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-2/knowledge/claims/claim-9')
    })

    it('id 含特殊字符时应进行 URL encode', () => {
      knowledgeApi.getClaim('proj-1', 'claim/1')
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/claims/claim%2F1')
    })
  })

  describe('listGaps', () => {
    it('应调用 get 并传入正确的 URL', () => {
      knowledgeApi.listGaps('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/gaps')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      knowledgeApi.listGaps('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 params 查询参数', () => {
      const params = { gapType: 'MISSING_EDGE', status: 'OPEN', pageNum: 1 }
      knowledgeApi.listGaps('proj-1', params)
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-1/knowledge/gaps', params)
    })

    it('未传 params 时第二参数为 undefined', () => {
      knowledgeApi.listGaps('proj-1')
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-1/knowledge/gaps', undefined)
    })
  })

  describe('resolveGap', () => {
    it('应调用 post 并传入正确的 URL', () => {
      knowledgeApi.resolveGap('proj-1', 'gap-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockPost.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/gaps/gap-1/resolve')
    })

    it('应使用 post HTTP 方法而非 get/put', () => {
      knowledgeApi.resolveGap('proj-1', 'gap-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应传递空对象作为请求体', () => {
      knowledgeApi.resolveGap('proj-1', 'gap-1')
      expect(mockPost).toHaveBeenCalledWith('/lg/projects/proj-1/knowledge/gaps/gap-1/resolve', {})
    })

    it('id 含特殊字符时应进行 URL encode', () => {
      knowledgeApi.resolveGap('proj-1', 'gap/1')
      expect(mockPost.mock.calls[0][0]).toBe('/lg/projects/proj-1/knowledge/gaps/gap%2F1/resolve')
    })
  })
})
