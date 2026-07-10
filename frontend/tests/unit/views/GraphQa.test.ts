import { describe, it, expect, beforeEach, vi, vitest } from 'vitest'
import { mount, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { ref, nextTick } from 'vue'
import GraphQa from '@/views/graph/GraphQa.vue'

vi.mock('@/utils/request', () => ({
  get: vi.fn(() => Promise.resolve({})),
  post: vi.fn(() => Promise.resolve({}))
}))

const mockAskStreamFetch = vi.fn()
const mockCreateConversation = vi.fn()
const mockGetMessages = vi.fn()
const mockListConversations = vi.fn()

vi.mock('@/api/qa.api', () => ({
  qaApi: {
    askStreamFetch: (...args: any[]) => mockAskStreamFetch(...args),
    createConversation: (...args: any[]) => mockCreateConversation(...args),
    getMessages: (...args: any[]) => mockGetMessages(...args),
    listConversations: (...args: any[]) => mockListConversations(...args),
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
  }
}))

const elementStubs = {
  'el-container': { template: '<div><slot /></div>' },
  'el-aside': { template: '<aside><slot /></aside>' },
  'el-main': { template: '<main><slot /></main>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-input': { template: '<input />' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-scrollbar': { template: '<div><slot /></div>' },
  'el-divider': { template: '<hr />' },
  'el-empty': { template: '<div class="el-empty"></div>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-dialog': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input-number': { template: '<input />' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio': { template: '<input type="radio" />' },
  'el-checkbox': { template: '<input type="checkbox" />' },
  'el-upload': { template: '<div><slot /></div>' },
  'el-progress': { template: '<div></div>' },
  'el-badge': { template: '<span><slot /></span>' },
  'el-avatar': { template: '<span><slot /></span>' },
  'el-dropdown': { template: '<div><slot /></div>' },
  'el-dropdown-menu': { template: '<div><slot /></div>' },
  'el-dropdown-item': { template: '<div><slot /></div>' },
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { template: '<div><slot /></div>' },
  'el-sub-menu': { template: '<div><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<div><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-pagination': { template: '<div></div>' },
  'el-popover': { template: '<div><slot /></div>' },
  'el-popconfirm': { template: '<div><slot /></div>' },
  'el-drawer': { template: '<div><slot /></div>' },
  'el-breadcrumb': { template: '<div><slot /></div>' },
  'el-breadcrumb-item': { template: '<span><slot /></span>' },
  'el-steps': { template: '<div><slot /></div>' },
  'el-step': { template: '<div><slot /></div>' },
  'el-timeline': { template: '<div><slot /></div>' },
  'el-timeline-item': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /></div>' },
  'el-notification': { template: '<div><slot /></div>' },
  'el-message-box': { template: '<div><slot /></div>' },
  'el-color-picker': { template: '<input />' },
  'el-date-picker': { template: '<input />' },
  'el-time-picker': { template: '<input />' },
  'el-cascader': { template: '<select><option></option></select>' },
  'el-transfer': { template: '<div><slot /></div>' },
  'el-tree': { template: '<div><slot /></div>' },
  'el-tree-select': { template: '<select><option></option></select>' },
  'el-virtual-list': { template: '<div><slot /></div>' },
  'el-infinite-scroll': { template: '<div><slot /></div>' },
  'el-image': { template: '<img />' },
  'el-carousel': { template: '<div><slot /></div>' },
  'el-carousel-item': { template: '<div><slot /></div>' },
  'el-collapses': { template: '<div><slot /></div>' },
  'el-collapse-panel': { template: '<div><slot /></div>' },
  'el-split': { template: '<div><slot /></div>' },
  'el-calendar': { template: '<div><slot /></div>' },
  'el-clock': { template: '<div><slot /></div>' },
  'el-watermark': { template: '<div><slot /></div>' },
  'el-affix': { template: '<div><slot /></div>' },
  'el-backtop': { template: '<div><slot /></div>' },
  'el-page-header': { template: '<div><slot /></div>' },
  'el-result': { template: '<div><slot /></div>' },
  'el-empty-state': { template: '<div><slot /></div>' },
  'el-skeleton': { template: '<div><slot /></div>' },
  'el-skeleton-item': { template: '<div></div>' },
  'el-descriptions': { template: '<div><slot /></div>' },
  'el-descriptions-item': { template: '<div><slot /></div>' },
  'el-statistic': { template: '<div><slot /></div>' },
  'el-countdown': { template: '<div><slot /></div>' },
}

function createWrapper(props = {}) {
  setActivePinia(createPinia())
  const pinia = createPinia()
  const router = createRouter({
    history: createWebHistory(),
    routes: []
  })
  return mount(GraphQa, {
    props: {
      projectId: 'test-project-1',
      ...props
    },
    global: {
      plugins: [router, pinia],
      stubs: elementStubs
    },
    attachTo: document.body
  })
}

describe('GraphQa 页面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListConversations.mockResolvedValue([])
    mockCreateConversation.mockResolvedValue({ id: 'conv-1' })
    mockGetMessages.mockResolvedValue([])
  })

  it('应该正确渲染图谱问答页面', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.graph-qa-page').exists()).toBe(true)
  })

  it('应该显示问答标题区域', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.card-header').exists()).toBe(true)
  })

  it('应该包含聊天容器', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.chat-container').exists()).toBe(true)
  })
})

