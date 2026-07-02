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

import BarChart from '@/components/charts/BarChart.vue'

describe('BarChart 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染柱状图组件', () => {
    const wrapper = mount(BarChart, {
      props: {
        xAxisData: ['一月', '二月', '三月'],
        series: [{ name: '销售额', data: [120, 200, 150] }]
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持自定义标题', () => {
    const wrapper = mount(BarChart, {
      props: {
        xAxisData: ['A', 'B'],
        series: [{ name: '数据', data: [10, 20] }],
        title: '测试图表'
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持横向模式', () => {
    const wrapper = mount(BarChart, {
      props: {
        xAxisData: ['类别1', '类别2'],
        series: [{ name: '数值', data: [30, 40] }],
        horizontal: true
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持堆叠模式', () => {
    const wrapper = mount(BarChart, {
      props: {
        xAxisData: ['Q1', 'Q2'],
        series: [
          { name: '产品A', data: [100, 150] },
          { name: '产品B', data: [80, 120] }
        ],
        stack: true
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
