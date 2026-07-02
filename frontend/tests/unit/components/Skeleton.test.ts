import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Skeleton from '@/components/common/Skeleton.vue'

describe('Skeleton 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染骨架屏容器', () => {
    const wrapper = mount(Skeleton)
    expect(wrapper.find('.skeleton-container').exists()).toBe(true)
  })

  it('type=table 时应该渲染表格骨架', () => {
    const wrapper = mount(Skeleton, {
      props: { type: 'table', rows: 3, cols: 4 }
    })
    expect(wrapper.find('.skeleton-table').exists()).toBe(true)
  })

  it('type=card 时应该渲染卡片骨架', () => {
    const wrapper = mount(Skeleton, {
      props: { type: 'card' }
    })
    expect(wrapper.find('.skeleton-card').exists()).toBe(true)
  })

  it('type=list 时应该渲染列表骨架', () => {
    const wrapper = mount(Skeleton, {
      props: { type: 'list', rows: 3 }
    })
    expect(wrapper.find('.skeleton-list').exists()).toBe(true)
  })

  it('animated 属性应该控制动画效果', () => {
    const wrapper = mount(Skeleton, {
      props: { type: 'card', animated: true }
    })
    expect(wrapper.find('.skeleton-container.animated').exists()).toBe(true)
  })
})
