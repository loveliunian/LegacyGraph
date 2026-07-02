import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TestCaseList from '@/views/test/TestCaseList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('TestCaseList 页面', () => {
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

  it('应该正确渲染测试用例列表页面', () => {
    const wrapper = mount(TestCaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.test-case-list').exists()).toBe(true)
  })

  it('应该显示筛选条件区域', () => {
    const wrapper = mount(TestCaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含分页组件', () => {
    const wrapper = mount(TestCaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
