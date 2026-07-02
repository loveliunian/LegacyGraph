import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import DriftQueue from '@/views/workbench/DriftQueue.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({}))
}))

describe('DriftQueue 页面', () => {
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

  it('应该正确渲染漂移队列页面', () => {
    const wrapper = mount(DriftQueue, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-table', 'el-table-column', 'el-tag', 'el-button', 'el-radio-group', 'el-radio-button']
      }
    })
    expect(wrapper.find('.drift-queue').exists()).toBe(true)
  })

  it('应该包含筛选栏', () => {
    const wrapper = mount(DriftQueue, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-table', 'el-table-column', 'el-tag', 'el-button', 'el-radio-group', 'el-radio-button']
      }
    })
    expect(wrapper.find('.filter-bar').exists()).toBe(true)
  })

  it('应该渲染有效组件', () => {
    const wrapper = mount(DriftQueue, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-table', 'el-table-column', 'el-tag', 'el-button', 'el-radio-group', 'el-radio-button']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
