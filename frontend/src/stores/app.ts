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
  primaryColor: '#6366F1',
  successColor: '#14B8A6',
  warningColor: '#F59E0B',
  dangerColor: '#EF4444',
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

const oldDefaultColors: Record<string, string> = {
  primaryColor: '#409eff',
  successColor: '#67c23a',
  warningColor: '#e6a23c',
  dangerColor: '#f56c6c'
}

function readThemeConfig(): ThemeConfig {
  const stored = localStorage.getItem(themeConfigStorageKey)
  if (!stored) {
    return { ...defaultThemeConfig }
  }

  try {
    const parsed = JSON.parse(stored)
    const hasOldDefaults = Object.entries(oldDefaultColors).some(
      ([key, value]) => parsed[key] === value
    )
    if (hasOldDefaults) {
      localStorage.setItem(themeConfigStorageKey, JSON.stringify(defaultThemeConfig))
      return { ...defaultThemeConfig }
    }
    return { ...defaultThemeConfig, ...parsed }
  } catch {
    return { ...defaultThemeConfig }
  }
}

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '')
  return [
    parseInt(h.substring(0, 2), 16),
    parseInt(h.substring(2, 4), 16),
    parseInt(h.substring(4, 6), 16)
  ]
}

function rgbToHex(r: number, g: number, b: number): string {
  const toHex = (n: number) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

function colorMix(color1: string, color2: string, weight: number): string {
  const [r1, g1, b1] = hexToRgb(color1)
  const [r2, g2, b2] = hexToRgb(color2)
  return rgbToHex(
    r1 * (1 - weight) + r2 * weight,
    g1 * (1 - weight) + g2 * weight,
    b1 * (1 - weight) + b2 * weight
  )
}

function setColorShades(baseVar: string, baseColor: string, isDark: boolean) {
  const html = document.documentElement
  const mixColor = isDark ? '#000000' : '#ffffff'
  const darkMixColor = isDark ? '#ffffff' : '#000000'
  const levels = [3, 5, 7, 8, 9] as const
  levels.forEach(level => {
    const weight = level / 10
    const shade = colorMix(baseColor, mixColor, weight)
    html.style.setProperty(`${baseVar}-light-${level}`, shade)
  })
  html.style.setProperty(`${baseVar}-dark-2`, colorMix(baseColor, darkMixColor, 0.2))
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
      const baseColor = themeConfig.value[key as keyof ThemeConfig]
      html.style.setProperty(cssVar, baseColor)
      setColorShades(cssVar, baseColor, dark)
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
