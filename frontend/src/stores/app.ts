import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'auto'

export interface ThemeConfig {
  primaryColor: string
  successColor: string
  warningColor: string
  dangerColor: string
  infoColor: string
}

const defaultThemeConfig: ThemeConfig = {
  primaryColor: '#409eff',
  successColor: '#67c23a',
  warningColor: '#e6a23c',
  dangerColor: '#f56c6c',
  infoColor: '#909399'
}

const themeCssVars: Record<keyof ThemeConfig, string> = {
  primaryColor: '--el-color-primary',
  successColor: '--el-color-success',
  warningColor: '--el-color-warning',
  dangerColor: '--el-color-danger',
  infoColor: '--el-color-info'
}

const themeConfigStorageKey = 'legacy-graph-theme-config'

function readThemeConfig(): ThemeConfig {
  const stored = localStorage.getItem(themeConfigStorageKey)
  if (!stored) {
    return { ...defaultThemeConfig }
  }

  try {
    return { ...defaultThemeConfig, ...JSON.parse(stored) }
  } catch {
    return { ...defaultThemeConfig }
  }
}

export const useAppStore = defineStore('app', () => {
  const theme = ref<ThemeMode>((localStorage.getItem('theme') as ThemeMode) || 'light')
  const themeConfig = ref<ThemeConfig>(readThemeConfig())
  const sidebarOpened = ref(localStorage.getItem('sidebarOpened') !== 'false')
  const sidebarWidth = ref(240)
  const collapsedWidth = ref(64)
  const device = ref<'desktop' | 'tablet' | 'mobile'>('desktop')

  const isMobile = computed(() => device.value === 'mobile')
  const isTablet = computed(() => device.value === 'tablet' || device.value === 'mobile')
  const isDark = computed(() => resolveDarkTheme(theme.value))

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

  function resolveDarkTheme(mode: ThemeMode) {
    if (mode === 'auto') {
      return window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    return mode === 'dark'
  }

  function applyTheme(mode: ThemeMode = theme.value) {
    const html = document.documentElement
    const dark = resolveDarkTheme(mode)
    if (dark) {
      html.classList.add('dark')
      document.body.setAttribute('data-theme', 'dark')
    } else {
      html.classList.remove('dark')
      document.body.setAttribute('data-theme', 'light')
    }

    Object.entries(themeCssVars).forEach(([key, cssVar]) => {
      html.style.setProperty(cssVar, themeConfig.value[key as keyof ThemeConfig])
    })
  }

  function updateThemeConfig(newConfig: Partial<ThemeConfig>) {
    themeConfig.value = { ...themeConfig.value, ...newConfig }
    localStorage.setItem(themeConfigStorageKey, JSON.stringify(themeConfig.value))
    applyTheme()
  }

  function resetThemeConfig() {
    themeConfig.value = { ...defaultThemeConfig }
    localStorage.setItem(themeConfigStorageKey, JSON.stringify(themeConfig.value))
    applyTheme()
  }

  function getThemeModeLabel() {
    const labels: Record<ThemeMode, string> = {
      light: '浅色模式',
      dark: '深色模式',
      auto: '跟随系统'
    }
    return labels[theme.value]
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
    applyTheme()
  }

  return {
    theme,
    themeConfig,
    sidebarOpened,
    sidebarWidth,
    collapsedWidth,
    device,
    isMobile,
    isTablet,
    isDark,
    currentSidebarWidth,
    setTheme,
    toggleTheme,
    applyTheme,
    updateThemeConfig,
    resetThemeConfig,
    getThemeModeLabel,
    toggleSidebar,
    setSidebarOpened,
    setDevice,
    initTheme
  }
}, {
  persist: {
    key: 'legacy-graph-app',
    storage: localStorage,
    paths: ['theme', 'themeConfig', 'sidebarOpened']
  }
})
