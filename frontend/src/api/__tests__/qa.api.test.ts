import { describe, expect, it, vi } from 'vitest'
import { qaApi } from '../qa.api'

describe('qaApi.askStreamFetch', () => {
  it('dispatches evidence SSE events before completion', async () => {
    const encoder = new TextEncoder()
    const evidence = {
      sourceKind: 'GRAPH_NODE',
      ref: 'node-1',
      title: 'OrderService',
      excerpt: 'creates orders',
      jumpUrl: '/graph?node=node-1',
    }
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            [
              'event: evidence',
              `data: ${JSON.stringify({ items: [evidence] })}`,
              '',
              'event: token',
              'data: {"text":"A"}',
              '',
              'event: complete',
              'data: {"conversationId":"conv-1","messageId":"msg-1"}',
              '',
            ].join('\n')
          )
        )
        controller.close()
      },
    })

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        body: stream,
      })
    )

    const onEvidence = vi.fn()
    const onToken = vi.fn()
    const onComplete = vi.fn()

    qaApi.askStreamFetch(
      { projectId: 'proj-1', question: '订单服务？' },
      {
        onToken,
        onEvidence,
        onComplete,
        onError: vi.fn(),
      }
    )

    await vi.waitFor(() => {
      expect(onEvidence).toHaveBeenCalledWith([evidence])
      expect(onToken).toHaveBeenCalledWith('A')
      expect(onComplete).toHaveBeenCalledWith({ conversationId: 'conv-1', messageId: 'msg-1' })
    })
  })
})
