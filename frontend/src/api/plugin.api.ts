import { get, post } from '@/utils/request'

export interface PluginDescriptor {
  id: string
  name: string
  description: string
  type: 'SCANNER' | 'AGENT' | 'TOOL' | 'GRAPH_VIEW'
  version: string
  metadata: Record<string, string>
  enabled: boolean
  menuSection?: string
  menuLabel?: string
  menuPath?: string
  routeName?: string
}

export interface PluginQueryParams {
  type?: PluginDescriptor['type']
}

/**
 * 插件管理 API
 */
export const pluginApi = {
  /**
   * 查询所有已注册插件
   */
  listAll(params?: PluginQueryParams): Promise<PluginDescriptor[]> {
    return get('/lg/plugins', params)
  },

  /**
   * 获取单个插件详情
   */
  get(id: string): Promise<PluginDescriptor> {
    return get(`/lg/plugins/${encodeURIComponent(id)}`)
  },

  /**
   * 启用插件
   */
  enable(id: string): Promise<PluginDescriptor> {
    return post(`/lg/plugins/${encodeURIComponent(id)}/enable`, {})
  },

  /**
   * 禁用插件
   */
  disable(id: string): Promise<PluginDescriptor> {
    return post(`/lg/plugins/${encodeURIComponent(id)}/disable`, {})
  }
}
