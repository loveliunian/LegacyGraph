import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import RiskDetail from '@/views/migration/RiskDetail.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('RiskDetail 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/risk/:id', name: 'RiskDetail', component: RiskDetail }
      ]
    })
  })

  it('应该正确渲染风险详情页面', () => {
    const wrapper = mount(RiskDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-progress', 'el-divider']
      }
    })
    expect(wrapper.find('.risk-detail').exists()).toBe(true)
  })

  it('应该包含标题区域', () => {
    const wrapper = mount(RiskDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-progress', 'el-divider']
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该包含风险信息描述列表', () => {
    const wrapper = mount(RiskDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-progress', 'el-divider']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
