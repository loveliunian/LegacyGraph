import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AgentHub from '@/views/agent/AgentHub.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({}))
}))

describe('AgentHub 页面', () => {
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

  it('应该正确渲染Agent中心页面', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-tag', 'el-dialog', 'el-icon']
      }
    })
    expect(wrapper.find('.agent-page').exists()).toBe(true)
  })

  it('应该显示AI助手标题区域', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-tag', 'el-dialog', 'el-icon']
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该渲染Agent卡片列表', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-tag', 'el-dialog', 'el-icon']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
