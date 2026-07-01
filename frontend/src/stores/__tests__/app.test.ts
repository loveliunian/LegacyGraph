import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAppStore } from '../app'

describe('useAppStore theme', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    document.documentElement.className = ''
    document.documentElement.removeAttribute('style')
    document.body.removeAttribute('data-theme')
  })

  it('applies auto theme from the current system color scheme', () => {
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: true,
      media: '(prefers-color-scheme: dark)',
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn()
    } as unknown as MediaQueryList)

    const store = useAppStore()

    store.setTheme('auto')

    expect(store.theme).toBe('auto')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(document.body.getAttribute('data-theme')).toBe('dark')
  })

  it('writes theme color config to Element Plus CSS variables', () => {
    const store = useAppStore()

    store.updateThemeConfig({ primaryColor: '#1890ff' })

    expect(store.themeConfig.primaryColor).toBe('#1890ff')
    expect(document.documentElement.style.getPropertyValue('--el-color-primary')).toBe('#1890ff')
  })
})
