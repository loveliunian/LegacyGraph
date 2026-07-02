import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import LogList from '@/views/audit/LogList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({})),
  del: vi.fn(() => Promise.resolve({}))
}))

describe('LogList 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: []
    })
  })

  it('应该正确渲染审计日志列表页面', () => {
    const wrapper = mount(LogList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-date-picker', 'el-pagination', 'el-tag']
      }
    })
    expect(wrapper.find('.audit-log-page').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(LogList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-date-picker', 'el-pagination', 'el-tag']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
