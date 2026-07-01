import { post } from '@/utils/request'

/**
 * 图谱问答 API
 * 基于知识图谱的智能问答
 */
export const qaApi = {
  /**
   * 提问
   * @param data 包含问题、项目ID（可选）、版本ID（可选）
   */
  ask: (data: { question: string; projectId?: string; versionId?: string }) => {
    return post('/qa/ask', data)
  },
}
