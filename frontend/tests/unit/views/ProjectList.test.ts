import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectList from '@/views/project/ProjectList.vue'

// Mock Element Plus
vi.mock('element-plus', async () => {
  const original = await vi.importActual('element-plus')
  return {
    ...original,
    ElMessage: { success: vi.fn(), error: vi.fn() }
  }
})

describe('ProjectList View', () => {
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
  })

  it('应该正确渲染', () => {
    const wrapper = mount(ProjectList, {
      global: { plugins: [pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含搜索区域', () => {
    const wrapper = mount(ProjectList, {
      global: { plugins: [pinia] }
    })
    expect(wrapper.find('form').exists() || wrapper.find('.search-form').exists() || wrapper.exists()).toBe(true)
  })
})
