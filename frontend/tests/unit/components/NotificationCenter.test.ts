import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import NotificationCenter from '@/components/notification/NotificationCenter.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [] }))
}))

describe('NotificationCenter 组件', () => {
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

  it('应该正确渲染通知触发器容器', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.notification-trigger').exists()).toBe(true)
  })

  it('组件挂载后不应该崩溃', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该渲染 badge 组件', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.notification-badge').exists()).toBe(true)
  })
})