describe('GraphQa 流式回答中断逻辑', () => {
  let wrapper: VueWrapper<any>

  beforeEach(() => {
    vi.clearAllMocks()
    mockListConversations.mockResolvedValue([
      { id: 'conv-1', title: '对话1' },
      { id: 'conv-2', title: '对话2' },
    ])
    mockCreateConversation.mockResolvedValue({ id: 'conv-new' })
    mockGetMessages.mockResolvedValue([])
  })

  it('创建新对话时应中止当前流式请求', async () => {
    let abortController: AbortController | null = null

    mockAskStreamFetch.mockImplementation(() => {
      abortController = new AbortController()
      return abortController
    })

    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any

    vm.currentConversationId = 'conv-1'
    vm.thinking = true
    vm.currentStreamController = new AbortController()
    const abortSpy = vi.spyOn(vm.currentStreamController, 'abort')

    await vm.createNewConversation()
    await nextTick()

    expect(abortSpy).toHaveBeenCalled()
    expect(vm.currentStreamController).toBeNull()
  })

  it('切换对话时应中止当前流式请求', async () => {
    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    vm.thinking = true
    vm.currentStreamController = new AbortController()
    const abortSpy = vi.spyOn(vm.currentStreamController, 'abort')

    await vm.switchConversation('conv-2')
    await nextTick()

    expect(abortSpy).toHaveBeenCalled()
    expect(vm.currentStreamController).toBeNull()
  })

  it('切换到同一对话时不应中止请求', async () => {
    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    const origController = new AbortController()
    vm.currentStreamController = origController
    const abortSpy = vi.spyOn(origController, 'abort')

    await vm.switchConversation('conv-1')
    await nextTick()

    expect(abortSpy).not.toHaveBeenCalled()
    expect(vm.currentStreamController).toBe(origController)
  })

  it('流式回调时对话ID不匹配应忽略更新', async () => {
    let onTokenCallback: ((token: string) => void) | null = null

    mockAskStreamFetch.mockImplementation((params: any, callbacks: any) => {
      onTokenCallback = callbacks.onToken
      return new AbortController()
    })
    mockCreateConversation.mockResolvedValue({ id: 'conv-1', title: '新对话' })
    mockListConversations.mockResolvedValue([])

    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    vm.inputText = '测试问题'

    await vm.handleSend()
    await nextTick()

    expect(onTokenCallback).not.toBeNull()

    const initialContent = vm.streamingContent

    vm.currentConversationId = 'conv-2'

    if (onTokenCallback) {
      onTokenCallback('新内容')
    }
    await nextTick()

    expect(vm.streamingContent).toBe(initialContent)
  })

  it('流式回调时对话ID匹配应更新内容', async () => {
    let onTokenCallback: ((token: string) => void) | null = null

    mockAskStreamFetch.mockImplementation((params: any, callbacks: any) => {
      onTokenCallback = callbacks.onToken
      return new AbortController()
    })
    mockCreateConversation.mockResolvedValue({ id: 'conv-1', title: '新对话' })
    mockListConversations.mockResolvedValue([])

    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    vm.inputText = '测试问题'

    await vm.handleSend()
    await nextTick()

    expect(vm.thinking).toBe(true)
    expect(onTokenCallback).not.toBeNull()

    if (onTokenCallback) {
      onTokenCallback('你好')
    }
    await nextTick()

    expect(vm.streamingContent).toBe('你好')

    if (onTokenCallback) {
      onTokenCallback('，世界')
    }
    await nextTick()

    expect(vm.streamingContent).toBe('你好，世界')
  })

  it('流式完成时应清空streamController', async () => {
    let onCompleteCallback: ((data: any) => void) | null = null

    mockAskStreamFetch.mockImplementation((params: any, callbacks: any) => {
      onCompleteCallback = callbacks.onComplete
      return new AbortController()
    })
    mockCreateConversation.mockResolvedValue({ id: 'conv-1', title: '新对话' })
    mockListConversations.mockResolvedValue([])

    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    vm.inputText = '测试问题'

    await vm.handleSend()
    await nextTick()

    expect(vm.thinking).toBe(true)
    expect(onCompleteCallback).not.toBeNull()
    expect(vm.messages.length).toBe(1)

    if (onCompleteCallback) {
      onCompleteCallback({
        answer: '完整回答',
        evidences: [],
        messageId: 'msg-1',
      })
    }
    await nextTick()

    expect(vm.thinking).toBe(false)
    expect(vm.currentStreamController).toBeNull()
    expect(vm.messages.length).toBe(2)
    expect(vm.messages[1].content).toBe('完整回答')
  })

  it('流式错误时应清空streamController', async () => {
    let onErrorCallback: ((err: any) => void) | null = null

    mockAskStreamFetch.mockImplementation((params: any, callbacks: any) => {
      onErrorCallback = callbacks.onError
      return new AbortController()
    })
    mockCreateConversation.mockResolvedValue({ id: 'conv-1', title: '新对话' })
    mockListConversations.mockResolvedValue([])

    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentConversationId = 'conv-1'
    vm.inputText = '测试问题'

    await vm.handleSend()
    await nextTick()

    expect(vm.thinking).toBe(true)
    expect(onErrorCallback).not.toBeNull()

    if (onErrorCallback) {
      onErrorCallback(new Error('test error'))
    }
    await nextTick()

    expect(vm.thinking).toBe(false)
    expect(vm.currentStreamController).toBeNull()
  })

  it('没有流式请求时创建新对话不应崩溃', async () => {
    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentStreamController = null

    await expect(vm.createNewConversation()).resolves.not.toThrow()
  })

  it('没有流式请求时切换对话不应崩溃', async () => {
    wrapper = createWrapper()
    await nextTick()

    const vm = wrapper.vm as any
    vm.currentStreamController = null
    vm.currentConversationId = 'conv-1'

    await expect(vm.switchConversation('conv-2')).resolves.not.toThrow()
  })
})
