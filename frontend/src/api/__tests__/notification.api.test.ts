import { beforeEach, describe, expect, it, vi } from 'vitest'
import { notificationApi } from '../notification.api'

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

describe('notificationApi', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockPut.mockReset()
    mockGet.mockResolvedValue({})
    mockPut.mockResolvedValue({})
  })

  describe('getRecent', () => {
    it('应调用 get 并传入正确的 URL', () => {
      notificationApi.getRecent('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/notifications/recent')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      notificationApi.getRecent('proj-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 projectId 和 limit 参数', () => {
      notificationApi.getRecent('proj-1', 50)
      expect(mockGet).toHaveBeenCalledWith('/lg/notifications/recent', {
        projectId: 'proj-1',
        limit: 50,
      })
    })

    it('limit 默认值应为 20', () => {
      notificationApi.getRecent('proj-1')
      expect(mockGet).toHaveBeenCalledWith('/lg/notifications/recent', {
        projectId: 'proj-1',
        limit: 20,
      })
    })
  })

  describe('markRead', () => {
    it('应调用 put 并传入正确的 URL', () => {
      notificationApi.markRead('notif-1')
      expect(mockPut).toHaveBeenCalled()
      expect(mockPut.mock.calls[0][0]).toBe('/lg/notifications/notif-1/read')
    })

    it('应使用 put HTTP 方法而非 get/post', () => {
      notificationApi.markRead('notif-1')
      expect(mockPut).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
    })

    it('应只传 URL 一个参数（无请求体）', () => {
      notificationApi.markRead('notif-1')
      expect(mockPut).toHaveBeenCalledWith('/lg/notifications/notif-1/read')
    })

    it('应对 id 中的特殊字符进行 URL encode', () => {
      notificationApi.markRead('notif 1/2')
      expect(mockPut.mock.calls[0][0]).toBe('/lg/notifications/notif%201%2F2/read')
    })
  })
})
