import { beforeEach, vi } from 'vitest'
import { config } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

config.global.renderStubDefaultSlot = false

beforeEach(() => {
  setActivePinia(createPinia())
})

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

globalThis.EventSource = vi.fn().mockImplementation(() => ({
  addEventListener: vi.fn(),
  close: vi.fn(),
  onerror: null
})) as any

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
const renderDefault = { template: '<div><slot /></div>' }

config.global.stubs = {
  teleport: true,
  'el-button': { template: '<button class="el-button" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>' },
  'el-button-group': renderDefault,
  'el-card': { template: '<section class="el-card"><slot name="header" /><slot /></section>' },
  'el-input': { template: '<input class="el-input" />' },
  'el-form': { template: '<form class="el-form"><slot /></form>' },
  'el-form-item': { template: '<div class="el-form-item"><slot /></div>' },
  'el-table': { template: '<div class="el-table"><slot /></div>' },
  'el-table-column': true,
  'el-pagination': renderDefault,
  'el-dialog': { template: '<div class="el-dialog"><slot /><slot name="footer" /></div>' },
  'el-dropdown': { template: '<div class="el-dropdown"><slot /><slot name="dropdown" /></div>' },
  'el-dropdown-menu': renderDefault,
  'el-dropdown-item': renderDefault,
  'el-menu': renderDefault,
  'el-menu-item': renderDefault,
  'el-submenu': renderDefault,
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-badge': { template: '<span class="el-badge"><slot /></span>' },
  'el-alert': true,
  'el-loading': true,
  'v-loading': true,
  'el-message': true,
  'el-message-box': true,
  'el-notification': true,
  'el-select': renderDefault,
  'el-option': renderDefault,
  'el-checkbox': renderDefault,
  'el-radio': renderDefault,
  'el-radio-button': renderDefault,
  'el-radio-group': renderDefault,
  'el-switch': true,
  'el-date-picker': true,
  'el-time-picker': true,
  'el-upload': true,
  'el-progress': true,
  'el-divider': renderDefault,
  'el-icon': { template: '<i class="el-icon"><slot /></i>' },
  'el-row': renderDefault,
  'el-col': renderDefault,
  'el-space': renderDefault,
  'el-container': renderDefault,
  'el-header': renderDefault,
  'el-aside': renderDefault,
  'el-main': renderDefault,
  'el-footer': renderDefault,
  'el-drawer': { template: '<div class="el-drawer"><slot /></div>' },
  'el-popover': renderDefault,
  'el-tooltip': renderDefault,
  'el-skeleton': true,
  'el-empty': true,
  'el-statistic': true,
  'el-descriptions': renderDefault,
  'el-descriptions-item': renderDefault,
  'el-avatar': true,
  'el-tabs': renderDefault,
  'el-tab-pane': renderDefault,
  'el-collapse': renderDefault,
  'el-collapse-item': renderDefault,
  'el-scrollbar': renderDefault,
  'el-config-provider': renderDefault,
  'el-text': renderDefault,
  'el-link': { template: '<a class="el-link"><slot /></a>' },
  'el-input-number': true,
  'el-slider': true,
  'el-checkbox-group': renderDefault
}

// Global mocks for $t (i18n) and common helpers
config.global.mocks = {
  $t: (key: string) => key,
  $i18n: { locale: 'zh-CN' }
}

// Mock i18n
config.global.plugins = []

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
    locale: { value: 'zh-CN' }
  }),
  createI18n: () => ({
    global: {
      t: (key: string) => key,
      locale: { value: 'zh-CN' }
    },
    install: vi.fn()
  })
}))
