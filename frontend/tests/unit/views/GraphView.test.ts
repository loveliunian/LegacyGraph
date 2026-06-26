import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import UnifiedGraph from '@/views/graph/UnifiedGraph.vue'

vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    template: '<div class="vue-flow-mock"></div>',
    props: ['nodes', 'edges', 'default-zoom']
  },
  Background: {
    template: '<div class="background-mock"></div>'
  },
  Controls: {
    template: '<div class="controls-mock"></div>'
  }
}))

describe('UnifiedGraph View', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { 
          path: '/projects/:id', 
          name: 'ProjectDetail', 
          component: { template: '<div>Detail</div>' },
          children: [
            { path: 'graph/unified', name: 'UnifiedGraph', component: UnifiedGraph }
          ]
        }
      ]
    })
  })

  it('should render correctly', () => {
    const wrapper = mount(UnifiedGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-select', 'el-option', 'el-slider', 'el-checkbox-group', 'el-checkbox', 'el-drawer']
      }
    })
    expect(wrapper.find('.unified-graph').exists()).toBe(true)
  })

  it('should have toolbar elements', () => {
    const wrapper = mount(UnifiedGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-select', 'el-option', 'el-slider', 'el-checkbox-group', 'el-checkbox', 'el-drawer']
      }
    })
    expect(wrapper.find('.graph-toolbar').exists()).toBe(true)
  })

  it('should have zoom controls', () => {
    const wrapper = mount(UnifiedGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-select', 'el-option', 'el-slider', 'el-checkbox-group', 'el-checkbox', 'el-drawer']
      }
    })
    expect(wrapper.find('.el-button').exists()).toBe(true)
  })
})
