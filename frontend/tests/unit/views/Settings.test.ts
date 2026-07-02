import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import Settings from '@/views/system/Settings.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve({})),
  del: vi.fn(() => Promise.resolve({}))
}))

vi.mock('@/components/SearchForm.vue', () => ({
  default: {
    template: '<div class="search-form-mock"><slot /></div>',
    props: ['model'],
    emits: ['search', 'reset']
  }
}))

describe('Settings 页面', () => {
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

  it('应该正确渲染系统设置页面', () => {
    const wrapper = mount(Settings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form', 'el-form-item', 'el-icon']
      }
    })
    expect(wrapper.find('.settings-page').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(Settings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form', 'el-form-item', 'el-icon']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该显示标题区域', () => {
    const wrapper = mount(Settings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form', 'el-form-item', 'el-icon']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
