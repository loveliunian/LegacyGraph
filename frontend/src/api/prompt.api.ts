import { get, post, put, del } from '@/utils/request'
import type { PageResult } from '@/types'

/**
 * 提示词模板实体
 */
export interface PromptTemplate {
  /** 模板ID */
  id?: number
  /** 模板编码 */
  templateCode: string
  /** 版本号 */
  version?: string
  /** 场景标识 */
  scene?: string
  /** 系统角色提示词 */
  systemPrompt?: string
  /** 领域知识提示词 */
  domainPrompt?: string
  /** 任务指令提示词 */
  taskPrompt?: string
  /** 输出格式 Schema (JSON) */
  outputSchema?: string
  /** 是否启用 */
  isActive?: boolean
  /** 创建时间 */
  createdAt?: string
}

/**
 * 提示词模板 API
 */
export const promptApi = {
  /** 分页查询 */
  list: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    scene?: string
    status?: string
  }) => get<PageResult<PromptTemplate>>('/lg/admin/prompts/list', params),

  /** 获取所有激活的模板 */
  listActive: () => get<PromptTemplate[]>('/lg/admin/prompts/active'),

  /** 获取详情 */
  getById: (id: number) => get<PromptTemplate>(`/lg/admin/prompts/${encodeURIComponent(id)}`),

  /** 创建 */
  create: (data: PromptTemplate) => post<PromptTemplate>('/lg/admin/prompts', data),

  /** 更新（创建新版本） */
  update: (id: number, data: PromptTemplate) => put<PromptTemplate>(`/lg/admin/prompts/${encodeURIComponent(id)}`, data),

  /** 切换启用状态 */
  toggleActive: (id: number) => put(`/lg/admin/prompts/${encodeURIComponent(id)}/toggle`),

  /** 删除 */
  delete: (id: number) => del(`/lg/admin/prompts/${id}`),

  /** 刷新缓存 */
  refreshCache: () => post('/lg/admin/prompts/cache/refresh'),
}
