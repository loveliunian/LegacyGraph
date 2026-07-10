import { beforeEach, describe, expect, it, vi } from 'vitest'
import { understandingApi } from '../understanding.api'

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

describe('understandingApi', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockPut.mockReset()
    mockGet.mockResolvedValue({})
    mockPost.mockResolvedValue({})
  })

  describe('getToolHealth', () => {
    it('应调用 get 并传入正确的 URL', () => {
      understandingApi.getToolHealth('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/understanding/tool-health')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      understandingApi.getToolHealth('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确拼接 projectId 到 URL', () => {
      understandingApi.getToolHealth('proj-2')
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-2/understanding/tool-health')
    })

    it('应对 projectId 中的特殊字符进行 URL encode', () => {
      understandingApi.getToolHealth('proj 1/2')
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj%201%2F2/understanding/tool-health')
    })
  })

  describe('createReport', () => {
    it('应调用 post 并传入正确的 URL', () => {
      const request = { question: '这个模块做什么的？' }
      understandingApi.createReport('proj-1', request)
      expect(mockPost).toHaveBeenCalled()
      expect(mockPost.mock.calls[0][0]).toBe('/lg/projects/proj-1/understanding/reports')
    })

    it('应使用 post HTTP 方法而非 get/put', () => {
      const request = { question: 'q' }
      understandingApi.createReport('proj-1', request)
      expect(mockPost).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 request 作为请求体', () => {
      const request = { question: '订单流程', reportType: 'OVERVIEW', format: 'MD' }
      understandingApi.createReport('proj-1', request)
      expect(mockPost).toHaveBeenCalledWith('/lg/projects/proj-1/understanding/reports', request)
    })

    it('projectId 含特殊字符时应进行 URL encode', () => {
      const request = { question: 'q' }
      understandingApi.createReport('proj 1', request)
      expect(mockPost.mock.calls[0][0]).toBe('/lg/projects/proj%201/understanding/reports')
    })
  })

  describe('getReport', () => {
    it('应调用 get 并传入正确的 URL', () => {
      understandingApi.getReport('proj-1', 'task-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/understanding/reports/task-1')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      understandingApi.getReport('proj-1', 'task-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确拼接 projectId 和 taskId 到 URL', () => {
      understandingApi.getReport('proj-2', 'task-9')
      expect(mockGet).toHaveBeenCalledWith('/lg/projects/proj-2/understanding/reports/task-9')
    })

    it('projectId/taskId 含特殊字符时应进行 URL encode', () => {
      understandingApi.getReport('proj/1', 'task 1')
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj%2F1/understanding/reports/task%201')
    })
  })

  describe('downloadReport', () => {
    it('应调用 get 并传入正确的 URL', () => {
      understandingApi.downloadReport('proj-1', 'task-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj-1/understanding/reports/task-1/download')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      understandingApi.downloadReport('proj-1', 'task-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应分别传递 format 参数和 responseType 配置', () => {
      understandingApi.downloadReport('proj-1', 'task-1')
      expect(mockGet).toHaveBeenCalledWith(
        '/lg/projects/proj-1/understanding/reports/task-1/download',
        { format: 'MD' },
        { responseType: 'blob' }
      )
    })

    it('projectId/taskId 含特殊字符时应进行 URL encode', () => {
      understandingApi.downloadReport('proj 1', 'task 1')
      expect(mockGet.mock.calls[0][0]).toBe('/lg/projects/proj%201/understanding/reports/task%201/download')
    })
  })
})
