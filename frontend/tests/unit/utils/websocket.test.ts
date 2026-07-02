import { describe, it, expect, vi } from 'vitest'

describe('websocket WebSocket工具', () => {
  it('应该导出 LegacyWebSocket 类', async () => {
    const mod = await import('@/utils/websocket')
    expect(mod.LegacyWebSocket).toBeDefined()
    expect(typeof mod.LegacyWebSocket).toBe('function')
  })

  it('应该导出 WebSocketMessage 类型', async () => {
    const mod = await import('@/utils/websocket')
    expect(mod).toBeDefined()
  })

  it('LegacyWebSocket 应该支持传入配置', async () => {
    const { LegacyWebSocket } = await import('@/utils/websocket')
    const ws = new LegacyWebSocket({ url: 'ws://localhost:8080/ws' })
    expect(ws).toBeDefined()
    expect(ws.isConnected).toBeDefined()
    expect(ws.isConnected.value).toBe(false)
    // 清理
    ws.close()
  })
})
