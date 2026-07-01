import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import tseslint from '@typescript-eslint/eslint-plugin'
import tsParser from '@typescript-eslint/parser'

// F-S2：启用 TS parser + @typescript-eslint 推荐规则，治理 any / console 等问题。
// 关键：对 .vue 文件必须保留 vue-eslint-parser 为外层 parser（由 pluginVue flat/recommended 提供），
// 仅将 tsParser 作为 <script> 块的内层 parser（parserOptions.parser）；
// 若对 .vue 直接设 parser: tsParser，会覆盖 vue-eslint-parser 导致 <template> 解析失败（'>' expected）。
const tsRules = {
  ...tseslint.configs.recommended.rules,
  '@typescript-eslint/no-explicit-any': 'warn',
  '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
  'no-console': ['warn', { allow: ['warn', 'error'] }],
  'no-undef': 'off' // TS 自身负责未定义变量检查
}

export default [
  {
    ignores: ['node_modules/**', 'dist/**']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  // .ts / .tsx：直接用 tsParser 作为 parser
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module'
      },
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    plugins: {
      '@typescript-eslint': tseslint
    },
    rules: tsRules
  },
  // .vue：外层 parser 仍为 vue-eslint-parser（继承自 pluginVue），tsParser 仅用于 <script> 块
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
        parser: tsParser
      },
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    plugins: {
      '@typescript-eslint': tseslint
    },
    rules: tsRules
  },
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-closing-bracket-newline': 'off'
    }
  }
]
