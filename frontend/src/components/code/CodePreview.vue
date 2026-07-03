<template>
  <div class="code-preview-container">
    <div
      v-if="showHeader"
      class="preview-header">
      <div class="file-info">
        <el-icon><Document /></el-icon>
        <span class="file-name">{{ fileName || '代码预览' }}</span>
        <el-tag
          v-if="lineCount > 0"
          size="small"
          type="info">
          {{ lineCount }} 行
        </el-tag>
        <el-tag
          size="small"
          type="warning">
          {{ language.toUpperCase() }}
        </el-tag>
      </div>
      <div class="preview-actions">
        <el-button-group size="small">
          <el-tooltip
            content="搜索"
            placement="top">
            <el-button
              :icon="Search"
              @click="toggleSearch" />
          </el-tooltip>
          <el-tooltip
            content="跳转到行"
            placement="top">
            <el-button
              :icon="Position"
              @click="jumpToLine" />
          </el-tooltip>
          <el-tooltip
            content="复制"
            placement="top">
            <el-button
              :icon="CopyDocument"
              @click="copyCode" />
          </el-tooltip>
          <el-tooltip
            content="下载"
            placement="top">
            <el-button
              :icon="Download"
              @click="downloadCode" />
          </el-tooltip>
          <el-tooltip
            content="全屏"
            placement="top">
            <el-button
              :icon="FullScreen"
              @click="toggleFullscreen" />
          </el-tooltip>
        </el-button-group>
        <el-button-group size="small">
          <el-tooltip
            content="自动换行"
            placement="top">
            <el-button
              :icon="Sort"
              :type="lineWrapping ? 'primary' : ''"
              @click="lineWrapping = !lineWrapping" />
          </el-tooltip>
          <el-tooltip
            content="显示行号"
            placement="top">
            <el-button
              :icon="List"
              :type="showLineNumbers ? 'primary' : ''"
              @click="showLineNumbers = !showLineNumbers" />
          </el-tooltip>
        </el-button-group>
      </div>
    </div>

    <div
      v-if="showSearchBar"
      class="search-bar">
      <el-input
        v-model="searchText"
        placeholder="搜索..."
        size="small"
        clearable
        :prefix-icon="Search"
        @input="handleSearch"
        @keyup.enter="findNext"
      >
        <template #append>
          <el-button
            size="small"
            :disabled="matches.length === 0"
            @click="findPrevious">
            <el-icon><ArrowUp /></el-icon>
          </el-button>
          <el-button
            size="small"
            :disabled="matches.length === 0"
            @click="findNext">
            <el-icon><ArrowDown /></el-icon>
          </el-button>
        </template>
      </el-input>
      <span
        v-if="matches.length > 0"
        class="search-stats">
        {{ currentMatchIndex + 1 }} / {{ matches.length }}
      </span>
    </div>

    <div
      ref="codeWrapper"
      class="code-wrapper">
      <div
        v-if="loading"
        class="loading-overlay">
        <el-skeleton
          :rows="15"
          animated />
      </div>
      <div
        v-else-if="error"
        class="error-state">
        <el-empty
          description="加载失败"
          :image-size="60">
          <el-button
            type="primary"
            size="small"
            @click="$emit('retry')">
            重试
          </el-button>
        </el-empty>
      </div>
      <div
        v-else
        class="code-content"
        :class="{ 'line-numbers-hidden': !showLineNumbers }">
        <pre
          class="code-pre"
          :class="{ 'wrap': lineWrapping }">
          <code
ref="codeRef"
:class="`language-${language}`"
v-html="highlightedCode" />
        </pre>
      </div>
    </div>

    <div
      v-if="showFooter"
      class="preview-footer">
      <div class="footer-left">
        <span
          v-if="encoding"
          class="encoding">{{ encoding }}</span>
        <span
          v-if="fileSize"
          class="size">{{ formatFileSize(fileSize) }}</span>
      </div>
      <div class="footer-right">
        <span v-if="currentLine > 0">行 {{ currentLine }}, 列 {{ currentColumn }}</span>
        <span v-if="matches.length > 0">{{ matches.length }} 个匹配</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'
import {
  Document,
  Search,
  Position,
  CopyDocument,
  Download,
  FullScreen,
  Sort,
  List,
  ArrowUp,
  ArrowDown
} from '@element-plus/icons-vue'

interface Props {
  code: string
  fileName?: string
  language?: string
  encoding?: string
  fileSize?: number
  maxHeight?: number
  showHeader?: boolean
  showFooter?: boolean
  showLineNumbers?: boolean
  showSearch?: boolean
  loading?: boolean
  error?: boolean
  lineWrapping?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  code: '',
  fileName: '',
  language: 'javascript',
  encoding: 'UTF-8',
  fileSize: 0,
  maxHeight: 500,
  showHeader: true,
  showFooter: true,
  showLineNumbers: true,
  showSearch: true,
  loading: false,
  error: false,
  lineWrapping: false
})

defineEmits<{
  retry: []
  lineClick: [lineNumber: number]
}>()

