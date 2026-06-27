<template>
  <div class="code-preview-container">
    <div class="preview-header" v-if="showHeader">
      <div class="file-info">
        <el-icon><Document /></el-icon>
        <span class="file-name">{{ fileName || '代码预览' }}</span>
        <el-tag v-if="lineCount" size="small" type="info">{{ lineCount }} 行</el-tag>
      </div>
      <div class="preview-actions">
        <el-button-group size="small">
          <el-tooltip content="复制" placement="top">
            <el-button :icon="CopyDocument" @click="copyCode" />
          </el-tooltip>
          <el-tooltip content="下载" placement="top">
            <el-button :icon="Download" @click="downloadCode" />
          </el-tooltip>
          <el-tooltip content="展开/折叠" placement="top">
            <el-button :icon="isExpanded ? Fold : Expand" @click="toggleExpand" />
          </el-tooltip>
        </el-button-group>
      </div>
    </div>

    <div class="line-actions" v-if="showLineNumbers && enableLineActions">
      <el-dropdown @command="handleLineAction" trigger="click">
        <el-button size="small" text>
          <el-icon><Setting /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="wrap">
              自动换行 {{ lineWrapping ? '✓' : '' }}
            </el-dropdown-item>
            <el-dropdown-item command="copyLine">
              复制当前行
            </el-dropdown-item>
            <el-dropdown-item command="bookmark">
              切换书签 {{ bookmarkedLines.size > 0 ? `(${bookmarkedLines.size})` : '' }}
            </el-dropdown-item>
            <el-dropdown-item command="jumpToLine">
              跳转到行...
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="code-wrapper" :class="{ 'expanded': isExpanded }" ref="codeWrapper">
      <div v-if="loading" class="loading-overlay">
        <el-skeleton :rows="15" animated />
      </div>
      <div v-else-if="error" class="error-state">
        <el-empty description="加载失败" :image-size="60">
          <el-button type="primary" size="small" @click="$emit('retry')">重试</el-button>
        </el-empty>
      </div>
      <div v-else class="code-content">
        <pre class="code-pre"><code :class="`language-${language}`" v-html="highlightedCode" ref="codeRef"></code></pre>
      </div>
    </div>

    <div class="preview-footer" v-if="showFooter">
      <div class="footer-left">
        <el-tag size="small" type="info">{{ language.toUpperCase() }}</el-tag>
        <span v-if="encoding" class="encoding">{{ encoding }}</span>
      </div>
      <div class="footer-right">
        <el-button size="small" text @click="$emit('viewFullscreen')">
          <FullScreen /> 全屏查看
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'

interface Props {
  code: string
  fileName?: string
  language?: string
  encoding?: string
  maxHeight?: number
  showHeader?: boolean
  showFooter?: boolean
  showLineNumbers?: boolean
  loading?: boolean
  error?: boolean
  enableLineActions?: boolean
  lineWrapping?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  language: 'java',
  showHeader: true,
  showFooter: true,
  showLineNumbers: true,
  loading: false,
  error: false,
  enableLineActions: true,
  lineWrapping: false,
  maxHeight: 500
})

const emit = defineEmits<{
  retry: []
  viewFullscreen: []
  lineClick: [lineNumber: number]
}>()

const isExpanded = ref(true)
const bookmarkedLines = ref<Set<number>>(new Set())
const codeRef = ref<HTMLElement>()
const codeWrapper = ref<HTMLElement>()

const lineCount = computed(() => {
  return props.code ? props.code.split('\n').length : 0
})

const highlightedCode = computed(() => {
  if (!props.code) return ''
  try {
    return hljs.highlight(props.code, { language: props.language }).value
  } catch {
    return escapeHtml(props.code)
  }
})

function escapeHtml(text: string) {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

function copyCode() {
  navigator.clipboard.writeText(props.code).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

function downloadCode() {
  const blob = new Blob([props.code], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = props.fileName || 'code.txt'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}

function handleLineAction(command: string) {
  switch (command) {
    case 'wrap':
      emit('toggleWrapping', !props.lineWrapping)
      break
    case 'copyLine':
      ElMessage.info('请点击行号复制该行')
      break
    case 'bookmark':
      ElMessage.info('书签功能开发中')
      break
    case 'jumpToLine':
      jumpToLine()
      break
  }
}

async function jumpToLine() {
  const { value } = await ElMessageBox.prompt('请输入行号:', '跳转到行', {
    confirmButtonText: '跳转',
    cancelButtonText: '取消',
    inputPattern: /^\d+$/,
    inputErrorMessage: '请输入有效的行号'
  })

  const lineNum = parseInt(value)
  if (lineNum >= 1 && lineNum <= lineCount.value) {
    const codeElement = codeWrapper.value
    if (codeElement) {
      const lineHeight = 20
      codeElement.scrollTop = (lineNum - 1) * lineHeight
      ElMessage.success(`已跳转到第 ${lineNum} 行`)
    }
  } else {
    ElMessage.error(`行号超出范围 (1-${lineCount.value})`)
  }
}

onMounted(() => {
  nextTick(() => {
    hljs.highlightAll()
  })
})

watch(() => props.code, () => {
  nextTick(() => {
    hljs.highlightAll()
  })
})
</script>

<style scoped>
.code-preview-container {
  width: 100%;
  background: #282c34;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #3e4451;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #21252b;
  border-bottom: 1px solid #3e4451;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #abb2bf;
}

.file-name {
  color: #e6c07b;
  font-weight: 500;
}

.preview-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.line-actions {
  padding: 4px 16px;
  background: #21252b;
  border-bottom: 1px solid #3e4451;
}

.code-wrapper {
  max-height: 500px;
  overflow: auto;
  transition: max-height 0.3s ease;
  position: relative;
}

.code-wrapper.expanded {
  max-height: none;
}

.loading-overlay {
  padding: 20px;
  background: #282c34;
}

.error-state {
  padding: 40px;
  background: #282c34;
}

.code-content {
  min-height: 100px;
}

.code-pre {
  margin: 0;
  padding: 16px;
  overflow-x: auto;
  line-height: 1.6;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
}

.code-pre code {
  font-family: inherit;
  display: block;
}

.preview-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #21252b;
  border-top: 1px solid #3e4451;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.encoding {
  color: #7f848e;
  font-size: 12px;
}

.footer-right {
  color: #7f848e;
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: #21252b;
}

::-webkit-scrollbar-thumb {
  background: #4b5263;
  border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
  background: #5c6370;
}
</style>
