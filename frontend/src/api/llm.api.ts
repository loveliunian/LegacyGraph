/**
 * LLM 提供商 API 模块
 * 管理 LLM 提供商的增删改查、启用/禁用、切换默认
 */
import { get, post, put, del } from '@/utils/request'

/** LLM 提供商配置 */
export interface LlmProvider {
  id: number
  providerCode: string
  modelId: string
  endpoint: string
  deploymentMode: string
  apiConfig: Record<string, any>
  isDefault: boolean
  isActive: boolean
  createdAt: string
}

/** LLM 提供商管理 API */
export const llmApi = {
  /** 获取所有提供商 */
  listAll: () => {
    return get<LlmProvider[]>('/llm/providers')
  },

  /** 获取当前默认提供商 */
  getDefault: () => {
    return get<LlmProvider>('/llm/providers/default')
  },

  /** 获取指定提供商 */
  getByCode: (code: string) => {
    return get<LlmProvider>(`/llm/providers/${encodeURIComponent(code)}`)
  },

  /** 保存或更新提供商 */
  save: (data: Partial<LlmProvider>) => {
    return post<LlmProvider>('/llm/providers', data)
  },

  /** 切换为默认提供商 */
  setDefault: (code: string) => {
    return put<void>(`/llm/providers/${encodeURIComponent(code)}/set-default`)
  },

  /** 启用/禁用 */
  toggleActive: (code: string, active: boolean) => {
    return put<void>(`/llm/providers/${encodeURIComponent(code)}/toggle-active?active=${encodeURIComponent(active)}`)
  },

  /** 删除提供商 */
  delete: (code: string) => {
    return del<void>(`/llm/providers/${code}`)
  },

  /** 测试提供商连通性（区分 chat / embedding 两类模型） */
  test: (code: string) => {
    return post<LlmProviderTestResult>(`/llm/providers/${encodeURIComponent(code)}/test`)
  }
}

/** 提供商测试结果 */
export interface LlmProviderTestResult {
  providerCode: string
  modelId: string
  type: 'chat' | 'embedding'
  success: boolean
  message: string
  latencyMs: number
  responseSnippet?: string
  vectorDim?: number
}
