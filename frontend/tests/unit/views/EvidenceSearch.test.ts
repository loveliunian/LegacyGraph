import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import EvidenceSearch from '@/views/fact/EvidenceSearch.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

vi.mock('@/utils/dict', () => ({
  dictLabel: vi.fn((_code: string, val: string) => val),
  loadAllDicts: vi.fn(() => Promise.resolve()),
  preloadDicts: vi.fn(() => Promise.resolve())
}))

vi.mock('@/utils', () => ({
  formatTime: vi.fn((val: string) => val)
}))

describe('EvidenceSearch 页面', () => {
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

  it('应该正确渲染证据检索页面', () => {
    const wrapper = mount(EvidenceSearch, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.evidence-search').exists()).toBe(true)
  })

  it('应该显示页面标题区域', () => {
    const wrapper = mount(EvidenceSearch, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该显示 h3 标题', () => {
    const wrapper = mount(EvidenceSearch, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('h3').exists()).toBe(true)
  })
})
