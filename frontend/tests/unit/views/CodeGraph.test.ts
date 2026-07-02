import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import CodeGraph from '@/views/graph/CodeGraph.vue'

vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    template: '<div class="vue-flow-mock"></div>',
    props: ['nodes', 'edges']
  }
}))

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('CodeGraph 页面', () => {
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

  it('应该正确渲染代码图谱页面', () => {
    const wrapper = mount(CodeGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-form', 'el-form-item', 'el-input', 'el-button']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含查询表单', () => {
    const wrapper = mount(CodeGraph, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-form', 'el-form-item', 'el-input', 'el-button']
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })
})
