import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock @/api (authApi)
vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(() => Promise.resolve({
      accessToken: 'mock-access',
      refreshToken: 'mock-refresh',
      user: { username: 'admin', permissions: ['read'], roles: ['admin'] }
    })),
    logout: vi.fn(() => Promise.resolve()),
    getCurrentUser: vi.fn(() => Promise.resolve({ username: 'admin', permissions: ['read'], roles: ['admin'] }))
  }
}))

// Mock @/utils/dict
vi.mock('@/utils/dict', () => ({
  loadAllDicts: vi.fn(() => Promise.resolve()),
  clearDictCache: vi.fn()
}))

// Mock pinia-plugin-persistedstate
vi.mock('pinia-plugin-persistedstate', () => ({
  default: () => () => {}
}))

import { useUserStore } from '@/stores/user'

describe('User Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should initialize with default values', () => {
    const store = useUserStore()
    expect(store.accessToken).toBe('')
    expect(store.userInfo).toBeNull()
    expect(store.permissions).toEqual([])
  })

  it('should clear auth correctly', () => {
    const store = useUserStore()
    store.setTokens('test-token', 'refresh-token')
    expect(store.accessToken).toBe('test-token')
    store.clearAuth()
    expect(store.accessToken).toBe('')
    expect(store.userInfo).toBeNull()
    expect(store.permissions).toEqual([])
  })

  it('should check permissions correctly', () => {
    const store = useUserStore()
    expect(store.hasPermission('read')).toBe(false)
  })

  it('should not have permission when permissions is empty', () => {
    const store = useUserStore()
    expect(store.hasPermission('any-permission')).toBe(false)
  })
})
