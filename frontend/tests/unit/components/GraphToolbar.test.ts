import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'

describe('GraphToolbar 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染工具栏容器', () => {
    const wrapper = mount(GraphToolbar)
    expect(wrapper.find('.graph-toolbar').exists()).toBe(true)
  })

  it('应该渲染缩放按钮组', () => {
    const wrapper = mount(GraphToolbar)
    expect(wrapper.find('.toolbar-group').exists()).toBe(true)
  })

  it('应该显示缩放百分比', () => {
    const wrapper = mount(GraphToolbar)
    expect(wrapper.find('.zoom-level').exists()).toBe(true)
  })

  it('应该渲染布局切换按钮组', () => {
    const wrapper = mount(GraphToolbar)
    // 应该包含多个按钮组
    const groups = wrapper.findAll('.toolbar-group')
    expect(groups.length).toBeGreaterThanOrEqual(3)
  })
})
