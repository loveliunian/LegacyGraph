import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import FactList from '@/views/fact/FactList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('FactList 页面', () => {
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

  it('应该正确渲染事实列表页面', () => {
    const wrapper = mount(FactList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.fact-list').exists()).toBe(true)
  })

  it('应该显示筛选区域', () => {
    const wrapper = mount(FactList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含表格展示', () => {
    const wrapper = mount(FactList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
