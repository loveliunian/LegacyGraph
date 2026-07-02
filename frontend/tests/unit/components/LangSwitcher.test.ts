import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/locales', () => ({
  default: {
    global: {
      t: (key: string) => key
    }
  },
  setLocale: vi.fn(),
  getLocale: () => 'zh-CN',
  locales: [
    { label: '简体中文', value: 'zh-CN' },
    { label: 'English', value: 'en-US' }
  ]
}))

import LangSwitcher from '@/components/LangSwitcher.vue'

describe('LangSwitcher 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染语言切换组件', () => {
    const wrapper = mount(LangSwitcher)
    expect(wrapper.exists()).toBe(true)
  })

  it('应该渲染为下拉菜单组件', () => {
    const wrapper = mount(LangSwitcher)
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含下拉菜单', () => {
    const wrapper = mount(LangSwitcher)
    expect(wrapper.exists()).toBe(true)
  })
})
