import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectList from '@/views/project/ProjectList.vue'

// Mock Element Plus
vi.mock('element-plus', async () => {
  const original = await vi.importActual('element-plus')
  return {
    ...original,
    ElMessage: {
      success: vi.fn(),
      error: vi.fn()
    }
  }
})

describe('ProjectList View', () => {
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
  })

  it('should render correctly', () => {
    const wrapper = mount(ProjectList, {
      global: {
        plugins: [pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form']
      }
    })
    expect(wrapper.find('.project-list').exists()).toBe(true)
  })

  it('should have create project button', () => {
    const wrapper = mount(ProjectList, {
      global: {
        plugins: [pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form']
      }
    })
    expect(wrapper.find('.el-button').exists()).toBe(true)
  })

  it('should have table for projects', () => {
    const wrapper = mount(ProjectList, {
      global: {
        plugins: [pinia],
        stubs: ['el-button', 'el-table', 'el-table-column', 'el-input', 'el-dialog', 'el-form']
      }
    })
    expect(wrapper.find('.el-table').exists()).toBe(true)
  })
})
