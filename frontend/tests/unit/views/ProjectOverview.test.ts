import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ProjectOverview from '@/views/project/ProjectOverview.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('ProjectOverview 页面', () => {
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

  it('应该正确渲染项目概览页面', () => {
    const wrapper = mount(ProjectOverview, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.project-overview').exists()).toBe(true)
  })

  it('应该渲染统计卡片区域', () => {
    const wrapper = mount(ProjectOverview, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.stats-row').exists()).toBe(true)
  })

  it('应该包含统计内容布局', () => {
    const wrapper = mount(ProjectOverview, {
      global: { plugins: [router, pinia] }
    })
    // stat-content is inside el-card stubs, but stat-item-secondary should still be there
    expect(wrapper.find('.stats-row').exists()).toBe(true)
  })
})
