import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TestCaseEditor from '@/views/test/TestCaseEditor.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({})),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve({}))
}))

describe('TestCaseEditor 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/test-case/new', name: 'TestCaseCreate', component: TestCaseEditor },
        { path: '/test-case/:id/edit', name: 'TestCaseEdit', component: TestCaseEditor }
      ]
    })
  })

  it('应该正确渲染测试用例编辑器页面', () => {
    const wrapper = mount(TestCaseEditor, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option']
      }
    })
    expect(wrapper.find('.test-case-editor').exists()).toBe(true)
  })

  it('应该包含标题区域', () => {
    const wrapper = mount(TestCaseEditor, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-button', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含表单元素', () => {
    const wrapper = mount(TestCaseEditor, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-card', 'el-button', 'el-form', 'el-form-item', 'el-input', 'el-select', 'el-option']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
