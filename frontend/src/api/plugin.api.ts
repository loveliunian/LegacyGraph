import { get } from '@/utils/request'

export interface PluginDescriptor {
  id: string
  name: string
  description: string
  type: 'SCANNER' | 'AGENT' | 'TOOL' | 'GRAPH_VIEW'
  version: string
  metadata: Record<string, string>
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
    const query = params?.type ? `?type=${params.type}` : ''
    return get(`/lg/plugins${query}`)
  },

  /**
   * 获取单个插件详情
   */
  get(id: string): Promise<PluginDescriptor> {
    return get(`/lg/plugins/${id}`)
  }
}
