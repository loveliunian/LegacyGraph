import { describe, expect, it } from 'vitest'
import { mapQaHistoryMessages } from '../qaMessageMapper'

describe('mapQaHistoryMessages', () => {
  it('normalizes backend roles and reads the persisted evidences field', () => {
    const messages = mapQaHistoryMessages([
      {
        id: 'm1',
        conversationId: 'conv-1',
        role: 'USER',
        content: '订单服务？',
        createdAt: '2026-07-03T10:00:00',
      },
      {
        id: 'm2',
        conversationId: 'conv-1',
        role: 'ASSISTANT',
        content: 'OrderService 负责订单创建。',
        evidences: JSON.stringify([
          {
            sourceKind: 'GRAPH_NODE',
            ref: 'node-1',
            title: 'OrderService',
            excerpt: 'creates orders',
          },
        ]),
        confidence: 0.8,
        createdAt: '2026-07-03T10:00:01',
      },
    ])

    expect(messages).toEqual([
      {
        role: 'user',
        content: '订单服务？',
        confidence: undefined,
        evidences: [],
        messageId: 'm1',
        conversationId: 'conv-1',
        createdAt: '2026-07-03T10:00:00',
      },
      {
        role: 'assistant',
        content: 'OrderService 负责订单创建。',
        confidence: 0.8,
        evidences: [
          {
            sourceKind: 'GRAPH_NODE',
            ref: 'node-1',
            title: 'OrderService',
            excerpt: 'creates orders',
          },
        ],
        messageId: 'm2',
        conversationId: 'conv-1',
        createdAt: '2026-07-03T10:00:01',
      },
    ])
  })
})
