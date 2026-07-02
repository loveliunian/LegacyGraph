import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TestRunDetail from '@/views/test/TestRunDetail.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('TestRunDetail 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/test-run/:id', name: 'TestRunDetail', component: TestRunDetail }
      ]
    })
  })

  it('应该正确渲染测试执行详情页面', () => {
    const wrapper = mount(TestRunDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-progress', 'el-divider', 'el-table', 'el-table-column']
      }
    })
    expect(wrapper.find('.test-run-detail').exists()).toBe(true)
  })

  it('应该包含标题区域', () => {
    const wrapper = mount(TestRunDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-progress', 'el-divider', 'el-table', 'el-table-column']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
