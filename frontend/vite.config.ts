import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import ElementPlus from 'unplugin-element-plus/vite'
import Icons from 'unplugin-icons/vite'
import IconsResolver from 'unplugin-icons/resolver'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ command }) => {
  const isBuild = command === 'build'

  return {
    plugins: [
      vue(),
      ElementPlus({}),
      Icons({ autoInstall: true, compiler: 'vue3' }),
      // 仅在生产构建时启用 PWA / Workbox，开发环境完全关闭，避免 SW 缓存干扰调试
      ...(isBuild
        ? [
            VitePWA({
              registerType: 'autoUpdate',
              includeAssets: ['favicon.ico', 'robots.txt'],
              manifest: {
                name: 'LegacyGraph - 代码知识图谱',
                short_name: 'LegacyGraph',
                description: '企业级代码分析与知识图谱平台',
                theme_color: '#5e5ce6',
                background_color: '#ffffff',
                icons: [
                  {
                    src: '/pwa-192x192.png',
                    sizes: '192x192',
                    type: 'image/png'
                  },
                  {
                    src: '/pwa-512x512.png',
                    sizes: '512x512',
                    type: 'image/png'
                  },
                  {
                    src: '/pwa-512x512.png',
                    sizes: '512x512',
                    type: 'image/png',
                    purpose: 'any maskable'
                  }
                ]
              },
              workbox: {
                globPatterns: ['**/*.{js,css,html,ico,png,svg}'],
                runtimeCaching: [
                  {
                    urlPattern: /\/api\/.*/,
                    handler: 'NetworkFirst',
                    options: {
                      cacheName: 'api-cache',
                      expiration: {
                        maxEntries: 100,
                        maxAgeSeconds: 60 * 60 * 24
                      },
                      cacheableResponse: {
                        statuses: [0, 200]
                      }
                    }
                  }
                ]
              },
              devOptions: {
                enabled: false
              }
            })
          ]
        : [])
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      open: true,
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            // 可视化库（G6/ECharts 体积大，独立 chunk 利用浏览器缓存）
            'vendor-g6': ['@antv/g6'],
            'vendor-echarts': ['echarts'],
            // UI 组件库
            'vendor-element-plus': ['element-plus'],
            // Vue 生态
            'vendor-vue': ['vue', 'vue-router', 'pinia'],
            // 通用工具
            'vendor-utils': ['axios', 'lodash-es', 'dayjs']
          }
        }
      },
      // 提高 chunk 大小警告阈值（默认 500KB）
      chunkSizeWarningLimit: 1000
    }
  }
})
