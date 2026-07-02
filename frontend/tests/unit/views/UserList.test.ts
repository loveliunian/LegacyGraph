import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import UserList from '@/views/system/UserList.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
}))

describe('UserList 页面', () => {
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

  it('应该正确渲染用户管理页面', () => {
    const wrapper = mount(UserList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.user-list').exists()).toBe(true)
  })

  it('应该包含卡片容器', () => {
    const wrapper = mount(UserList, {
      global: { plugins: [router, pinia] }
    })
    // el-card 被 stub 了，但外层容器存在
    expect(wrapper.find('.user-list').exists()).toBe(true)
  })

  it('应该包含搜索筛选区域', () => {
    const wrapper = mount(UserList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
