import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const axiosState = vi.hoisted(() => {
  let responseRejected: ((error: any) => Promise<any>) | undefined

  const request = vi.fn((config: any) => Promise.resolve({ data: { replayedUrl: config?.url } })) as any
  request.get = vi.fn()
  request.post = vi.fn()
  request.put = vi.fn()
  request.delete = vi.fn()
  request.interceptors = {
    request: {
      use: vi.fn()
    },
    response: {
      use: vi.fn((_fulfilled: any, rejected: any) => {
        responseRejected = rejected
      })
    }
  }

  return {
    request,
    get responseRejected() {
      if (!responseRejected) {
        throw new Error('response interceptor not registered')
      }
      return responseRejected
    },
    reset() {
      responseRejected = undefined
      request.mockReset()
      request.mockImplementation((config: any) => Promise.resolve({ data: { replayedUrl: config?.url } }))
      request.get.mockReset()
      request.post.mockReset()
      request.put.mockReset()
      request.delete.mockReset()
      request.interceptors.request.use.mockClear()
      request.interceptors.response.use.mockClear()
    }
  }
})

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => axiosState.request)
  }
}))

vi.mock('@/utils/loading', () => ({
  showLoading: vi.fn(),
  hideLoading: vi.fn(),
  forceHideLoading: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn()
  }
}))

describe('request token 刷新队列', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.resetModules()
    axiosState.reset()
    setActivePinia(createPinia())
  })

  it('排队请求超时后不应在后续刷新成功时被重放', async () => {
    vi.useFakeTimers()
    const { useUserStore } = await import('@/stores/user')
    const userStore = useUserStore()
    userStore.setTokens('old-access', 'refresh-token')

    let resolveRefresh!: (value: any) => void
    axiosState.request.post.mockImplementationOnce(() => new Promise(resolve => {
      resolveRefresh = resolve
    }))

    const firstRetry = axiosState.responseRejected({
      config: { url: '/first', headers: {} },
      response: { status: 401, statusText: 'Unauthorized' }
    })
    const timedOutRetry = axiosState.responseRejected({
      config: { url: '/timed-out', headers: {} },
      response: { status: 401, statusText: 'Unauthorized' }
    })
    const timedOutExpectation = expect(timedOutRetry).rejects.toThrow('Token 刷新超时')

    await vi.advanceTimersByTimeAsync(15000)
    await timedOutExpectation

    resolveRefresh({ accessToken: 'new-access', refreshToken: 'new-refresh' })
    await firstRetry

    expect(axiosState.request).toHaveBeenCalledTimes(1)
    expect(axiosState.request).toHaveBeenCalledWith(expect.objectContaining({ url: '/first' }))
    expect(axiosState.request).not.toHaveBeenCalledWith(expect.objectContaining({ url: '/timed-out' }))
  })
})
