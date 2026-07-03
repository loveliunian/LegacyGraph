import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AgentHub from '@/views/agent/AgentHub.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  post: vi.fn(() => Promise.resolve({}))
}))

// 全局 stub element-plus 组件
const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot name="header" /><slot /></div>' },
  'el-collapse': { template: '<div><slot /></div>' },
  'el-collapse-item': { template: '<div><slot name="title" /><slot /></div>' },
  'el-button': true,
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>', props: ['type', 'size'] },
  'el-dialog': { template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>', props: ['modelValue'] },
  'el-icon': { template: '<span class="el-icon-stub"><slot /></span>' },
  'el-input': { template: '<input />', props: ['modelValue'] },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot name="label" /><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-descriptions': { template: '<div><slot /></div>' },
  'el-descriptions-item': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div class="el-alert-stub"><slot /></div>', props: ['title', 'type'] },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-divider': true,
  'el-col': { template: '<div><slot /></div>' },
  'el-row': { template: '<div><slot /></div>' },
}

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
        stubs: globalStubs
      }
    })
    expect(wrapper.find('.agent-page').exists()).toBe(true)
  })

  it('应该显示AI助手标题区域', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: globalStubs
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该包含10个Agent能力卡片', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: globalStubs
      }
    })
    // 10 个 agent-card + 可能的嵌套
    const cards = wrapper.findAll('.agent-card')
    expect(cards.length).toBe(10)
  })

  it('使用指引折叠面板存在', () => {
    const wrapper = mount(AgentHub, {
      global: {
        plugins: [router, pinia],
        stubs: globalStubs
      }
    })
    expect(wrapper.find('.help-collapse').exists()).toBe(true)
  })
})
