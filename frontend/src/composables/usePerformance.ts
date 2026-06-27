import { onMounted, onUnmounted, ref } from 'vue'

interface PerformanceMetrics {
  fp: number
  fcp: number
  lcp: number
  cls: number
  fid: number
  ttfb: number
}

interface PerformanceOptions {
  reportCallback?: (metrics: Partial<PerformanceMetrics>) => void
  enableConsole?: boolean
}

const metrics = ref<Partial<PerformanceMetrics>>({})
let observer: PerformanceObserver | null = null

export function usePerformance(options: PerformanceOptions = {}) {
  const { reportCallback, enableConsole = true } = options

  const reportMetrics = (newMetrics: Partial<PerformanceMetrics>) => {
    Object.assign(metrics.value, newMetrics)
    if (enableConsole) {
      console.table(metrics.value)
    }
    reportCallback?.(metrics.value)
  }

  const observeLCP = () => {
    if ('PerformanceObserver' in window) {
      const lcpObserver = new PerformanceObserver((entryList) => {
        const entries = entryList.getEntries()
        const lastEntry = entries[entries.length - 1] as PerformanceEventTiming
        reportMetrics({ lcp: lastEntry.startTime })
      })
      lcpObserver.observe({ entryTypes: ['largest-contentful-paint'] })
      return lcpObserver
    }
    return null
  }

  const observeFID = () => {
    if ('PerformanceObserver' in window) {
      const fidObserver = new PerformanceObserver((entryList) => {
        const entries = entryList.getEntries()
        entries.forEach((entry) => {
          const perfEntry = entry as PerformanceEventTiming
          reportMetrics({ fid: perfEntry.processingStart - perfEntry.startTime })
        })
      })
      fidObserver.observe({ entryTypes: ['first-input'] })
      return fidObserver
    }
    return null
  }

  const observeCLS = () => {
    if ('PerformanceObserver' in window) {
      let clsValue = 0
      const clsObserver = new PerformanceObserver((entryList) => {
        const entries = entryList.getEntries()
        entries.forEach((entry) => {
          if (!(entry as any).hadRecentInput) {
            clsValue += (entry as any).value
          }
        })
        reportMetrics({ cls: clsValue })
      })
      clsObserver.observe({ entryTypes: ['layout-shift'] })
      return clsObserver
    }
    return null
  }

  const getFP = () => {
    if ('performance' in window) {
      const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming
      if (navigation) {
        reportMetrics({
          fp: (performance as any).timeOrigin
            ? performance.now() + performance.timeOrigin
            : (performance as any).timing.responseEnd
        })
      }
    }
  }

  const getFCP = () => {
    if ('PerformanceObserver' in window) {
      const fcpObserver = new PerformanceObserver((entryList) => {
        const entries = entryList.getEntries()
        entries.forEach((entry) => {
          if (entry.name === 'first-contentful-paint') {
            reportMetrics({ fcp: entry.startTime })
          }
        })
      })
      fcpObserver.observe({ entryTypes: ['paint'] })
      return fcpObserver
    }
    return null
  }

  const getTTFB = () => {
    if ('performance' in window) {
      const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming
      if (navigation) {
        reportMetrics({ ttfb: navigation.responseStart - navigation.requestStart })
      }
    }
  }

  const disconnect = () => {
    observer?.disconnect()
  }

  onMounted(() => {
    getFP()
    getFCP()
    getTTFB()
    observeLCP()
    observeFID()
    observeCLS()
  })

  onUnmounted(() => {
    disconnect()
  })

  return {
    metrics,
    disconnect
  }
}

export function measurePerformance(label: string, fn: () => any) {
  const start = performance.now()
  const result = fn()
  const end = performance.now()
  console.log(`[Performance] ${label}: ${(end - start).toFixed(2)}ms`)
  return result
}

export async function measurePerformanceAsync(label: string, fn: () => Promise<any>) {
  const start = performance.now()
  const result = await fn()
  const end = performance.now()
  console.log(`[Performance] ${label}: ${(end - start).toFixed(2)}ms`)
  return result
}

export default usePerformance
