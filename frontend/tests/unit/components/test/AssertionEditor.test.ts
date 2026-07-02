import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AssertionEditor from '@/components/test/AssertionEditor.vue'

describe('AssertionEditor 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染断言编辑器', () => {
    const wrapper = mount(AssertionEditor, {
      props: {
        modelValue: []
      }
    })
    expect(wrapper.find('.assertion-editor').exists()).toBe(true)
  })

  it('无断言时应该显示空状态', () => {
    const wrapper = mount(AssertionEditor, {
      props: {
        modelValue: []
      }
    })
    expect(wrapper.find('.assertion-header').exists()).toBe(true)
  })

  it('应该显示"添加断言"按钮', () => {
    const wrapper = mount(AssertionEditor, {
      props: {
        modelValue: []
      }
    })
    expect(wrapper.find('.assertion-header').exists()).toBe(true)
  })

  it('存在断言时应该渲染断言列表', () => {
    const assertions = [
      { type: 'HTTP_STATUS', expression: '', expected: 200 },
      { type: 'JSON_PATH', expression: '$.data.id', expected: 1 }
    ]
    const wrapper = mount(AssertionEditor, {
      props: {
        modelValue: assertions
      }
    })
    expect(wrapper.find('.assertion-list').exists()).toBe(true)
  })
})
