import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

export type LocaleType = 'zh-CN' | 'en-US'

const LOCALE_KEY = 'legacy_graph_locale'

const getDefaultLocale = (): LocaleType => {
  const savedLocale = localStorage.getItem(LOCALE_KEY)
  if (savedLocale && ['zh-CN', 'en-US'].includes(savedLocale)) {
    return savedLocale as LocaleType
  }
  const browserLang = navigator.language
  if (browserLang.startsWith('zh')) {
    return 'zh-CN'
  }
  return 'en-US'
}

export const locales = [
  { label: '简体中文', value: 'zh-CN' },
  { label: 'English', value: 'en-US' }
]

const i18n = createI18n({
  legacy: false,
  locale: getDefaultLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  },
  globalInjection: true,
  silentTranslationWarn: true,
  silentFallbackWarn: true
})

export function setLocale(locale: LocaleType) {
  if (i18n.mode === 'legacy') {
    i18n.global.locale = locale
  } else {
    (i18n.global.locale as any).value = locale
  }
  localStorage.setItem(LOCALE_KEY, locale)
  document.documentElement.setAttribute('lang', locale)
}

export function getLocale(): LocaleType {
  return i18n.global.locale as LocaleType
}

export function t(key: string, params?: Record<string, any>): string {
  return i18n.global.t(key, params)
}

export default i18n
