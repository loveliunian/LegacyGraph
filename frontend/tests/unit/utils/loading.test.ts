import { describe, it, expect, vi } from 'vitest'

// Mock Element Plus loading
vi.mock('element-plus', () => ({
  ElLoading: {
    service: vi.fn().mockReturnValue({ close: vi.fn() })
  }
}))

describe('loading 加载工具', () => {
  it('应该导出 showLoading 函数', async () => {
    const mod = await import('@/utils/loading')
    expect(mod.showLoading).toBeDefined()
    expect(typeof mod.showLoading).toBe('function')
  })

  it('应该导出 hideLoading 函数', async () => {
    const mod = await import('@/utils/loading')
    expect(mod.hideLoading).toBeDefined()
    expect(typeof mod.hideLoading).toBe('function')
  })

  it('showLoading 应该调用 ElLoading.service', async () => {
    const { showLoading } = await import('@/utils/loading')
    const { ElLoading } = await import('element-plus')
    showLoading('测试加载中...')
    expect(ElLoading.service).toHaveBeenCalled()
  })
})
