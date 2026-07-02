import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import PromptList from '@/views/system/PromptList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve({})),
  del: vi.fn(() => Promise.resolve({}))
}))

describe('PromptList 页面', () => {
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

  it('应该正确渲染提示词模板管理页面', () => {
    const wrapper = mount(PromptList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.find('.prompt-mgr').exists()).toBe(true)
  })

  it('应该显示页面标题区域', () => {
    const wrapper = mount(PromptList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该显示 h3 标题', () => {
    const wrapper = mount(PromptList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-dialog', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option', 'el-icon']
      }
    })
    expect(wrapper.find('h3').exists()).toBe(true)
  })
})
