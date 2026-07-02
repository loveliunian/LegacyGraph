import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import BusinessGraph from '@/views/graph/BusinessGraph.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

vi.mock('@/components/graph/GraphViewer.vue', () => ({
  default: {
    template: '<div class="graph-viewer-mock"></div>',
    props: ['nodes', 'edges', 'height', 'editable']
  }
}))

describe('BusinessGraph 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: []
    })
  })

  it('应该正确渲染业务图谱页面', () => {
    const wrapper = mount(BusinessGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-tree', 'el-tag', 'el-empty', 'el-descriptions', 'el-descriptions-item']
      }
    })
    expect(wrapper.find('.business-graph').exists()).toBe(true)
  })

  it('应该显示页面标题区域', () => {
    const wrapper = mount(BusinessGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-tree', 'el-tag', 'el-empty', 'el-descriptions', 'el-descriptions-item']
      }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该显示 h3 标题', () => {
    const wrapper = mount(BusinessGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-tree', 'el-tag', 'el-empty', 'el-descriptions', 'el-descriptions-item']
      }
    })
    expect(wrapper.find('h3').exists()).toBe(true)
  })
})
