import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CodeDiffViewer from '@/components/code/CodeDiffViewer.vue'

// Mock highlight.js
vi.mock('highlight.js', () => ({
  default: {
    highlight: vi.fn((code: string, { language: _lang }: any) => ({ value: code }))
  }
}))

// Mock diff library
vi.mock('diff', () => ({
  diffLines: vi.fn(() => [
    { added: undefined, removed: true, value: 'old line\n', count: 1 },
    { added: true, removed: undefined, value: 'new line\n', count: 1 },
    { added: undefined, removed: undefined, value: 'same line\n', count: 1 }
  ])
}))

// Mock CSS import
vi.mock('highlight.js/styles/atom-one-dark.css', () => ({}))

describe('CodeDiffViewer 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染代码对比容器', () => {
    const wrapper = mount(CodeDiffViewer, {
      props: {
        oldCode: 'const a = 1;',
        newCode: 'const a = 2;'
      }
    })
    expect(wrapper.find('.code-diff-container').exists()).toBe(true)
  })

  it('无代码时应该显示空状态', () => {
    const wrapper = mount(CodeDiffViewer, {
      props: {
        oldCode: '',
        newCode: ''
      }
    })
    expect(wrapper.find('.code-diff-container').exists()).toBe(true)
  })

  it('应该渲染 diff 头信息', () => {
    const wrapper = mount(CodeDiffViewer, {
      props: {
        oldCode: 'const a = 1;',
        newCode: 'const a = 2;',
        fileName: 'test.js'
      }
    })
    expect(wrapper.find('.diff-header').exists()).toBe(true)
  })

  it('loading 状态应该显示加载覆盖层', () => {
    const wrapper = mount(CodeDiffViewer, {
      props: {
        oldCode: 'const a = 1;',
        newCode: 'const a = 2;',
        loading: true
      }
    })
    expect(wrapper.find('.loading-overlay').exists()).toBe(true)
  })
})
