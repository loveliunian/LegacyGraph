import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      exclude: [...configDefaults.exclude, 'tests/e2e/**/*'],
      root: fileURLToPath(new URL('./', import.meta.url)),
      globals: true,
      setupFiles: ['./tests/setup.ts'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'json', 'html'],
        include: ['src/**/*.{ts,vue}'],
        exclude: ['src/**/*.d.ts', 'src/locales/**']
      },
      server: {
        deps: {
          inline: ['@vue-flow/core', 'element-plus']
        }
      }
    }
  })
)
