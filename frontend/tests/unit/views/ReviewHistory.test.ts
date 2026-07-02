import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ReviewHistory from '@/views/review/ReviewHistory.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('ReviewHistory 页面', () => {
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

  it('应该正确渲染审核历史页面', () => {
    const wrapper = mount(ReviewHistory, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.review-history').exists()).toBe(true)
  })

  it('应该显示页面标题区域', () => {
    const wrapper = mount(ReviewHistory, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该显示 h3 标题', () => {
    const wrapper = mount(ReviewHistory, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('h3').exists()).toBe(true)
  })
})
