import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import CreateScan from '@/views/scan/CreateScan.vue'

vi.mock('@/api', () => ({
  scanApi: {
    createScan: vi.fn(() => Promise.resolve({ id: 'scan-1' }))
  },
  sourceApi: {
    listRepos: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
    listDatabases: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
    listDocuments: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
  }
}))

vi.mock('@/utils/dict', () => ({
  preloadDicts: vi.fn(),
  dictLabel: vi.fn(() => '')
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn()
  }
}))

describe('CreateScan 页面', () => {
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

  it('应该正确渲染创建扫描页面', () => {
    const wrapper = mount(CreateScan, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.create-scan-dialog').exists()).toBe(true)
  })

  it('应该显示步骤条', () => {
    const wrapper = mount(CreateScan, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.step-card').exists()).toBe(true)
  })

  it('应该显示 h4 标题', () => {
    const wrapper = mount(CreateScan, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('h4').exists()).toBe(true)
  })
})
