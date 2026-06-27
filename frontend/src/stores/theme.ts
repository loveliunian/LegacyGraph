import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'auto'

export interface ThemeConfig {
  primaryColor: string
  successColor: string
  warningColor: string
  dangerColor: string
  infoColor: string
}

const defaultConfig: ThemeConfig = {
  primaryColor: '#409eff',
  successColor: '#67c23a',
  warningColor: '#e6a23c',
  dangerColor: '#f56c6c',
  infoColor: '#909399'
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>('light')
  const config = ref<ThemeConfig>({ ...defaultConfig })

  const isDark = computed(() => {
    if (mode.value === 'auto') {
      return window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    return mode.value === 'dark'
  })

  const cssVars = computed(() => ({
    '--el-color-primary': config.value.primaryColor,
    '--el-color-success': config.value.successColor,
    '--el-color-warning': config.value.warningColor,
    '--el-color-danger': config.value.dangerColor,
    '--el-color-info': config.value.infoColor
  }))

  function setMode(newMode: ThemeMode) {
    mode.value = newMode
    applyTheme()
  }

  function toggleMode() {
    if (mode.value === 'light') {
      mode.value = 'dark'
    } else if (mode.value === 'dark') {
      mode.value = 'auto'
    } else {
      mode.value = 'light'
    }
    applyTheme()
  }

  function updateConfig(newConfig: Partial<ThemeConfig>) {
    config.value = { ...config.value, ...newConfig }
    applyTheme()
  }

  function resetConfig() {
    config.value = { ...defaultConfig }
    applyTheme()
  }

  function applyTheme() {
    const root = document.documentElement

    if (isDark.value) {
      root.classList.add('dark')
      document.body.setAttribute('data-theme', 'dark')
    } else {
      root.classList.remove('dark')
      document.body.setAttribute('data-theme', 'light')
    }

    Object.entries(cssVars.value).forEach(([key, value]) => {
      document.documentElement.style.setProperty(key, value)
    })
  }

  function getModeLabel(): string {
    const labels: Record<ThemeMode, string> = {
      light: '浅色模式',
      dark: '深色模式',
      auto: '跟随系统'
    }
    return labels[mode.value]
  }

  return {
    mode,
    config,
    isDark,
    setMode,
    toggleMode,
    updateConfig,
    resetConfig,
    applyTheme,
    getModeLabel
  }
}, {
  persist: {
    key: 'legacy-graph-theme',
    storage: localStorage,
    paths: ['mode', 'config']
  }
})
