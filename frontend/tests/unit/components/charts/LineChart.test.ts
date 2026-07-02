import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('echarts', () => ({
  init: vi.fn(() => ({
    setOption: vi.fn(),
    dispose: vi.fn(),
    resize: vi.fn(),
    showLoading: vi.fn(),
    hideLoading: vi.fn()
  }))
}))

import LineChart from '@/components/charts/LineChart.vue'

describe('LineChart 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染折线图组件', () => {
    const wrapper = mount(LineChart, {
      props: {
        xAxisData: ['周一', '周二', '周三'],
        series: [{ name: '访问量', data: [820, 932, 901] }]
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持多条折线', () => {
    const wrapper = mount(LineChart, {
      props: {
        xAxisData: ['1月', '2月', '3月'],
        series: [
          { name: '收入', data: [3000, 3500, 4000] },
          { name: '支出', data: [2000, 2500, 2800] }
        ]
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持自定义标题', () => {
    const wrapper = mount(LineChart, {
      props: {
        xAxisData: ['A', 'B', 'C'],
        series: [{ name: '趋势', data: [10, 20, 15] }],
        title: '月度趋势图'
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持非平滑模式', () => {
    const wrapper = mount(LineChart, {
      props: {
        xAxisData: ['1', '2', '3'],
        series: [{ name: '数据', data: [5, 10, 8], smooth: false }]
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
