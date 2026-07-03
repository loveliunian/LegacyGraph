import { post, get, del } from '@/utils/request'
import { useUserStore } from '@/stores/user'

export interface EvidenceItem {
  sourceKind: string
  ref: string
  title: string
  excerpt: string
  sourcePath?: string
  sourceFile?: string
  nodeType?: string
  score?: number
  relevanceScore?: number
  retrievalMethod?: string
  jumpUrl?: string
}

export interface QaConversation {
  id: string
  projectId: string
  title: string
  messageCount?: number
  createdAt: string
  updatedAt: string
}

export interface QaMessage {
  id: string
  conversationId: string
  role: 'USER' | 'ASSISTANT' | 'user' | 'assistant'
  content: string
  evidences?: string
  evidencesJson?: string
  confidence?: number
  createdAt: string
}

interface QaStreamCallbacks {
  onToken: (token: string) => void
  onEvidence?: (evidences: EvidenceItem[]) => void
  onComplete: (data: any) => void
  onError: (error: Error) => void
}

/**
 * 图谱问答 API — 流式 + 多轮对话
 */
export const qaApi = {
  /**
   * 非流式问答（兼容旧版）
   */
  ask: (data: { question: string; projectId?: string; versionId?: string }) => {
    return post('/qa/ask', data)
  },

  /**
   * 流式问答 — 返回 EventSource 供前端监听
   */
  askStream: (data: {
    question: string
    projectId?: string
    versionId?: string
    conversationId?: string
  }): EventSource => {
    // POST SSE: 使用 fetch + ReadableStream 替代原生 EventSource（原生只支持 GET）
    const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api') + '/qa/ask/stream'
    const es = new EventSource(`${baseUrl}?t=${Date.now()}`) // placeholder, we use fetch below
    es.close() // 关闭原生 ES，改用 fetch

    // 返回自定义对象模拟 EventSource
    return createFetchSSE(baseUrl, data) as any
  },

  /**
   * 使用 fetch 发起 POST SSE 请求
   * 返回一个具有 onmessage/onerror/onopen 接口的对象
   */
  askStreamFetch: (
    data: {
      question: string
      projectId?: string
      versionId?: string
      conversationId?: string
    },
    callbacks: QaStreamCallbacks
  ): AbortController => {
    const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api') + '/qa/ask/stream'
    const controller = new AbortController()
    const userStore = useUserStore()
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (userStore.accessToken) {
      headers['Authorization'] = `Bearer ${userStore.accessToken}`
    }

    fetch(baseUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`)
        }
        const reader = response.body?.getReader()
        if (!reader) throw new Error('No reader')
        const decoder = new TextDecoder()
        let buffer = ''

        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })

          // 解析 SSE 事件
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          let currentEvent = 'message'
          for (const line of lines) {
            if (line.startsWith('event:')) {
              currentEvent = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              const dataStr = line.slice(5).trim()
              if (!dataStr || dataStr === '[DONE]') continue

              try {
                const parsed = JSON.parse(dataStr)
                if (currentEvent === 'token') {
                  callbacks.onToken(parsed.text || '')
                } else if (currentEvent === 'evidence') {
                  const items = Array.isArray(parsed) ? parsed : parsed.items
                  callbacks.onEvidence?.(Array.isArray(items) ? items : [])
                } else if (currentEvent === 'complete') {
                  callbacks.onComplete(parsed)
                } else if (currentEvent === 'error') {
                  callbacks.onError(new Error(parsed.message || '未知错误'))
                }
              } catch {
                // 非 JSON，直接当 token 处理
                if (currentEvent === 'token') {
                  callbacks.onToken(dataStr)
                }
              }
              currentEvent = 'message'
            }
          }
        }
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          callbacks.onError(err)
        }
      })

    return controller
  },

  /** 列出对话 */
  listConversations: (projectId: string) => {
    return get(`/qa/conversations?projectId=${projectId}`) as Promise<{ data: QaConversation[] }>
  },

  /** 获取对话消息 */
  getMessages: (conversationId: string) => {
    return get(`/qa/conversations/${conversationId}/messages`) as Promise<{ data: QaMessage[] }>
  },

  /** 删除对话 */
  deleteConversation: (conversationId: string) => {
    return del(`/qa/conversations/${conversationId}`)
  },

  /** 提交反馈 */
  submitFeedback: (data: {
    messageId: string
    conversationId: string
    projectId: string
    helpful: boolean
    feedbackText?: string
    usedEvidenceIds?: string[]
    question?: string
    answer?: string
  }) => {
    return post('/qa/feedback', data)
  },
}

/** fetch-based SSE helper (legacy compat) */
function createFetchSSE(_url: string, _data: any): any {
  return { onmessage: null, onerror: null, close() {} }
}
