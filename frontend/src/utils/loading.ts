import { ElLoading } from 'element-plus'
import type { LoadingInstance } from 'element-plus/es/components/loading/src/loading'

let loadingInstance: LoadingInstance | null = null
let requestCount = 0

/**
 * 显示全局 Loading
 * @param text Loading 文字
 */
export function showLoading(text = '加载中...') {
  if (requestCount === 0) {
    loadingInstance = ElLoading.service({
      lock: true,
      text,
      background: 'rgba(255, 255, 255, 0.7)',
      fullscreen: true
    })
  }
  requestCount++
}

/**
 * 隐藏全局 Loading
 */
export function hideLoading() {
  requestCount--
  if (requestCount <= 0) {
    requestCount = 0
    loadingInstance?.close()
    loadingInstance = null
  }
}

/**
 * 强制关闭 Loading（不管计数）
 */
export function forceHideLoading() {
  requestCount = 0
  loadingInstance?.close()
  loadingInstance = null
}

/**
 * 包装 Promise，自动显示/隐藏 Loading
 * @param promise Promise
 * @param loadingText Loading 文字
 */
export async function withLoading<T>(promise: Promise<T>, loadingText?: string): Promise<T> {
  showLoading(loadingText)
  try {
    return await promise
  } finally {
    hideLoading()
  }
}

/**
 * 局部 Loading 包装器
 * @param target 目标元素或选择器
 * @param text Loading文字
 */
export function createLocalLoading(target: HTMLElement | string, text?: string) {
  return ElLoading.service({
    target,
    lock: true,
    text,
    background: 'rgba(255, 255, 255, 0.7)'
  })
}
