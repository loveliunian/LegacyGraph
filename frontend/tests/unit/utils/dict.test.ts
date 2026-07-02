import { describe, it, expect, beforeEach, vi } from 'vitest'
import { loadAllDicts, dictLabel, dictMap, clearDictCache, preloadDicts } from '@/utils/dict'

// Mock request module
vi.mock('@/utils/request', () => ({
  get: vi.fn()
}))

import { get } from '@/utils/request'

describe('dict 工具', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearDictCache()
  })

  describe('loadAllDicts', () => {
    it('应该从后端加载字典数据', async () => {
      const mockData = {
        repo_type: { FULLSTACK: '全栈', BACKEND: '后端' },
        status: { ACTIVE: '启用', INACTIVE: '禁用' }
      }
      vi.mocked(get).mockResolvedValueOnce(mockData)

      await loadAllDicts()

      expect(get).toHaveBeenCalledWith('/lg/system/dicts/all/maps')
      expect(dictLabel('repo_type', 'FULLSTACK')).toBe('全栈')
    })

    it('已加载后不应该重复请求', async () => {
      vi.mocked(get).mockResolvedValueOnce({ repo_type: { TEST: '测试' } })

      await loadAllDicts()
      await loadAllDicts()
      await loadAllDicts()

      expect(get).toHaveBeenCalledTimes(1)
    })

    it('加载失败不应该抛异常', async () => {
      vi.mocked(get).mockRejectedValueOnce(new Error('Network Error'))

      await expect(loadAllDicts()).resolves.toBeUndefined()
    })
  })

  describe('dictLabel', () => {
    it('应该返回对应的标签文本', async () => {
      vi.mocked(get).mockResolvedValueOnce({
        repo_type: { FULLSTACK: '全栈' }
      })
      await loadAllDicts()

      expect(dictLabel('repo_type', 'FULLSTACK')).toBe('全栈')
    })

    it('字典编码不存在时应该返回原始值', () => {
      expect(dictLabel('nonexistent', 'foo')).toBe('foo')
    })

    it('值为空时应该返回 "-"', () => {
      expect(dictLabel('repo_type', '')).toBe('-')
    })

    it('值在字典中不存在时应该返回原始值', async () => {
      vi.mocked(get).mockResolvedValueOnce({
        repo_type: { FULLSTACK: '全栈' }
      })
      await loadAllDicts()

      expect(dictLabel('repo_type', 'UNKNOWN')).toBe('UNKNOWN')
    })
  })

  describe('dictMap', () => {
    it('应该返回字典映射表', async () => {
      const map = { BACKEND: '后端', FRONTEND: '前端' }
      vi.mocked(get).mockResolvedValueOnce({ repo_type: map })
      await loadAllDicts()

      expect(dictMap('repo_type')).toEqual(map)
    })

    it('字典不存在时应该返回空对象', () => {
      expect(dictMap('nonexistent')).toEqual({})
    })
  })

  describe('clearDictCache', () => {
    it('应该清空所有缓存', async () => {
      vi.mocked(get).mockResolvedValueOnce({ repo_type: { TEST: '测试' } })
      await loadAllDicts()
      expect(dictLabel('repo_type', 'TEST')).toBe('测试')

      clearDictCache()
      expect(dictLabel('repo_type', 'TEST')).toBe('TEST')
    })
  })

  describe('preloadDicts', () => {
    it('应该兼容旧调用不抛出错误', async () => {
      await expect(preloadDicts(['repo_type'])).resolves.toBeUndefined()
    })
  })
})
