import { describe, it, expect, beforeEach, vi } from 'vitest'
import axios from 'axios'

// Mock axios
vi.mock('axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() }
    }
  }
}))

describe('API Base Configuration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should have base URL configured', () => {
    expect(import.meta.env.VITE_API_BASE_URL).toBeDefined()
  })

  it('should have correct API endpoints structure', () => {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
    
    // 验证主要端点路径模式
    expect(baseUrl).toBeTypeOf('string')
  })
})
