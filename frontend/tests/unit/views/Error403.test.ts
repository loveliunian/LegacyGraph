import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import Error403 from '@/views/error/403.vue'

describe('Error403 页面', () => {
  let router: any

  beforeEach(() => {
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/dashboard', name: 'Dashboard', component: { template: '<div>Dashboard</div>' } }
      ]
    })
  })

  it('应该正确渲染403错误页面', () => {
    const wrapper = mount(Error403, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-page').exists()).toBe(true)
  })

  it('应该显示403错误码', () => {
    const wrapper = mount(Error403, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-code').exists()).toBe(true)
    expect(wrapper.find('.error-code').text()).toBe('403')
  })

  it('应该包含操作按钮区域', () => {
    const wrapper = mount(Error403, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-actions').exists()).toBe(true)
  })
})
