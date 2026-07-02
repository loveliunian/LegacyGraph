import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'

vi.mock('@/stores/user', () => ({
  useUserStore: vi.fn(() => ({
    userInfo: { username: 'admin', nickname: '管理员' },
    hasAnyRole: vi.fn(() => true),
    logout: vi.fn(),
    accessToken: 'test-token'
  }))
}))

vi.mock('@/stores/app', () => ({
  useAppStore: vi.fn(() => ({
    isDark: false,
    toggleTheme: vi.fn()
  }))
}))

describe('AppLayout 组件', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/dashboard', name: 'Dashboard', component: { template: '<div>Dashboard</div>' } },
        { path: '/projects', name: 'Projects', component: { template: '<div>Projects</div>' } }
      ]
    })
  })

  it('应该正确渲染布局容器', () => {
    const wrapper = mount(AppLayout, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.app-layout').exists()).toBe(true)
  })

  it('应该包含 header 区域', () => {
    const wrapper = mount(AppLayout, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.app-header').exists()).toBe(true)
  })

  it('应该包含内容区域', () => {
    const wrapper = mount(AppLayout, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.app-content').exists()).toBe(true)
  })
})
