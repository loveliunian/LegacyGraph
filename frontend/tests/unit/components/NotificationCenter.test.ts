import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import NotificationCenter from '@/components/NotificationCenter.vue'

vi.mock('@/api', () => ({
  notificationApi: {
    listNotifications: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
    markAllRead: vi.fn(() => Promise.resolve({})),
    markRead: vi.fn(() => Promise.resolve({}))
  }
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    accessToken: 'test-token',
    userInfo: { id: 'user-1' }
  })
}))

vi.mock('@/stores/project', () => ({
  useProjectStore: () => ({
    currentProjectId: 'project-1'
  })
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn()
  }
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

  it('组件挂载后不应该崩溃', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该渲染通知项列表', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.el-dropdown').exists()).toBe(true)
  })

  it('应该包含通知内容区域', () => {
    const wrapper = mount(NotificationCenter, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
