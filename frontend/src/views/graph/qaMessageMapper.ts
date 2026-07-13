import type { EvidenceItem, QaMessage } from '@/api/qa.api'

export interface QaChatMessage {
  role: 'user' | 'assistant'
  content: string
  confidence?: number
  evidences: EvidenceItem[]
  messageId: string
  conversationId: string
  // 提问/回答时间（ISO 字符串），历史消息来自后端 createdAt
  createdAt?: string
  // 回答耗时（毫秒），仅流式新回答有；历史消息未持久化，故缺省
  latencyMs?: number
}

export function mapQaHistoryMessages(messages: QaMessage[]): QaChatMessage[] {
  return messages.map((message) => ({
    role: normalizeRole(message.role),
    content: message.content,
    confidence: message.confidence,
    evidences: parseEvidences(message.evidences ?? message.evidencesJson),
    messageId: message.id,
    conversationId: message.conversationId,
    createdAt: message.createdAt,
  }))
}

function normalizeRole(role: QaMessage['role']): QaChatMessage['role'] {
  return role === 'USER' || role === 'user' ? 'user' : 'assistant'
}

function parseEvidences(raw?: string): EvidenceItem[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}
