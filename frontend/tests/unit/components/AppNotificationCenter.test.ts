import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NotificationCenter from '@/components/NotificationCenter.vue'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    getCurrentUser: vi.fn(),
  },
  notificationApi: {
    getRecent: vi.fn().mockResolvedValue([]),
    markRead: vi.fn(),
  },
  projectApi: {
    list: vi.fn(),
    detail: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('根通知中心', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    const userStore = useUserStore()
    const projectStore = useProjectStore()
    userStore.setTokens('access-token-1', 'refresh-token-1')
    projectStore.setCurrentProject('project 1')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn(() => new Promise(() => {})),
        }),
      },
    }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('通知 SSE 使用 Authorization 头且不把 token 放入 URL', async () => {
    const wrapper = mount(NotificationCenter, {
      global: {
        plugins: [pinia],
        stubs: {
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElBadge: { template: '<div><slot /></div>' },
          ElIcon: { template: '<i><slot /></i>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElDropdownItem: { template: '<div><slot /></div>' },
          ElTag: { template: '<span><slot /></span>' },
          ElButton: { template: '<button><slot /></button>' },
          Bell: true,
        },
      },
    })

    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toContain('/lg/notifications/stream?projectId=project%201')
    expect(String(url)).not.toContain('token=')
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer access-token-1')

    wrapper.unmount()
  })
})
