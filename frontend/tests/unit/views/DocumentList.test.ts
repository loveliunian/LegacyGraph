import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import DocumentList from '@/views/source/DocumentList.vue'

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  upload: vi.fn()
}))

vi.mock('@/utils/request', () => ({
  get: requestMocks.get,
  post: requestMocks.post,
  put: requestMocks.put,
  del: requestMocks.del,
  upload: requestMocks.upload
}))

describe('DocumentList 页面', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    vi.clearAllMocks()
    requestMocks.get.mockResolvedValue({ list: [], total: 0 })
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: []
    })
  })

  it('应该正确渲染文档列表页面', () => {
    const wrapper = mount(DocumentList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.document-list').exists()).toBe(true)
  })

  it('应该包含页面标题区域', () => {
    const wrapper = mount(DocumentList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.find('.page-header').exists()).toBe(true)
  })

  it('应该包含表格展示区域', () => {
    const wrapper = mount(DocumentList, {
      global: { plugins: [router, pinia] }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('解析接口返回业务失败时不应标记为已解析', async () => {
    requestMocks.post.mockResolvedValue({ success: false, message: '解析失败' })
    const wrapper = mount(DocumentList, {
      global: { plugins: [router, pinia] }
    })
    const row = {
      id: 'doc-1',
      docName: 'design.md',
      parseStatus: 'UPLOADED',
      factCount: 0
    }

    await (wrapper.vm as any).parseDoc(row)

    expect(row.parseStatus).toBe('PARSE_FAILED')
    expect(row.factCount).toBe(0)
  })
})
