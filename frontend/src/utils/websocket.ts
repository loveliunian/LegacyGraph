import { ref } from 'vue'

export interface WebSocketMessage<T = any> {
  type: string
  data: T
  timestamp?: number
}

export interface WebSocketOptions {
  url: string
  reconnectInterval?: number
  maxReconnectAttempts?: number
  heartbeatInterval?: number
  heartbeatMessage?: string
}

type MessageHandler = (message: WebSocketMessage) => void

export class LegacyWebSocket {
  private ws: WebSocket | null = null
  private url: string
  private reconnectAttempts = 0
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private isManualClose = false
  private messageHandlers = new Map<string, Set<MessageHandler>>()
  private allMessageHandlers = new Set<(message: WebSocketMessage) => void>()

  public readonly isConnected = ref(false)
  public readonly reconnectInterval: number
  public readonly maxReconnectAttempts: number
  public readonly heartbeatInterval: number
  public readonly heartbeatMessage: string

  constructor(options: WebSocketOptions) {
    this.url = options.url
    this.reconnectInterval = options.reconnectInterval || 3000
    this.maxReconnectAttempts = options.maxReconnectAttempts || 10
    this.heartbeatInterval = options.heartbeatInterval || 30000
    this.heartbeatMessage = options.heartbeatMessage || 'ping'
  }

  connect() {
    if (this.ws?.readyState === WebSocket.OPEN) return

    this.isManualClose = false

    try {
      this.ws = new WebSocket(this.url)

      this.ws.onopen = () => {
        console.log('[WebSocket] 连接成功')
        this.isConnected.value = true
        this.reconnectAttempts = 0
        this.startHeartbeat()
      }

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WebSocketMessage
          this.handleMessage(message)
        } catch (error) {
          console.error('[WebSocket] 消息解析失败:', error)
        }
      }

      this.ws.onerror = (error) => {
        console.error('[WebSocket] 连接错误:', error)
        this.isConnected.value = false
      }

      this.ws.onclose = (event) => {
        console.log('[WebSocket] 连接关闭:', event.code, event.reason)
        this.isConnected.value = false
        this.stopHeartbeat()

        if (!this.isManualClose) {
          this.scheduleReconnect()
        }
      }
    } catch (error) {
      console.error('[WebSocket] 创建连接失败:', error)
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WebSocket] 达到最大重连次数，停止重连')
      return
    }

    this.reconnectAttempts++
    console.log(`[WebSocket] ${this.reconnectInterval / 1000}s 后尝试第 ${this.reconnectAttempts} 次重连`)

    this.reconnectTimer = window.setTimeout(() => {
      this.connect()
    }, this.reconnectInterval)
  }

  private startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(this.heartbeatMessage)
      }
    }, this.heartbeatInterval)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private handleMessage(message: WebSocketMessage) {
    this.allMessageHandlers.forEach(handler => handler(message))
    const handlers = this.messageHandlers.get(message.type)
    if (handlers) {
      handlers.forEach(handler => handler(message))
    }
  }

  on(type: string, handler: MessageHandler): () => void {
    if (!this.messageHandlers.has(type)) {
      this.messageHandlers.set(type, new Set())
    }
    this.messageHandlers.get(type)!.add(handler)

    return () => {
      this.messageHandlers.get(type)?.delete(handler)
    }
  }

  onMessage(handler: (message: WebSocketMessage) => void): () => void {
    this.allMessageHandlers.add(handler)
    return () => {
      this.allMessageHandlers.delete(handler)
    }
  }

  off(type: string, handler?: MessageHandler) {
    if (handler) {
      this.messageHandlers.get(type)?.delete(handler)
    } else {
      this.messageHandlers.delete(type)
    }
  }

  send(message: WebSocketMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    } else {
      console.warn('[WebSocket] 连接未建立，消息发送失败:', message)
    }
  }

  close() {
    this.isManualClose = true
    this.stopHeartbeat()

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }

    if (this.ws) {
      this.ws.close()
      this.ws = null
    }

    this.isConnected.value = false
    this.messageHandlers.clear()
    this.allMessageHandlers.clear()
    this.reconnectAttempts = 0
  }
}

const wsInstances = new Map<string, LegacyWebSocket>()

export function useWebSocket(key: string, options?: Partial<WebSocketOptions>) {
  if (!wsInstances.has(key)) {
    const defaultOptions: WebSocketOptions = {
      url: `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`,
      ...options
    }
    wsInstances.set(key, new LegacyWebSocket(defaultOptions))
  }
  return wsInstances.get(key)!
}

export function destroyWebSocket(key: string) {
  const ws = wsInstances.get(key)
  if (ws) {
    ws.close()
    wsInstances.delete(key)
  }
}
