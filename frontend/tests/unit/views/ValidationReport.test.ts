import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ValidationReport from '@/views/report/ValidationReport.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({}))
}))

describe('ValidationReport 页面', () => {
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

  it('应该正确渲染验证报告页面', () => {
    const wrapper = mount(ValidationReport, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.validation-report').exists()).toBe(true)
  })

  it('应该显示统计卡片区域', () => {
    const wrapper = mount(ValidationReport, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.stats-row').exists()).toBe(true)
  })

  it('应该包含版本选择下拉框', () => {
    const wrapper = mount(ValidationReport, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })
})
