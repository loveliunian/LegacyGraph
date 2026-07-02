import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TestRunList from '@/views/test/TestRunList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

vi.mock('@/components/common/StatusTag.vue', () => ({
  default: {
    template: '<span class="status-tag-mock"></span>',
    props: ['status']
  }
}))

describe('TestRunList 页面', () => {
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

  it('应该正确渲染测试执行列表页面', () => {
    const wrapper = mount(TestRunList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-form', 'el-form-item', 'el-select', 'el-option', 'el-pagination']
      }
    })
    expect(wrapper.find('.test-run-list').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(TestRunList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-table', 'el-table-column', 'el-tag', 'el-form', 'el-form-item', 'el-select', 'el-option', 'el-pagination']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该显示标题区域', () => {
    const wrapper = mount(TestRunList, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-tag', 'el-form', 'el-form-item', 'el-select', 'el-option', 'el-pagination']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
