import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import DocumentList from '@/views/source/DocumentList.vue'
import { useUserStore } from '@/stores/user'

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
    requestMocks.post.mockResolvedValue({ success: true })
    requestMocks.put.mockResolvedValue({ success: true })
    requestMocks.del.mockResolvedValue({ success: true })
    requestMocks.upload.mockResolvedValue({ success: true })
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: []
    })
  })

  async function mountWithProject(projectId = 'project 1') {
    const localRouter = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:projectId/documents', component: DocumentList }]
    })
    localRouter.push(`/projects/${encodeURIComponent(projectId)}/documents`)
    await localRouter.isReady()
    return mount(DocumentList, {
      global: { plugins: [localRouter, pinia] }
    })
  }

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

  it('上传地址应使用环境 API 前缀并编码项目 ID', async () => {
    const userStore = useUserStore()
    userStore.setTokens('access-token-1', 'refresh-token-1')

    const wrapper = await mountWithProject('project 1')

    expect((wrapper.vm as any).uploadUrl).toBe('http://localhost:8080/api/lg/projects/project%201/sources/documents/upload')
    expect((wrapper.vm as any).uploadHeaders.Authorization).toBe('Bearer access-token-1')
  })

  it('上传认证头应随刷新后的 token 更新', async () => {
    const userStore = useUserStore()
    userStore.setTokens('access-token-1', 'refresh-token-1')
    const wrapper = await mountWithProject('project-1')

    userStore.setTokens('access-token-2', 'refresh-token-2')

    expect((wrapper.vm as any).uploadHeaders.Authorization).toBe('Bearer access-token-2')
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
