import { get } from '@/utils/request'

export interface Notification {
  id: string
  eventType: string
  payload: Record<string, any>
  read: boolean
  createdAt: string
}

export const notificationApi = {
  /** 获取最近通知 */
  getRecent: (projectId: string, limit: number = 20) => {
    return get<Notification[]>('/lg/notifications/recent', { projectId, limit })
  },

  /** 标记已读 */
  markRead: (id: string) => {
    return get(`/lg/notifications/${id}/read`)
  },
}
