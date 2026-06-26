import { vi } from 'vitest'

// 设置环境变量
vi.stubGlobal('import.meta.env', {
  VITE_API_BASE_URL: '/api',
  VITE_APP_ENV: 'test'
})

// Mock localStorage
Object.defineProperty(window, 'localStorage', {
  value: {
    getItem: vi.fn(),
    setItem: vi.fn(),
    removeItem: vi.fn(),
    clear: vi.fn()
  }
})

// Mock Element Plus
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  },
  ElMessageBox: {
    alert: vi.fn().mockResolvedValue(true),
    confirm: vi.fn().mockResolvedValue(true),
    prompt: vi.fn().mockResolvedValue(true)
  }
}))

// Mock Vue Flow
vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    template: '<div class="vue-flow-mock"></div>',
    props: ['nodes', 'edges', 'default-zoom', 'min-zoom', 'max-zoom']
  },
  Background: {
    template: '<div class="background-mock"></div>'
  },
  Controls: {
    template: '<div class="controls-mock"></div>'
  }
}))

// Mock echarts
vi.mock('echarts', () => ({
  default: {
    init: vi.fn().mockReturnValue({
      setOption: vi.fn(),
      resize: vi.fn(),
      dispose: vi.fn()
    })
  }
}))

// Clean up after each test
afterEach(() => {
  vi.clearAllMocks()
})
