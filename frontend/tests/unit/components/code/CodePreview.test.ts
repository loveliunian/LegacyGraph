import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('highlight.js', () => ({
  default: {
    highlight: (code: string, options: any) => ({ value: code }),
    highlightAll: vi.fn()
  }
}))

import CodePreview from '@/components/code/CodePreview.vue'

describe('CodePreview 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染代码预览容器', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: 'console.log("hello")',
        language: 'javascript'
      }
    })
    expect(wrapper.find('.code-preview-container').exists()).toBe(true)
  })

  it('应该显示文件名和语言标签', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: 'SELECT * FROM users',
        fileName: 'query.sql',
        language: 'sql'
      }
    })
    expect(wrapper.find('.code-preview-container').exists()).toBe(true)
  })

  it('加载状态时应该显示骨架屏', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: '',
        loading: true
      }
    })
    expect(wrapper.find('.loading-overlay').exists()).toBe(true)
  })

  it('错误状态时应该显示空状态', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: '',
        error: true
      }
    })
    expect(wrapper.find('.error-state').exists()).toBe(true)
  })

  it('应该显示底部信息栏', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: 'test',
        showFooter: true,
        fileSize: 1024,
        encoding: 'UTF-8'
      }
    })
    expect(wrapper.find('.preview-footer').exists()).toBe(true)
  })

  it('隐藏头部时不显示头部', () => {
    const wrapper = mount(CodePreview, {
      props: {
        code: 'test',
        showHeader: false
      }
    })
    expect(wrapper.find('.preview-header').exists()).toBe(false)
  })
})
