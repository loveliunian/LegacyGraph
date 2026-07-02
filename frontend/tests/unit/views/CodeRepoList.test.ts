import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import CodeRepoList from '@/views/source/CodeRepoList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('CodeRepoList 页面', () => {
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

  it('应该正确渲染代码仓库列表页面', () => {
    const wrapper = mount(CodeRepoList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.repo-list').exists()).toBe(true)
  })

  it('应该包含页面标题', () => {
    const wrapper = mount(CodeRepoList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(CodeRepoList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
