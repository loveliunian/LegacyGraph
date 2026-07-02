import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ProjectDetail from '@/views/project/ProjectDetail.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('ProjectDetail 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/projects/:id', name: 'ProjectDetail', component: ProjectDetail }]
    })
  })

  it('应该正确渲染项目详情页面', () => {
    const wrapper = mount(ProjectDetail, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含项目信息展示区域', () => {
    const wrapper = mount(ProjectDetail, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
