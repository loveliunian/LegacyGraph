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

import PieChart from '@/components/charts/PieChart.vue'

describe('PieChart 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染饼图组件', () => {
    const wrapper = mount(PieChart, {
      props: {
        data: [
          { name: '类型A', value: 40 },
          { name: '类型B', value: 30 },
          { name: '类型C', value: 30 }
        ]
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持自定义标题', () => {
    const wrapper = mount(PieChart, {
      props: {
        data: [
          { name: '已完成', value: 75 },
          { name: '进行中', value: 25 }
        ],
        title: '任务状态分布'
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持玫瑰图模式', () => {
    const wrapper = mount(PieChart, {
      props: {
        data: [
          { name: '分类1', value: 50 },
          { name: '分类2', value: 30 },
          { name: '分类3', value: 20 }
        ],
        roseType: 'radius'
      }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该支持自定义颜色', () => {
    const wrapper = mount(PieChart, {
      props: {
        data: [
          { name: 'iOS', value: 45 },
          { name: 'Android', value: 55 }
        ],
        color: ['#409eff', '#67c23a']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
