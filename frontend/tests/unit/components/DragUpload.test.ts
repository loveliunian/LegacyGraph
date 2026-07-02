import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DragUpload from '@/components/upload/DragUpload.vue'

describe('DragUpload 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染上传区域', () => {
    const wrapper = mount(DragUpload)
    expect(wrapper.find('.drag-upload-container').exists()).toBe(true)
  })

  it('应该显示上传提示文字', () => {
    const wrapper = mount(DragUpload)
    expect(wrapper.find('.upload-text').exists()).toBe(true)
  })

  it('应该包含文件选择 input', () => {
    const wrapper = mount(DragUpload)
    expect(wrapper.find('.file-input').exists()).toBe(true)
  })

  it('showHint 为 true 时应该显示格式提示', () => {
    const wrapper = mount(DragUpload, {
      props: { showHint: true }
    })
    expect(wrapper.find('.upload-hint').exists()).toBe(true)
  })

  it('禁用状态应该有 disabled 类', () => {
    const wrapper = mount(DragUpload, {
      props: { disabled: true }
    })
    expect(wrapper.find('.drag-upload-container.disabled').exists()).toBe(true)
  })
})
