import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/variables.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import i18n, { getLocale } from './locales'
import { useAppStore } from './stores/app'

const app = createApp(App)
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)

// 在挂载之前初始化主题（仅操作 document.documentElement，不依赖 DOM 挂载）
const appStore = useAppStore()
appStore.initTheme()

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component as any)
}

app.use(i18n)
app.use(router)

const currentLocale = getLocale()
app.use(ElementPlus, {
  locale: currentLocale === 'zh-CN' ? zhCn : en
})

app.mount('#app')
