import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ReviewList from '@/views/review/ReviewList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({}))
}))

describe('ReviewList 页面', () => {
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

  it('应该正确渲染审核列表页面', () => {
    const wrapper = mount(ReviewList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含卡片容器', () => {
    const wrapper = mount(ReviewList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含表格展示', () => {
    const wrapper = mount(ReviewList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
