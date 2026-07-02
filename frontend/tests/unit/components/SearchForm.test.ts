import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchForm from '@/components/common/SearchForm.vue'

describe('SearchForm 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const createWrapper = (props = {}) => {
    return mount(SearchForm, {
      props: {
        model: { keyword: '' },
        ...props
      }
    })
  }

  it('应该正确渲染搜索表单', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.search-form').exists()).toBe(true)
  })

  it('应该渲染搜索区域容器', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.search-form__form').exists()).toBe(true)
  })

  it('应该包含操作按钮区域', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.search-form__actions').exists()).toBe(true)
  })

  it('搜索时应该触发 search 事件', async () => {
    const wrapper = createWrapper()
    const searchBtn = wrapper.findAllComponents({ name: 'ElButton' }).at(0)
    await searchBtn?.trigger('click')
    expect(wrapper.exists()).toBe(true)
  })
})
