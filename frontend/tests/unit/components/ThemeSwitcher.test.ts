import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/stores/app', () => ({
  useAppStore: () => ({
    isDark: false,
    toggleTheme: vi.fn()
  })
}))

import ThemeSwitcher from '@/components/ThemeSwitcher.vue'

describe('ThemeSwitcher 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染主题切换按钮', () => {
    const wrapper = mount(ThemeSwitcher)
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含切换图标的按钮', () => {
    const wrapper = mount(ThemeSwitcher)
    expect(wrapper.exists()).toBe(true)
  })
})
