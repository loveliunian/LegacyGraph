import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/dashboard/Index.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('Dashboard 页面', () => {
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

  it('应该正确渲染工作台页面', () => {
    const wrapper = mount(Dashboard, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.dashboard-container').exists()).toBe(true)
  })

  it('应该包含统计卡片区域', () => {
    const wrapper = mount(Dashboard, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.stats-card').exists()).toBe(true)
  })

  it('应该包含统计项布局', () => {
    const wrapper = mount(Dashboard, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.stat-item').exists()).toBe(true)
  })
})
