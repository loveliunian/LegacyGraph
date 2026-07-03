import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import GraphQa from '@/views/graph/GraphQa.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({})),
  post: vi.fn(() => Promise.resolve({}))
}))

const elementStubs = {
  'el-container': { template: '<div><slot /></div>' },
  'el-aside': { template: '<aside><slot /></aside>' },
  'el-main': { template: '<main><slot /></main>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': { template: '<input />' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
}

describe('GraphQa 页面', () => {
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

  it('应该正确渲染图谱问答页面', () => {
    const wrapper = mount(GraphQa, {
      global: {
        plugins: [router, pinia],
        stubs: elementStubs
      }
    })
    expect(wrapper.find('.graph-qa-page').exists()).toBe(true)
  })

  it('应该显示问答标题区域', () => {
    const wrapper = mount(GraphQa, {
      global: {
        plugins: [router, pinia],
        stubs: elementStubs
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该包含聊天容器', () => {
    const wrapper = mount(GraphQa, {
      global: {
        plugins: [router, pinia],
        stubs: elementStubs
      }
    })
    expect(wrapper.find('.chat-container').exists()).toBe(true)
  })
})
