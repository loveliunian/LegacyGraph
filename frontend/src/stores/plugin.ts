import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { pluginApi, type PluginDescriptor } from '@/api'

/** 插件子功能项（从 metadata.menuItems JSON 解析） */
export interface PluginMenuItem {
  pluginId: string
  menuLabel: string
  menuPath: string
  routeName: string
}

export const usePluginStore = defineStore('plugin', () => {
  const graphViewPlugins = ref<PluginDescriptor[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  /**
   * 拉取已启用的 GRAPH_VIEW 类型插件（用于动态菜单和路由注入）
   */
  async function loadGraphViewPlugins(force = false) {
    if (loaded.value && !force) return
    if (loading.value) return
    loading.value = true
    try {
      const all = await pluginApi.listAll({ type: 'GRAPH_VIEW' })
      graphViewPlugins.value = all.filter(p => p.enabled)
      loaded.value = true
    } catch (e) {
      console.warn('Failed to load graph view plugins:', e)
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  /**
   * 从插件 metadata.menuItems 解析子功能列表
   * 兼容两种插件形态：
   *   1. 单功能插件：直接用 menuLabel/menuPath/routeName 字段
   *   2. 整体插件：metadata.menuItems JSON 数组
   */
  const pluginMenuItems = computed<PluginMenuItem[]>(() => {
    const items: PluginMenuItem[] = []
    graphViewPlugins.value.forEach(p => {
      // 形态1：单功能插件有直接的路由字段
      if (p.routeName && p.menuPath) {
        items.push({
          pluginId: p.id,
          menuLabel: p.menuLabel || p.name,
          menuPath: p.menuPath,
          routeName: p.routeName
        })
      }
      // 形态2：整体插件从 metadata.menuItems 解析
      if (p.metadata?.menuItems) {
        try {
          const parsed = JSON.parse(p.metadata.menuItems)
          if (Array.isArray(parsed)) {
            parsed.forEach((m: any) => {
              if (m.routeName && m.menuPath) {
                items.push({
                  pluginId: p.id,
                  menuLabel: m.menuLabel || m.routeName,
                  menuPath: m.menuPath,
                  routeName: m.routeName
                })
              }
            })
          }
        } catch (e) {
          console.warn(`Failed to parse menuItems for plugin ${p.id}:`, e)
        }
      }
    })
    return items
  })

  /**
   * 按菜单分组的子功能项
   */
  const menuItemsBySection = computed<Record<string, PluginMenuItem[]>>(() => {
    const map: Record<string, PluginMenuItem[]> = {}
    graphViewPlugins.value.forEach(p => {
      if (!p.menuSection) return
      const list = map[p.menuSection] ||= []
      // 单功能插件
      if (p.routeName && p.menuPath) {
        list.push({
          pluginId: p.id,
          menuLabel: p.menuLabel || p.name,
          menuPath: p.menuPath,
          routeName: p.routeName
        })
      }
      // 整体插件的子功能
      if (p.metadata?.menuItems) {
        try {
          const parsed = JSON.parse(p.metadata.menuItems)
          if (Array.isArray(parsed)) {
            parsed.forEach((m: any) => {
              if (m.routeName && m.menuPath) {
                list.push({
                  pluginId: p.id,
                  menuLabel: m.menuLabel || m.routeName,
                  menuPath: m.menuPath,
                  routeName: m.routeName
                })
              }
            })
          }
        } catch {
          // ignore parse errors
        }
      }
    })
    return map
  })

  /**
   * 获取指定菜单组的插件子功能项
   */
  function getMenuItemsForSection(section: string): PluginMenuItem[] {
    return menuItemsBySection.value[section] || []
  }

  return {
    graphViewPlugins,
    loaded,
    loading,
    pluginMenuItems,
    menuItemsBySection,
    loadGraphViewPlugins,
    getMenuItemsForSection
  }
})
