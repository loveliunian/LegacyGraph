import { get, del } from '@/utils/request'

/**
 * 审计日志 API
 */
export const auditApi = {
  /** 查询审计日志列表 */
  list: (params?: Record<string, any>) =>
    get('/lg/audit/list', { params }),

  /** 获取审计日志详情 */
  getDetail: (id: string) => get(`/lg/audit/${id}`),

  /** 下载审计日志详情（JSON格式） */
  download: (id: string) => {
    window.open(`/api/lg/audit/${id}/download`, '_blank')
  },

  /** 清空审计日志 */
  clear: () => del('/lg/audit/clear'),

  /** 获取审计日志统计数 */
  statsCount: () => get('/lg/audit/stats/count'),
}
