import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { PluginDescriptor } from '@/api'

// Mock @/api (pluginApi)
vi.mock('@/api', () => ({
  pluginApi: {
    listAll: vi.fn()
  }
}))

// Mock pinia-plugin-persistedstate
vi.mock('pinia-plugin-persistedstate', () => ({
  default: () => () => {}
}))

import { pluginApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

describe('Plugin Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // 1. 初始化默认值
  it('should initialize with default values', () => {
    const store = usePluginStore()
    expect(store.loaded).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.graphViewPlugins).toEqual([])
  })

  // 2. loadGraphViewPlugins 首次加载 → 调用 pluginApi.listAll + 过滤 enabled
  it('should load graph view plugins and filter enabled on first load', async () => {
    const allPlugins: PluginDescriptor[] = [
      { id: 'p1', name: 'Enabled', description: '', type: 'GRAPH_VIEW', version: '1.0', metadata: {}, enabled: true },
      { id: 'p2', name: 'Disabled', description: '', type: 'GRAPH_VIEW', version: '1.0', metadata: {}, enabled: false }
    ]
    vi.mocked(pluginApi.listAll).mockResolvedValue(allPlugins)

    const store = usePluginStore()
    await store.loadGraphViewPlugins()

    expect(pluginApi.listAll).toHaveBeenCalledWith({ type: 'GRAPH_VIEW' })
    expect(pluginApi.listAll).toHaveBeenCalledTimes(1)
    expect(store.graphViewPlugins).toHaveLength(1)
    expect(store.graphViewPlugins[0].id).toBe('p1')
    expect(store.loaded).toBe(true)
    expect(store.loading).toBe(false)
  })

  // 3. loadGraphViewPlugins 已 loaded 非 force → 不重复请求
  it('should not re-fetch when already loaded without force', async () => {
    vi.mocked(pluginApi.listAll).mockResolvedValue([])

    const store = usePluginStore()
    await store.loadGraphViewPlugins()
    expect(pluginApi.listAll).toHaveBeenCalledTimes(1)

    await store.loadGraphViewPlugins()
    expect(pluginApi.listAll).toHaveBeenCalledTimes(1)
  })

  // 4. loadGraphViewPlugins loading 中 → 不重复请求
  it('should not re-fetch when loading is in progress', async () => {
    let resolveListAll!: (value: PluginDescriptor[]) => void
    const pendingPromise = new Promise<PluginDescriptor[]>(resolve => {
      resolveListAll = resolve
    })
    vi.mocked(pluginApi.listAll).mockReturnValue(pendingPromise)

    const store = usePluginStore()
    // 第一次调用，不 await，处于 pending 状态
    const firstCall = store.loadGraphViewPlugins()

    // loading 应为 true
    expect(store.loading).toBe(true)

    // loading 中再次调用，应直接返回不重复请求
    await store.loadGraphViewPlugins()
    expect(pluginApi.listAll).toHaveBeenCalledTimes(1)

    // 解除 pending 以清理
    resolveListAll([])
    await firstCall
  })

  // 5. loadGraphViewPlugins force=true → 强制重新加载
  it('should force reload when force=true', async () => {
    const plugins1: PluginDescriptor[] = [
      { id: 'p1', name: 'Plugin 1', description: '', type: 'GRAPH_VIEW', version: '1.0', metadata: {}, enabled: true }
    ]
    const plugins2: PluginDescriptor[] = [
      { id: 'p2', name: 'Plugin 2', description: '', type: 'GRAPH_VIEW', version: '1.0', metadata: {}, enabled: true }
    ]
    vi.mocked(pluginApi.listAll)
      .mockResolvedValueOnce(plugins1)
      .mockResolvedValueOnce(plugins2)

    const store = usePluginStore()
    await store.loadGraphViewPlugins()
    expect(pluginApi.listAll).toHaveBeenCalledTimes(1)
    expect(store.graphViewPlugins[0].id).toBe('p1')

    await store.loadGraphViewPlugins(true)
    expect(pluginApi.listAll).toHaveBeenCalledTimes(2)
    expect(store.graphViewPlugins[0].id).toBe('p2')
  })

  // 6. loadGraphViewPlugins API 失败 → loaded 仍设为 true（容错）
  it('should set loaded to true even when API fails (fault tolerance)', async () => {
    vi.mocked(pluginApi.listAll).mockRejectedValue(new Error('Network error'))
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const store = usePluginStore()
    await store.loadGraphViewPlugins()

    expect(store.loaded).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.graphViewPlugins).toEqual([])
    expect(consoleSpy).toHaveBeenCalled()

    consoleSpy.mockRestore()
  })

  // 7. pluginMenuItems 单功能插件解析（有 routeName/menuPath）
  it('should parse single-function plugins (with routeName and menuPath) in pluginMenuItems', () => {
    const store = usePluginStore()
    const plugins: PluginDescriptor[] = [
      {
        id: 'p1',
        name: 'With Label',
        description: '',
        type: 'GRAPH_VIEW',
        version: '1.0',
        metadata: {},
        enabled: true,
        routeName: 'route-1',
        menuPath: '/path-1',
        menuLabel: 'Menu 1'
      },
      {
        id: 'p2',
        name: 'Fallback Name',
        description: '',
        type: 'GRAPH_VIEW',
        version: '1.0',
        metadata: {},
        enabled: true,
        routeName: 'route-2',
        menuPath: '/path-2'
        // 无 menuLabel，应回退到 name
      }
    ]
    store.graphViewPlugins = plugins

    expect(store.pluginMenuItems).toEqual([
      { pluginId: 'p1', menuLabel: 'Menu 1', menuPath: '/path-1', routeName: 'route-1' },
      { pluginId: 'p2', menuLabel: 'Fallback Name', menuPath: '/path-2', routeName: 'route-2' }
    ])
  })

  // 8. pluginMenuItems 整体插件解析（metadata.menuItems JSON 数组）
  it('should parse whole plugins (metadata.menuItems JSON array) in pluginMenuItems', () => {
    const store = usePluginStore()
    const plugin: PluginDescriptor = {
      id: 'p2',
      name: 'Multi Plugin',
      description: '',
      type: 'GRAPH_VIEW',
      version: '1.0',
      metadata: {
        menuItems: JSON.stringify([
          { routeName: 'route-a', menuPath: '/a', menuLabel: 'Menu A' },
          { routeName: 'route-b', menuPath: '/b' }
        ])
      },
      enabled: true
    }
    store.graphViewPlugins = [plugin]

    expect(store.pluginMenuItems).toEqual([
      { pluginId: 'p2', menuLabel: 'Menu A', menuPath: '/a', routeName: 'route-a' },
      { pluginId: 'p2', menuLabel: 'route-b', menuPath: '/b', routeName: 'route-b' }
    ])
  })

  // 9. pluginMenuItems menuItems JSON 解析失败 → 不崩溃
  it('should not crash when metadata.menuItems JSON is invalid', () => {
    const store = usePluginStore()
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const plugin: PluginDescriptor = {
      id: 'p3',
      name: 'Bad JSON Plugin',
      description: '',
      type: 'GRAPH_VIEW',
      version: '1.0',
      metadata: {
        menuItems: '{invalid json'
      },
      enabled: true
    }
    store.graphViewPlugins = [plugin]

    expect(() => store.pluginMenuItems).not.toThrow()
    expect(store.pluginMenuItems).toEqual([])
    expect(consoleSpy).toHaveBeenCalled()

    consoleSpy.mockRestore()
  })

  // 10. menuItemsBySection 按 menuSection 分组
  it('should group menu items by menuSection in menuItemsBySection', () => {
    const store = usePluginStore()
    const plugins: PluginDescriptor[] = [
      {
        id: 'p1', name: 'Single In Section A', description: '', type: 'GRAPH_VIEW', version: '1.0',
        metadata: {}, enabled: true,
        menuSection: 'section-a',
        routeName: 'route-1', menuPath: '/1', menuLabel: 'Menu 1'
      },
      {
        id: 'p2', name: 'Whole In Section B', description: '', type: 'GRAPH_VIEW', version: '1.0',
        metadata: {
          menuItems: JSON.stringify([
            { routeName: 'route-a', menuPath: '/a', menuLabel: 'Menu A' },
            { routeName: 'route-b', menuPath: '/b', menuLabel: 'Menu B' }
          ])
        },
        enabled: true,
        menuSection: 'section-b'
      },
      {
        id: 'p3', name: 'No Section', description: '', type: 'GRAPH_VIEW', version: '1.0',
        metadata: {}, enabled: true,
        routeName: 'route-3', menuPath: '/3', menuLabel: 'Menu 3'
        // 无 menuSection，应被排除
      }
    ]
    store.graphViewPlugins = plugins

    expect(store.menuItemsBySection).toEqual({
      'section-a': [
        { pluginId: 'p1', menuLabel: 'Menu 1', menuPath: '/1', routeName: 'route-1' }
      ],
      'section-b': [
        { pluginId: 'p2', menuLabel: 'Menu A', menuPath: '/a', routeName: 'route-a' },
        { pluginId: 'p2', menuLabel: 'Menu B', menuPath: '/b', routeName: 'route-b' }
      ]
    })
    // 无 menuSection 的插件不应出现在分组中
    expect(store.menuItemsBySection).not.toHaveProperty('undefined')
  })

  // 11. getMenuItemsForSection 存在/不存在的 section
  it('should return items for existing section and empty array for non-existing section', () => {
    const store = usePluginStore()
    const plugins: PluginDescriptor[] = [
      {
        id: 'p1', name: 'Plugin 1', description: '', type: 'GRAPH_VIEW', version: '1.0',
        metadata: {}, enabled: true,
        menuSection: 'section-a',
        routeName: 'route-1', menuPath: '/1', menuLabel: 'Menu 1'
      }
    ]
    store.graphViewPlugins = plugins

    // 存在的 section
    expect(store.getMenuItemsForSection('section-a')).toEqual([
      { pluginId: 'p1', menuLabel: 'Menu 1', menuPath: '/1', routeName: 'route-1' }
    ])

    // 不存在的 section
    expect(store.getMenuItemsForSection('non-existent')).toEqual([])
  })
})
