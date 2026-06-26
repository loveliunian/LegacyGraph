import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUserStore } from '@/stores/user'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => { store[key] = value },
    removeItem: (key: string) => delete store[key],
    clear: () => { store = {} }
  }
})()

Object.defineProperty(window, 'localStorage', {
  value: localStorageMock
})

// Mock axios
vi.mock('axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

describe('User Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorageMock.clear()
  })

  it('should initialize with default values', () => {
    const store = useUserStore()
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.permissions).toEqual([])
  })

  it('should clear auth correctly', () => {
    const store = useUserStore()
    store.setTokens('test-token', 'refresh-token')
    expect(store.token).toBe('test-token')
    store.clearAuth()
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
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
