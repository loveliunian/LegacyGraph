import { describe, it, expect, beforeEach, vi } from 'vitest'
import { hasPermission, hasAnyPermission, hasAllPermissions } from '@/utils/permission'

// Mock useUserStore
const mockHasPermission = vi.fn()

vi.mock('@/stores/user', () => ({
  useUserStore: vi.fn(() => ({
    hasPermission: mockHasPermission
  }))
}))

describe('permission 工具', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('hasPermission', () => {
    it('有权限时应该返回 true', () => {
      mockHasPermission.mockReturnValue(true)

      expect(hasPermission('user:create')).toBe(true)
    })

    it('无权限时应该返回 false', () => {
      mockHasPermission.mockReturnValue(false)

      expect(hasPermission('user:delete')).toBe(false)
    })
  })

  describe('hasAnyPermission', () => {
    it('有任意一个权限时应该返回 true', () => {
      mockHasPermission.mockImplementation((perm: string) => {
        return perm === 'user:view'
      })

      expect(hasAnyPermission(['user:create', 'user:view'])).toBe(true)
    })

    it('所有权限都没有时应该返回 false', () => {
      mockHasPermission.mockReturnValue(false)

      expect(hasAnyPermission(['user:create', 'user:delete'])).toBe(false)
    })

    it('权限列表为空时应该返回 false', () => {
      mockHasPermission.mockReturnValue(false)

      expect(hasAnyPermission([])).toBe(false)
    })
  })

  describe('hasAllPermissions', () => {
    it('拥有所有权限时应该返回 true', () => {
      mockHasPermission.mockReturnValue(true)

      expect(hasAllPermissions(['user:create', 'user:delete'])).toBe(true)
    })

    it('缺少任意一个权限时应该返回 false', () => {
      mockHasPermission.mockImplementation((perm: string) => {
        return perm === 'user:view'
      })

      expect(hasAllPermissions(['user:view', 'user:delete'])).toBe(false)
    })

    it('权限列表为空时应该返回 true', () => {
      expect(hasAllPermissions([])).toBe(true)
    })
  })
})
