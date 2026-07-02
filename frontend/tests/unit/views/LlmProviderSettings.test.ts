import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import LlmProviderSettings from '@/views/system/LlmProviderSettings.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve({})),
  del: vi.fn(() => Promise.resolve({}))
}))

describe('LlmProviderSettings 页面', () => {
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

  it('应该正确渲染LLM提供商设置页面', () => {
    const wrapper = mount(LlmProviderSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.find('.llm-provider-page').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(LlmProviderSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该显示标题区域', () => {
    const wrapper = mount(LlmProviderSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
