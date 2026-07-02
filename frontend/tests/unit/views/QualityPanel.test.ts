import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import QualityPanel from '@/views/workbench/QualityPanel.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('QualityPanel 页面', () => {
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

  it('应该正确渲染质量面板页面', () => {
    const wrapper = mount(QualityPanel, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-tag']
      }
    })
    expect(wrapper.find('.quality-panel').exists()).toBe(true)
  })

  it('应该包含统计卡片区域', () => {
    const wrapper = mount(QualityPanel, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-tag']
      }
    })
    expect(wrapper.find('.stats-grid').exists()).toBe(true)
  })

  it('应该渲染有效组件', () => {
    const wrapper = mount(QualityPanel, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-tag']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
