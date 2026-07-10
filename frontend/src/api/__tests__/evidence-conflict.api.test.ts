import { beforeEach, describe, expect, it, vi } from 'vitest'
import { evidenceConflictApi } from '../evidence-conflict.api'

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

describe('evidenceConflictApi', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockPut.mockReset()
    mockGet.mockResolvedValue({})
    mockPost.mockResolvedValue({})
  })

  describe('list', () => {
    it('应调用 get 并传入正确的 URL', () => {
      evidenceConflictApi.list('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/evidence-conflicts')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      evidenceConflictApi.list('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 projectId 和 includeResolved 参数', () => {
      evidenceConflictApi.list('proj-1', true)
      expect(mockGet).toHaveBeenCalledWith('/lg/evidence-conflicts', {
        projectId: 'proj-1',
        includeResolved: true,
      })
    })

    it('includeResolved 默认值应为 false', () => {
      evidenceConflictApi.list('proj-1')
      expect(mockGet).toHaveBeenCalledWith('/lg/evidence-conflicts', {
        projectId: 'proj-1',
        includeResolved: false,
      })
    })
  })

  describe('resolve', () => {
    it('应调用 post 并传入正确的 URL', () => {
      evidenceConflictApi.resolve('conflict-1', 'RESOLVED')
      expect(mockPost).toHaveBeenCalled()
      expect(mockPost.mock.calls[0][0]).toBe('/lg/evidence-conflicts/conflict-1/resolve')
    })

    it('应使用 post HTTP 方法而非 get/put', () => {
      evidenceConflictApi.resolve('conflict-1', 'RESOLVED')
      expect(mockPost).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 resolution 参数作为请求体', () => {
      evidenceConflictApi.resolve('conflict-1', '已通过人工核对解决')
      expect(mockPost).toHaveBeenCalledWith('/lg/evidence-conflicts/conflict-1/resolve', {
        resolution: '已通过人工核对解决',
      })
    })

    it('应对 id 中的特殊字符进行 URL encode', () => {
      evidenceConflictApi.resolve('conflict 1/2', 'RESOLVED')
      expect(mockPost.mock.calls[0][0]).toBe('/lg/evidence-conflicts/conflict%201%2F2/resolve')
    })
  })
})
