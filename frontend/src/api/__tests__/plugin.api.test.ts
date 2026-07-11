import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pluginApi } from '../plugin.api'

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

describe('pluginApi', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockPut.mockReset()
    mockGet.mockResolvedValue({})
    mockPost.mockResolvedValue({})
  })

  describe('listAll', () => {
    it('应调用 get 并传入正确的 URL', () => {
      pluginApi.listAll()
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/plugins')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      pluginApi.listAll()
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确传递 params 查询参数', () => {
      const params = { type: 'SCANNER' as const }
      pluginApi.listAll(params)
      expect(mockGet).toHaveBeenCalledWith('/lg/plugins', params)
    })

    it('未传 params 时第二参数为 undefined', () => {
      pluginApi.listAll()
      expect(mockGet).toHaveBeenCalledWith('/lg/plugins', undefined)
    })
  })

  describe('get', () => {
    it('应调用 get 并传入正确的 URL', () => {
      pluginApi.get('plugin-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockGet.mock.calls[0][0]).toBe('/lg/plugins/plugin-1')
    })

    it('应使用 get HTTP 方法而非 post/put', () => {
      pluginApi.get('plugin-1')
      expect(mockGet).toHaveBeenCalled()
      expect(mockPost).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应正确拼接 id 到 URL', () => {
      pluginApi.get('scanner-a')
      expect(mockGet).toHaveBeenCalledWith('/lg/plugins/scanner-a')
    })

    it('应对 id 中的特殊字符进行 URL encode', () => {
      pluginApi.get('plugin 1/2')
      expect(mockGet.mock.calls[0][0]).toBe('/lg/plugins/plugin%201%2F2')
    })
  })

  describe('enable', () => {
    it('应调用 post 并传入正确的 URL', () => {
      pluginApi.enable('plugin-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockPost.mock.calls[0][0]).toBe('/lg/plugins/plugin-1/enable')
    })

    it('应使用 post HTTP 方法而非 get/put', () => {
      pluginApi.enable('plugin-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应传递空对象作为请求体', () => {
      pluginApi.enable('plugin-1')
      expect(mockPost).toHaveBeenCalledWith('/lg/plugins/plugin-1/enable', {})
    })

    it('应对 id 中的特殊字符进行 URL encode', () => {
      pluginApi.enable('plugin 1/2')
      expect(mockPost.mock.calls[0][0]).toBe('/lg/plugins/plugin%201%2F2/enable')
    })
  })

  describe('disable', () => {
    it('应调用 post 并传入正确的 URL', () => {
      pluginApi.disable('plugin-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockPost.mock.calls[0][0]).toBe('/lg/plugins/plugin-1/disable')
    })

    it('应使用 post HTTP 方法而非 get/put', () => {
      pluginApi.disable('plugin-1')
      expect(mockPost).toHaveBeenCalled()
      expect(mockGet).not.toHaveBeenCalled()
      expect(mockPut).not.toHaveBeenCalled()
    })

    it('应传递空对象作为请求体', () => {
      pluginApi.disable('plugin-1')
      expect(mockPost).toHaveBeenCalledWith('/lg/plugins/plugin-1/disable', {})
    })

    it('应对 id 中的特殊字符进行 URL encode', () => {
      pluginApi.disable('plugin 1/2')
      expect(mockPost.mock.calls[0][0]).toBe('/lg/plugins/plugin%201%2F2/disable')
    })
  })
})
