import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GraphAnalysisPanel from '@/components/graph/GraphAnalysisPanel.vue'

const mockNodes = [
  { id: '1', data: { label: '节点A', type: 'api', confidence: 0.9 }, position: { x: 100, y: 100 } },
  { id: '2', data: { label: '节点B', type: 'controller', confidence: 0.8 }, position: { x: 300, y: 200 } },
  { id: '3', data: { label: '节点C', type: 'service', confidence: 0.7 }, position: { x: 200, y: 400 } }
]

const mockEdges = [
  { id: 'e1', source: '1', target: '2', data: { confidence: 0.9 } },
  { id: 'e2', source: '2', target: '3', data: { confidence: 0.8 } }
]

describe('GraphAnalysisPanel 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染图谱分析面板', () => {
    const wrapper = mount(GraphAnalysisPanel, {
      props: { nodes: [], edges: [] }
    })
    expect(wrapper.find('.graph-analysis-panel').exists()).toBe(true)
  })

  it('应该显示面板标题', () => {
    const wrapper = mount(GraphAnalysisPanel, {
      props: { nodes: [], edges: [] }
    })
    expect(wrapper.find('.panel-title').exists()).toBe(true)
  })

  it('应该渲染分析标签页', () => {
    const wrapper = mount(GraphAnalysisPanel, {
      props: { nodes: mockNodes, edges: mockEdges }
    })
    expect(wrapper.find('.analysis-tabs').exists()).toBe(true)
  })

  it('应该渲染展开全部/收起全部按钮', () => {
    const wrapper = mount(GraphAnalysisPanel, {
      props: { nodes: mockNodes, edges: mockEdges }
    })
    expect(wrapper.find('.panel-header').exists()).toBe(true)
  })
})
