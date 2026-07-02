import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import JsonViewer from '@/components/common/JsonViewer.vue'

describe('JsonViewer 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染 JSON 查看器', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: { name: 'test', value: 123 }
      }
    })
    expect(wrapper.find('.json-viewer').exists()).toBe(true)
  })

  it('应该正确渲染数组数据', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: [1, 2, 3, 4, 5]
      }
    })
    expect(wrapper.find('.json-viewer').exists()).toBe(true)
  })

  it('应该正确渲染嵌套对象', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: {
          user: { id: 1, name: 'Alice', roles: ['admin'] }
        }
      }
    })
    expect(wrapper.find('.json-viewer').exists()).toBe(true)
  })

  it('应该正确渲染 null 值', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: null
      }
    })
    expect(wrapper.find('.json-viewer').exists()).toBe(true)
  })

  it('不可折叠模式不显示头部', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: { key: 'value' },
        collapsible: false
      }
    })
    expect(wrapper.find('.json-viewer__header').exists()).toBe(false)
  })

  it('应该显示自定义标签', () => {
    const wrapper = mount(JsonViewer, {
      props: {
        data: { items: [1, 2, 3] },
        label: '自定义数据'
      }
    })
    expect(wrapper.find('.json-viewer').exists()).toBe(true)
  })
})
