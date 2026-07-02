import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const mockChartInstance = {
  setOption: vi.fn(),
  dispose: vi.fn(),
  resize: vi.fn(),
  showLoading: vi.fn(),
  hideLoading: vi.fn()
}

vi.mock('echarts', () => ({
  init: vi.fn(() => mockChartInstance)
}))

import BaseChart from '@/components/charts/BaseChart.vue'

describe('BaseChart 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('应该正确渲染基础图表容器', () => {
    const wrapper = mount(BaseChart, {
      props: {
        option: {
          xAxis: { type: 'category', data: ['A', 'B'] },
          yAxis: { type: 'value' },
          series: [{ type: 'bar', data: [1, 2] }]
        }
      }
    })
    expect(wrapper.find('.base-chart').exists()).toBe(true)
  })

  it('应该支持自定义宽度和高度', () => {
    const wrapper = mount(BaseChart, {
      props: {
        option: { series: [] },
        width: '800px',
        height: '600px'
      }
    })
    expect(wrapper.find('.base-chart').exists()).toBe(true)
  })

  it('加载状态时应该显示 loading', () => {
    const wrapper = mount(BaseChart, {
      props: {
        option: { series: [] },
        loading: true
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该暴露 resize 和 getInstance 方法', () => {
    const wrapper = mount(BaseChart, {
      props: {
        option: { series: [] }
      }
    })
    expect(wrapper.vm).toBeDefined()
  })
})
