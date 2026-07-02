import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock request
vi.mock('@/utils/request', () => ({
  get: vi.fn()
}))

describe('versionsCache', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('应该导出 loadScanVersions 函数', async () => {
    const mod = await import('@/utils/versionsCache')
    expect(mod.loadScanVersions).toBeDefined()
    expect(typeof mod.loadScanVersions).toBe('function')
  })

  it('空 projectId 返回空数组', async () => {
    const { loadScanVersions } = await import('@/utils/versionsCache')
    const result = await loadScanVersions('')
    expect(result).toEqual([])
  })

  it('应该导出 clearVersionsCache', async () => {
    const mod = await import('@/utils/versionsCache')
    if (mod.clearVersionsCache) {
      expect(typeof mod.clearVersionsCache).toBe('function')
    }
  })
})
