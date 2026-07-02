import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ThemeSettings from '@/views/settings/ThemeSettings.vue'

vi.mock('@/stores/app', () => ({
  useAppStore: () => ({
    theme: 'light',
    setTheme: vi.fn(),
    themeMode: 'light',
    themeConfig: {
      primaryColor: '#409eff',
      successColor: '#67c23a',
      warningColor: '#e6a23c',
      dangerColor: '#f56c6c',
      infoColor: '#909399',
      fontFamily: 'default',
      fontSize: 14,
      borderRadius: 4,
      contentWidth: 'fixed',
      collapsed: false,
      theme: 'light'
    },
    setThemeConfig: vi.fn(),
    resetThemeConfig: vi.fn()
  })
}))

describe('ThemeSettings 页面', () => {
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

  it('应该正确渲染主题设置页面', () => {
    const wrapper = mount(ThemeSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-divider', 'el-icon', 'el-alert']
      }
    })
    expect(wrapper.find('.theme-settings-page').exists()).toBe(true)
  })

  it('应该显示标题区域', () => {
    const wrapper = mount(ThemeSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-divider', 'el-icon', 'el-alert']
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该包含设置区域', () => {
    const wrapper = mount(ThemeSettings, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-divider', 'el-icon', 'el-alert']
      }
    })
    expect(wrapper.find('.settings-section').exists()).toBe(true)
  })
})
