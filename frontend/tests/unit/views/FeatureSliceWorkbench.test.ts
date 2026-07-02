import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import FeatureSliceWorkbench from '@/views/workbench/FeatureSliceWorkbench.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('FeatureSliceWorkbench 页面', () => {
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

  it('应该正确渲染功能切片工作台页面', () => {
    const wrapper = mount(FeatureSliceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-select', 'el-option', 'el-button', 'el-icon', 'el-table', 'el-table-column', 'el-tag', 'el-empty', 'el-card', 'el-descriptions', 'el-descriptions-item', 'el-progress']
      }
    })
    expect(wrapper.find('.slice-workbench').exists()).toBe(true)
  })

  it('应该包含工具栏', () => {
    const wrapper = mount(FeatureSliceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-select', 'el-option', 'el-button', 'el-icon', 'el-table', 'el-table-column', 'el-tag', 'el-empty', 'el-card', 'el-descriptions', 'el-descriptions-item', 'el-progress']
      }
    })
    expect(wrapper.find('.toolbar').exists()).toBe(true)
  })

  it('应该渲染有效组件', () => {
    const wrapper = mount(FeatureSliceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-select', 'el-option', 'el-button', 'el-icon', 'el-table', 'el-table-column', 'el-tag', 'el-empty', 'el-card', 'el-descriptions', 'el-descriptions-item', 'el-progress']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
