import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import LogDetail from '@/views/audit/LogDetail.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

vi.mock('@element-plus/icons-vue', () => ({
  ArrowLeft: { template: '<i class="mock-icon"></i>' },
  Download: { template: '<i class="mock-icon"></i>' }
}))

describe('LogDetail 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/audit/:id', name: 'LogDetail', component: LogDetail }
      ]
    })
  })

  it('应该正确渲染审计日志详情页面', () => {
    const wrapper = mount(LogDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-avatar', 'el-divider']
      }
    })
    expect(wrapper.find('.log-detail-page').exists()).toBe(true)
  })

  it('应该包含返回按钮', () => {
    const wrapper = mount(LogDetail, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-descriptions', 'el-descriptions-item', 'el-tag', 'el-avatar', 'el-divider']
      }
    })
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })
})
