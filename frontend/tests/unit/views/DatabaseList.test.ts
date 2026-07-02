import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import DatabaseList from '@/views/source/DatabaseList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('DatabaseList 页面', () => {
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

  it('应该正确渲染数据库列表页面', () => {
    const wrapper = mount(DatabaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.database-list').exists()).toBe(true)
  })

  it('应该包含页面标题', () => {
    const wrapper = mount(DatabaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(DatabaseList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
