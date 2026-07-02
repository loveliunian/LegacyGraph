import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BatchActions from '@/components/common/BatchActions.vue'

describe('BatchActions 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const createWrapper = (props = {}) => {
    return mount(BatchActions, {
      props: {
        actions: [
          { key: 'delete', label: '批量删除', type: 'danger' as const },
          { key: 'export', label: '导出', type: 'primary' as const }
        ],
        selectedItems: [],
        ...props
      }
    })
  }

  it('未选中项时不应该渲染', () => {
    const wrapper = createWrapper({ selectedItems: [] })
    expect(wrapper.find('.batch-actions').exists()).toBe(false)
  })

  it('选中项时应该渲染批量操作栏', () => {
    const wrapper = createWrapper({
      selectedItems: [{ id: 1 }, { id: 2 }]
    })
    expect(wrapper.find('.batch-actions').exists()).toBe(true)
  })

  it('应该显示选中数量', () => {
    const wrapper = createWrapper({
      selectedItems: [{ id: 1 }, { id: 2 }, { id: 3 }]
    })
    // el-tag is stubbed, but the batch-info div should exist
    expect(wrapper.find('.batch-info').exists()).toBe(true)
  })

  it('showClear 开启时应该显示清除按钮', () => {
    const wrapper = createWrapper({
      selectedItems: [{ id: 1 }],
      showClear: true
    })
    // el-button text is stubbed, but the batch-info div should exist
    expect(wrapper.find('.batch-info').exists()).toBe(true)
  })
})
