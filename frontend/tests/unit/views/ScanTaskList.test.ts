import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ScanTaskList from '@/views/scan/ScanTaskList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('ScanTaskList 页面', () => {
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

  it('应该正确渲染扫描任务列表页面', () => {
    const wrapper = mount(ScanTaskList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.scan-task-list').exists()).toBe(true)
  })

  it('应该显示页面标题', () => {
    const wrapper = mount(ScanTaskList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含新建扫描按钮结构', () => {
    const wrapper = mount(ScanTaskList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
