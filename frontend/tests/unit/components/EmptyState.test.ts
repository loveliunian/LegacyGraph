import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import EmptyState from '@/components/common/EmptyState.vue'

describe('EmptyState 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染空状态组件', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  it('应该显示默认描述文本', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('.empty-state__description').exists()).toBe(true)
  })

  it('应该支持自定义描述', () => {
    const wrapper = mount(EmptyState, {
      props: { description: '没有找到数据' }
    })
    expect(wrapper.find('.empty-state__description').exists()).toBe(true)
  })

  it('有 actions 时应该渲染操作区域', () => {
    const wrapper = mount(EmptyState, {
      props: {
        actions: [{ text: '添加', onClick: vi.fn() }]
      }
    })
    expect(wrapper.find('.empty-state__actions').exists()).toBe(true)
  })
})
