import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CodeViewer from '@/components/common/CodeViewer.vue'

describe('CodeViewer 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染代码查看器', () => {
    const wrapper = mount(CodeViewer, {
      props: {
        code: 'function hello() { return "world"; }',
        language: 'javascript'
      }
    })
    expect(wrapper.find('.code-viewer').exists()).toBe(true)
  })

  it('应该显示语言标签', () => {
    const wrapper = mount(CodeViewer, {
      props: {
        code: 'print("hello")',
        language: 'python'
      }
    })
    expect(wrapper.find('.code-viewer__language').exists()).toBe(true)
  })

  it('显示代码内容', () => {
    const wrapper = mount(CodeViewer, {
      props: {
        code: 'const x = 1;',
        language: 'javascript'
      }
    })
    expect(wrapper.find('pre code').exists()).toBe(true)
  })

  it('隐藏头部时不显示头部', () => {
    const wrapper = mount(CodeViewer, {
      props: {
        code: 'test',
        showHeader: false
      }
    })
    expect(wrapper.find('.code-viewer__header').exists()).toBe(false)
  })
})
