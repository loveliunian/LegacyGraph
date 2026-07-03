import type { EvidenceItem, QaMessage } from '@/api/qa.api'

export interface QaChatMessage {
  role: 'user' | 'assistant'
  content: string
  confidence?: number
  evidences: EvidenceItem[]
  messageId: string
  conversationId: string
}

export function mapQaHistoryMessages(messages: QaMessage[]): QaChatMessage[] {
  return messages.map((message) => ({
    role: normalizeRole(message.role),
    content: message.content,
    confidence: message.confidence,
    evidences: parseEvidences(message.evidences ?? message.evidencesJson),
    messageId: message.id,
    conversationId: message.conversationId,
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
