import { vi } from 'vitest'
import { config } from '@vue/test-utils'

// 设置环境变量
process.env.VITE_API_BASE_URL = 'http://localhost:8080/api'

globalThis.ResizeObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  unobserve: vi.fn(),
  disconnect: vi.fn()
}))

globalThis.IntersectionObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  unobserve: vi.fn(),
  disconnect: vi.fn()
}))

globalThis.PerformanceObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  disconnect: vi.fn()
}))

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn()
  }))
})

// Stub all common Element Plus components that might appear in tests
config.global.stubs = {
  teleport: true,
  'el-button': true,
  'el-card': true,
  'el-input': true,
  'el-form': true,
  'el-form-item': true,
  'el-table': true,
  'el-table-column': true,
  'el-pagination': true,
  'el-dialog': true,
  'el-dropdown': true,
  'el-dropdown-menu': true,
  'el-dropdown-item': true,
  'el-menu': true,
  'el-menu-item': true,
  'el-submenu': true,
  'el-tag': true,
  'el-badge': true,
  'el-alert': true,
  'el-loading': true,
  'v-loading': true,
  'el-message': true,
  'el-message-box': true,
  'el-notification': true,
  'el-select': true,
  'el-option': true,
  'el-checkbox': true,
  'el-radio': true,
  'el-switch': true,
  'el-date-picker': true,
  'el-time-picker': true,
  'el-upload': true,
  'el-progress': true,
  'el-divider': true,
  'el-icon': true,
  'el-row': true,
  'el-col': true,
  'el-space': true,
  'el-container': true,
  'el-header': true,
  'el-aside': true,
  'el-main': true,
  'el-footer': true,
  'el-drawer': true,
  'el-popover': true,
  'el-tooltip': true
}

// Mock i18n
config.global.plugins = []

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key
  })
}))
