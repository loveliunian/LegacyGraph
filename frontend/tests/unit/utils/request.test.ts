import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock dependencies before importing the module
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}))

vi.mock('@/stores/user', () => ({
  useUserStore: vi.fn(() => ({
    accessToken: 'test-access-token',
    refreshToken: 'test-refresh-token',
    clearAuth: vi.fn(),
    setTokens: vi.fn()
  }))
}))

vi.mock('@/utils/loading', () => ({
  showLoading: vi.fn(),
  hideLoading: vi.fn(),
  forceHideLoading: vi.fn()
}))

vi.mock('axios', async () => {
  const actual = await vi.importActual('axios')
  return {
    ...(actual as any),
    default: {
      create: vi.fn(() => ({
        interceptors: {
          request: { use: vi.fn() },
          response: { use: vi.fn() }
        },
        get: vi.fn(),
        post: vi.fn(),
        put: vi.fn(),
        delete: vi.fn()
      }))
    }
  }
})

describe('request 工具', () => {
  it('应该导出 axios 实例', async () => {
    const requestModule = await import('@/utils/request')
    expect(requestModule.default).toBeDefined()
  })

  it('应该导出 get 方法', async () => {
    const { get } = await import('@/utils/request')
    expect(typeof get).toBe('function')
  })

  it('应该导出 post 方法', async () => {
    const { post } = await import('@/utils/request')
    expect(typeof post).toBe('function')
  })

  it('应该导出 put 方法', async () => {
    const { put } = await import('@/utils/request')
    expect(typeof put).toBe('function')
  })

  it('应该导出 del 方法', async () => {
    const { del } = await import('@/utils/request')
    expect(typeof del).toBe('function')
  })

  it('应该导出 upload 方法', async () => {
    const { upload } = await import('@/utils/request')
    expect(typeof upload).toBe('function')
  })

  it('应该导出 downloadFile 方法', async () => {
    const { downloadFile } = await import('@/utils/request')
    expect(typeof downloadFile).toBe('function')
  })
})
