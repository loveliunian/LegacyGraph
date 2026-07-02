import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import Error404 from '@/views/error/404.vue'

describe('Error404 页面', () => {
  let router: any

  beforeEach(() => {
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/dashboard', name: 'Dashboard', component: { template: '<div>Dashboard</div>' } }
      ]
    })
  })

  it('应该正确渲染404错误页面', () => {
    const wrapper = mount(Error404, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-page').exists()).toBe(true)
  })

  it('应该显示404错误码', () => {
    const wrapper = mount(Error404, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-code').exists()).toBe(true)
    expect(wrapper.find('.error-code').text()).toBe('404')
  })

  it('应该包含操作按钮区域', () => {
    const wrapper = mount(Error404, {
      global: {
        plugins: [router],
        stubs: ['el-button', 'el-icon'],
        mocks: { $t: (key: string) => key }
      }
    })
    expect(wrapper.find('.error-actions').exists()).toBe(true)
  })
})
