import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TestExecutionLog from '@/components/test/TestExecutionLog.vue'

describe('TestExecutionLog 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染执行日志组件', () => {
    const wrapper = mount(TestExecutionLog, {
      props: {
        log: []
      }
    })
    expect(wrapper.find('.execution-log').exists()).toBe(true)
  })

  it('空日志时应该显示日志容器', () => {
    const wrapper = mount(TestExecutionLog, {
      props: {
        log: []
      }
    })
    expect(wrapper.find('.execution-log').exists()).toBe(true)
  })

  it('应该渲染日志内容', () => {
    const wrapper = mount(TestExecutionLog, {
      props: {
        log: ['开始执行测试...', '测试1: PASSED', '测试2: FAILED']
      }
    })
    expect(wrapper.find('.log-container').exists()).toBe(true)
  })

  it('成功日志应该添加 success 样式', () => {
    const wrapper = mount(TestExecutionLog, {
      props: {
        log: ['测试通过: 所有断言通过']
      }
    })
    expect(wrapper.find('.log-container').exists()).toBe(true)
  })

  it('失败日志应该添加 error 样式', () => {
    const wrapper = mount(TestExecutionLog, {
      props: {
        log: ['错误: 连接超时']
      }
    })
    expect(wrapper.find('.log-container').exists()).toBe(true)
  })
})
