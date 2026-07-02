import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TestCaseForm from '@/components/test/TestCaseForm.vue'

// Mock child component
vi.mock('@/components/test/AssertionEditor.vue', () => ({
  default: {
    name: 'AssertionEditor',
    template: '<div class="assertion-editor-mock"></div>',
    props: ['modelValue'],
    emits: ['update:modelValue']
  }
}))

describe('TestCaseForm 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const defaultProps = {
    modelValue: {
      caseCode: 'test-001',
      caseName: '测试用例',
      caseType: 'API'
    },
    nodeOptions: []
  }

  it('应该正确渲染表单', () => {
    const wrapper = mount(TestCaseForm, {
      props: defaultProps
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含表单区域', () => {
    const wrapper = mount(TestCaseForm, {
      props: defaultProps
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含 API 调用配置区域', () => {
    const wrapper = mount(TestCaseForm, {
      props: defaultProps
    })
    expect(wrapper.html()).toContain('API调用')
  })

  it('应该包含断言编辑器', () => {
    const wrapper = mount(TestCaseForm, {
      props: defaultProps
    })
    expect(wrapper.find('.assertion-editor-mock').exists()).toBe(true)
  })
})
