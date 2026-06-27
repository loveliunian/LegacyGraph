declare module 'diff'
declare module 'spark-md5'

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'element-plus/dist/index.css'
declare module '@element-plus/icons-vue'

declare module 'highlight.js'
declare module 'highlight.js/lib/languages/*'

interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string
  readonly VITE_API_BASE_URL: string
  readonly VITE_PWA_ENABLED: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
