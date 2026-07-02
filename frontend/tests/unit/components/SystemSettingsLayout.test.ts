import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const mockPush = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({
    name: 'SystemDictionaryList'
  }),
  useRouter: () => ({
    push: mockPush
  })
}))

import SystemSettingsLayout from '@/components/SystemSettingsLayout.vue'

describe('SystemSettingsLayout 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockPush.mockClear()
  })

  it('应该正确渲染系统管理布局', () => {
    const wrapper = mount(SystemSettingsLayout)
    expect(wrapper.find('.system-layout').exists()).toBe(true)
  })

  it('应该渲染系统管理标题', () => {
    const wrapper = mount(SystemSettingsLayout)
    expect(wrapper.find('.system-header').exists()).toBe(true)
  })

  it('应该渲染标签页容器', () => {
    const wrapper = mount(SystemSettingsLayout)
    expect(wrapper.find('.system-tabs').exists()).toBe(true)
  })

  it('应该渲染内容区域', () => {
    const wrapper = mount(SystemSettingsLayout)
    expect(wrapper.find('.system-content').exists()).toBe(true)
  })
})
