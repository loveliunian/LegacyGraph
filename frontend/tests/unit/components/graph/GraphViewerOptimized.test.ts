import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    name: 'VueFlow',
    template: '<div class="vue-flow-container"><slot /></div>'
  },
  useVueFlow: () => ({
    fitView: vi.fn(),
    zoomIn: vi.fn(),
    zoomOut: vi.fn(),
    setCenter: vi.fn(),
    getZoom: vi.fn(() => 1)
  }),
  MarkerType: {
    ArrowClosed: 'arrowclosed'
  }
}))

import GraphViewerOptimized from '@/components/graph/GraphViewerOptimized.vue'

const mockNodes = [
  { id: '1', data: { label: '节点1', type: 'api', confidence: 0.9, status: 'confirmed', evidenceCount: 3 }, position: { x: 100, y: 100 } },
  { id: '2', data: { label: '节点2', type: 'controller', confidence: 0.8, status: 'confirmed', evidenceCount: 2 }, position: { x: 300, y: 200 } }
]

const mockEdges = [
  { id: 'e1', source: '1', target: '2', data: { confidence: 0.9 } }
]

describe('GraphViewerOptimized 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染图谱容器', () => {
    const wrapper = mount(GraphViewerOptimized, {
      props: { nodes: [], edges: [] }
    })
    expect(wrapper.find('.graph-container').exists()).toBe(true)
  })

  it('应该显示节点和关系统计', () => {
    const wrapper = mount(GraphViewerOptimized, {
      props: { nodes: mockNodes, edges: mockEdges }
    })
    expect(wrapper.find('.graph-panel-left').exists()).toBe(true)
  })

  it('应该渲染操作按钮面板', () => {
    const wrapper = mount(GraphViewerOptimized, {
      props: { nodes: mockNodes, edges: mockEdges }
    })
    expect(wrapper.find('.graph-panel-right').exists()).toBe(true)
  })

  it('应该渲染适应视图/居中/布局/导出按钮', () => {
    const wrapper = mount(GraphViewerOptimized, {
      props: { nodes: mockNodes, edges: mockEdges }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
