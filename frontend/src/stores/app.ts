import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export type ThemeMode = 'light' | 'dark'

export const useAppStore = defineStore('app', () => {
  const theme = ref<ThemeMode>((localStorage.getItem('theme') as ThemeMode) || 'light')
  const sidebarOpened = ref(localStorage.getItem('sidebarOpened') !== 'false')
  const sidebarWidth = ref(240)
  const collapsedWidth = ref(64)
  const device = ref<'desktop' | 'tablet' | 'mobile'>('desktop')

  const isMobile = computed(() => device.value === 'mobile')
  const isTablet = computed(() => device.value === 'tablet' || device.value === 'mobile')

  const currentSidebarWidth = computed(() => {
    return sidebarOpened.value ? sidebarWidth.value : collapsedWidth.value
  })

  function setTheme(mode: ThemeMode) {
    theme.value = mode
    localStorage.setItem('theme', mode)
    applyTheme(mode)
  }

  function toggleTheme() {
    setTheme(theme.value === 'light' ? 'dark' : 'light')
  }

  function applyTheme(mode: ThemeMode) {
    const html = document.documentElement
    if (mode === 'dark') {
      html.classList.add('dark')
    } else {
      html.classList.remove('dark')
    }
  }

  function toggleSidebar() {
    sidebarOpened.value = !sidebarOpened.value
    localStorage.setItem('sidebarOpened', String(sidebarOpened.value))
  }

  function setSidebarOpened(opened: boolean) {
    sidebarOpened.value = opened
    localStorage.setItem('sidebarOpened', String(opened))
  }

  function setDevice(newDevice: 'desktop' | 'tablet' | 'mobile') {
    device.value = newDevice
    if (newDevice === 'mobile') {
      setSidebarOpened(false)
    }
  }

  function initTheme() {
    applyTheme(theme.value)
  }

  return {
    theme,
    sidebarOpened,
    sidebarWidth,
    collapsedWidth,
    device,
    isMobile,
    isTablet,
    currentSidebarWidth,
    setTheme,
    toggleTheme,
    toggleSidebar,
    setSidebarOpened,
    setDevice,
    initTheme
  }
}, {
  persist: {
    key: 'legacy-graph-app',
    storage: localStorage,
    paths: ['theme', 'sidebarOpened']
  }
})
