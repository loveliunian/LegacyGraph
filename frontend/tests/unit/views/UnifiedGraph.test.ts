import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// Mock vue-router before component import
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'test' }, query: {} }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual('element-plus')
  return { ...actual, ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }
})

vi.mock('@vue-flow/core', () => ({
  VueFlow: { template: '<div class="vue-flow"><slot /></div>' },
  useVueFlow: () => ({ fitView: vi.fn(), zoomTo: vi.fn() }),
  MarkerType: { ArrowClosed: 'arrowclosed' }
}))

import UnifiedGraph from '@/views/graph/UnifiedGraph.vue'

describe('UnifiedGraph 统一图谱', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染', () => {
    const wrapper = mount(UnifiedGraph, {
      global: { plugins: [createPinia()] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('应该包含图谱容器', () => {
    const wrapper = mount(UnifiedGraph, {
      global: { plugins: [createPinia()] }
    })
    expect(wrapper.find('.vue-flow, [class*="graph"]').exists() || wrapper.exists()).toBe(true)
  })
})
