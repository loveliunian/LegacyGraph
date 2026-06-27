import { ref, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'

const BREAKPOINTS = {
  sm: 640,
  md: 768,
  lg: 1024,
  xl: 1280,
  xxl: 1536
}

export function useBreakpoint() {
  const width = ref(window.innerWidth)
  const appStore = useAppStore()

  const isMobile = ref(width.value < BREAKPOINTS.md)
  const isTablet = ref(width.value >= BREAKPOINTS.md && width.value < BREAKPOINTS.lg)
  const isDesktop = ref(width.value >= BREAKPOINTS.lg)
  const isSmallDesktop = ref(width.value >= BREAKPOINTS.lg && width.value < BREAKPOINTS.xl)
  const isLargeDesktop = ref(width.value >= BREAKPOINTS.xl)

  const update = () => {
    width.value = window.innerWidth
    isMobile.value = width.value < BREAKPOINTS.md
    isTablet.value = width.value >= BREAKPOINTS.md && width.value < BREAKPOINTS.lg
    isDesktop.value = width.value >= BREAKPOINTS.lg
    isSmallDesktop.value = width.value >= BREAKPOINTS.lg && width.value < BREAKPOINTS.xl
    isLargeDesktop.value = width.value >= BREAKPOINTS.xl

    if (isMobile.value) {
      appStore.setDevice('mobile')
    } else if (isTablet.value) {
      appStore.setDevice('tablet')
    } else {
      appStore.setDevice('desktop')
    }
  }

  onMounted(() => {
    window.addEventListener('resize', update)
    update()
  })

  onUnmounted(() => {
    window.removeEventListener('resize', update)
  })

  return {
    width,
    isMobile,
    isTablet,
    isDesktop,
    isSmallDesktop,
    isLargeDesktop,
    BREAKPOINTS
  }
}
