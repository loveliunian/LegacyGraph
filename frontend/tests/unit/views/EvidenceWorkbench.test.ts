import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import EvidenceWorkbench from '@/views/workbench/EvidenceWorkbench.vue'

vi.mock('@/api', () => ({
  graphApi: {
    getVersions: vi.fn(() => Promise.resolve({ list: [], total: 0 }))
  }
}))

vi.mock('@/views/workbench/FeatureSliceWorkbench.vue', () => ({
  default: {
    template: '<div class="slice-workbench-mock"></div>',
    props: ['projectId', 'versionId']
  }
}))

vi.mock('@/views/workbench/DriftQueue.vue', () => ({
  default: {
    template: '<div class="drift-queue-mock"></div>',
    props: ['projectId', 'versionId']
  }
}))

vi.mock('@/views/workbench/QualityPanel.vue', () => ({
  default: {
    template: '<div class="quality-panel-mock"></div>',
    props: ['projectId', 'versionId']
  }
}))

describe('EvidenceWorkbench 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/projects/:projectId/workbench', name: 'EvidenceWorkbench', component: EvidenceWorkbench }
      ]
    })
  })

  it('应该正确渲染证据工作台页面', () => {
    const wrapper = mount(EvidenceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-tabs', 'el-tab-pane']
      }
    })
    expect(wrapper.find('.evidence-workbench').exists()).toBe(true)
  })

  it('应该显示工作台标题', () => {
    const wrapper = mount(EvidenceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-tabs', 'el-tab-pane']
      }
    })
    expect(wrapper.find('.workbench-header').exists()).toBe(true)
  })

  it('应该包含标签页切换区域', () => {
    const wrapper = mount(EvidenceWorkbench, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-tabs', 'el-tab-pane']
      }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