const codeRef = ref<HTMLElement>()
const codeWrapper = ref<HTMLElement>()
const showSearchBar = ref(false)
const searchText = ref('')
const matches = ref<number[]>([])
const currentMatchIndex = ref(0)
const currentLine = ref(0)
const currentColumn = ref(0)
const isFullscreen = ref(false)
const showLineNumbers = ref(props.showLineNumbers)
const lineWrapping = ref(props.lineWrapping)

const lineCount = computed(() => {
  return props.code ? props.code.split('\n').length : 0
})

const highlightedCode = computed(() => {
  if (!props.code) return ''
  try {
    let result = hljs.highlight(props.code, { language: props.language }).value
    if (searchText.value && matches.value.length > 0) {
      const regex = new RegExp(`(${escapeRegExp(searchText.value)})`, 'gi')
      result = result.replace(regex, '<mark class="search-match">$1</mark>')
    }
    return result
  } catch {
    return escapeHtml(props.code)
  }
})

function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

function toggleSearch() {
  showSearchBar.value = !showSearchBar.value
  if (!showSearchBar.value) {
    searchText.value = ''
    matches.value = []
  }
}

function handleSearch() {
  if (!searchText.value) {
    matches.value = []
    currentMatchIndex.value = 0
    return
  }

  const lines = props.code.split('\n')
  const found: number[] = []
  const searchLower = searchText.value.toLowerCase()

  lines.forEach((line, index) => {
    if (line.toLowerCase().includes(searchLower)) {
      found.push(index + 1)
    }
  })

  matches.value = found
  currentMatchIndex.value = 0

  if (found.length > 0) {
    scrollToLine(found[0])
  }
}

function findNext() {
  if (matches.value.length === 0) return
  currentMatchIndex.value = (currentMatchIndex.value + 1) % matches.value.length
  scrollToLine(matches.value[currentMatchIndex.value])
}

function findPrevious() {
  if (matches.value.length === 0) return
  currentMatchIndex.value = (currentMatchIndex.value - 1 + matches.value.length) % matches.value.length
  scrollToLine(matches.value[currentMatchIndex.value])
}

function scrollToLine(lineNumber: number) {
  if (!codeWrapper.value) return
  const lineHeight = 20
  codeWrapper.value.scrollTop = (lineNumber - 1) * lineHeight
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
    scrollToLine(lineNum)
    ElMessage.success(`已跳转到第 ${lineNum} 行`)
  } else {
    ElMessage.error(`行号超出范围 (1-${lineCount.value})`)
  }
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

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    codeWrapper.value?.requestFullscreen()
    isFullscreen.value = true
  } else {
    document.exitFullscreen()
    isFullscreen.value = false
  }
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
}

watch(() => props.code, () => {
  nextTick(() => {
    hljs.highlightAll()
  })
})

watch(searchText, () => {
  handleSearch()
})

onMounted(() => {
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
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  border: 1px solid #3e4451;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #21252b;
  border-bottom: 1px solid #3e4451;
  flex-wrap: wrap;
  gap: 12px;
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
  gap: 8px;
  align-items: center;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: #21252b;
  border-bottom: 1px solid #3e4451;
}

.search-bar .el-input {
  flex: 1;
}

.search-stats {
  color: #abb2bf;
  font-size: 13px;
  white-space: nowrap;
}

.code-wrapper {
  max-height: v-bind('maxHeight + "px"');
  overflow: auto;
  position: relative;
}

.code-wrapper::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.code-wrapper::-webkit-scrollbar-track {
  background: #21252b;
}

.code-wrapper::-webkit-scrollbar-thumb {
  background: #4b5263;
  border-radius: 5px;
}

.code-wrapper::-webkit-scrollbar-thumb:hover {
  background: #5c6370;
}

.loading-overlay {
  padding: 20px;
}

.error-state {
  padding: 60px 20px;
}

.code-content {
  min-height: 100px;
  position: relative;
}

.code-pre {
  margin: 0;
  padding: 16px 0;
  overflow-x: auto;
  line-height: 1.6;
  font-size: 13px;
  counter-reset: line;
}

.code-pre.wrap {
  white-space: pre-wrap;
  word-wrap: break-word;
}

.code-pre code {
  font-family: inherit;
  display: block;
  padding: 0 16px;
}

.code-pre code::before {
  counter-increment: line;
  content: counter(line);
  display: inline-block;
  width: 45px;
  margin-right: 16px;
  padding-right: 8px;
  text-align: right;
  color: #5c6370;
  border-right: 1px solid #3e4451;
  user-select: none;
}

.line-numbers-hidden .code-pre code::before {
  display: none;
}

.search-match {
  background: #e06c75;
  color: #282c34;
  padding: 1px 4px;
  border-radius: 2px;
}

.preview-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #21252b;
  border-top: 1px solid #3e4451;
  font-size: 12px;
  color: #5c6370;
}

.footer-left,
.footer-right {
  display: flex;
  gap: 16px;
  align-items: center;
}

.encoding,
.size {
  color: #5c6370;
}
</style>
