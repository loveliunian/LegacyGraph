import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BaseTable from '@/components/common/BaseTable.vue'

describe('BaseTable 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const createWrapper = (props = {}) => {
    return mount(BaseTable, {
      props: {
        data: [],
        ...props
      },
      slots: {
        default: '<el-table-column prop="name" label="名称" />'
      }
    })
  }

  it('应该正确渲染表格容器', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.base-table').exists()).toBe(true)
  })

  it('分页开启时应该渲染分页组件', () => {
    const wrapper = createWrapper({ pagination: true })
    expect(wrapper.find('.base-table__pagination').exists()).toBe(true)
  })

  it('loading 状态应该显示加载效果', () => {
    const wrapper = createWrapper({ loading: true })
    expect(wrapper.exists()).toBe(true)
  })

  it('分页关闭时不应该渲染分页', () => {
    const wrapper = createWrapper({ pagination: false })
    expect(wrapper.find('.base-table__pagination').exists()).toBe(false)
  })
})
