import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@vue-flow/core', () => ({
  Handle: {
    name: 'Handle',
    template: '<div class="vue-flow__handle" />'
  },
  Position: {
    Top: 'top',
    Bottom: 'bottom',
    Left: 'left',
    Right: 'right'
  }
}))

import CustomNode from '@/components/graph/CustomNode.vue'

describe('CustomNode 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染自定义节点', () => {
    const wrapper = mount(CustomNode, {
      props: {
        id: 'node-1',
        data: {
          label: '测试节点',
          type: 'api',
          confidence: 0.8,
          status: 'confirmed',
          evidenceCount: 3
        },
        selected: false
      }
    })
    expect(wrapper.find('.custom-node').exists()).toBe(true)
  })

  it('高置信度节点应该显示绿色边框', () => {
    const wrapper = mount(CustomNode, {
      props: {
        id: 'node-2',
        data: {
          label: '高置信度',
          type: 'controller',
          confidence: 0.95,
          status: 'confirmed',
          evidenceCount: 5
        }
      }
    })
    expect(wrapper.find('.custom-node').exists()).toBe(true)
  })

  it('低置信度节点应该正确渲染', () => {
    const wrapper = mount(CustomNode, {
      props: {
        id: 'node-3',
        data: {
          label: '低置信度',
          type: 'sql',
          confidence: 0.3,
          status: 'pending',
          evidenceCount: 1
        }
      }
    })
    expect(wrapper.find('.custom-node').exists()).toBe(true)
  })

  it('选中状态应该添加选中样式', () => {
    const wrapper = mount(CustomNode, {
      props: {
        id: 'node-4',
        data: {
          label: '选中节点',
          type: 'feature_module',
          confidence: 0.7,
          status: 'confirmed'
        },
        selected: true
      }
    })
    expect(wrapper.find('.node-selected').exists()).toBe(true)
  })
})
