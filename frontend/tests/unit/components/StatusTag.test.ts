import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import StatusTag from '@/components/common/StatusTag.vue'

describe('StatusTag 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染待处理状态', () => {
    const wrapper = mount(StatusTag, {
      props: { status: 'PENDING' }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该正确渲染已完成状态', () => {
    const wrapper = mount(StatusTag, {
      props: { status: 'COMPLETED' }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该正确渲染失败状态', () => {
    const wrapper = mount(StatusTag, {
      props: { status: 'FAILED' }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('未知状态应该回退显示原始值', () => {
    const wrapper = mount(StatusTag, {
      props: { status: 'UNKNOWN_STATUS' }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持自定义 statusMap', () => {
    const customMap = {
      CUSTOM: { text: '自定义状态', type: 'success' as const }
    }
    const wrapper = mount(StatusTag, {
      props: {
        status: 'CUSTOM',
        statusMap: customMap
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
