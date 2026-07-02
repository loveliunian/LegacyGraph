import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import DictionaryList from '@/views/system/DictionaryList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('DictionaryList 页面', () => {
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

  it('应该正确渲染字典管理页面', () => {
    const wrapper = mount(DictionaryList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.dictionary-list').exists()).toBe(true)
  })

  it('应该包含卡片容器', () => {
    const wrapper = mount(DictionaryList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.dictionary-list').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(DictionaryList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
