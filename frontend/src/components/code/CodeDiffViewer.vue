<template>
  <div class="code-diff-container">
    <div class="diff-header" v-if="showHeader">
      <div class="file-info">
        <el-icon><Document /></el-icon>
        <span class="file-name">{{ fileName || '代码对比' }}</span>
        <el-tag v-if="changeType" size="small" :type="getChangeTypeTag(changeType)">
          {{ getChangeTypeText(changeType) }}
        </el-tag>
      </div>
      <div class="diff-actions">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="split">分栏对比</el-radio-button>
          <el-radio-button value="unified">合并对比</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="toggleLineNumbers">
          <el-tooltip content="显示/隐藏行号" placement="top">
            <Sort />
          </el-tooltip>
        </el-button>
        <el-button size="small" @click="copyContent">
          <el-tooltip content="复制" placement="top">
            <CopyDocument />
          </el-tooltip>
        </el-button>
      </div>
    </div>

    <div class="diff-stats" v-if="showStats && diffStats">
      <span class="stat-item additions">+ {{ diffStats.additions }}</span>
      <span class="stat-item deletions">- {{ diffStats.deletions }}</span>
      <span class="stat-item unchanged">  {{ diffStats.unchanged }} 行未变</span>
    </div>

    <div class="diff-content" ref="diffContainer">
      <div v-if="loading" class="loading-skeleton">
        <el-skeleton :rows="20" animated />
      </div>
      <div v-else-if="error" class="error-message">
        <el-empty description="加载失败" image-size="80">
          <el-button type="primary" @click="$emit('retry')">重试</el-button>
        </el-empty>
      </div>
      <div v-else-if="!oldContent && !newContent" class="empty-message">
        <el-empty description="暂无代码可对比" image-size="80" />
      </div>
      <div v-else class="code-diff-wrapper">
        <table class="diff-table" :class="{ 'split-view': viewMode === 'split' }">
          <thead v-if="viewMode === 'split'">
            <tr>
              <th class="line-num-col line-num-old">行号</th>
              <th class="code-col code-old">旧版本</th>
              <th class="line-num-col line-num-new">行号</th>
              <th class="code-col code-new">新版本</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(line, index) in diffLines"
              :key="index"
              class="diff-line"
              :class="getLineClass(line)"
              @mouseenter="hoverLine = index"
              @mouseleave="hoverLine = -1"
            >
              <template v-if="viewMode === 'split'">
                <td class="line-num line-num-old" v-if="line.oldLineNum">
                  <span>{{ line.oldLineNum }}</span>
                </td>
                <td class="line-num line-num-empty" v-else></td>
                <td class="code-cell code-old">
                  <pre><code v-html="highlightSyntax(line.oldContent)"></code></pre>
                </td>
                <td class="line-num line-num-new" v-if="line.newLineNum">
                  <span>{{ line.newLineNum }}</span>
                </td>
                <td class="line-num line-num-empty" v-else></td>
                <td class="code-cell code-new">
                  <pre><code v-html="highlightSyntax(line.newContent)"></code></pre>
                </td>
              </template>
              <template v-else>
                <td class="line-num line-num-unified">
                  <span class="old-num">{{ line.oldLineNum }}</span>
                  <span class="new-num">{{ line.newLineNum }}</span>
                </td>
                <td class="marker-cell">
                  <span class="marker" :class="line.type">{{ getMarker(line.type) }}</span>
                </td>
                <td class="code-cell">
                  <pre><code v-html="highlightSyntax(line.content)"></code></pre>
                </td>
              </template>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'

interface DiffStats {
  additions: number
  deletions: number
  unchanged: number
}

interface DiffLine {
  oldLineNum?: number
  newLineNum?: number
  oldContent?: string
  newContent?: string
  content?: string
  type: 'add' | 'remove' | 'unchanged' | 'context' | 'hunk'
}

const props = withDefaults(defineProps<{
  oldContent: string
  newContent: string
  fileName?: string
  language?: string
  changeType?: 'add' | 'remove' | 'modify' | 'rename'
  showHeader?: boolean
  showStats?: boolean
  showLineNumbers?: boolean
  loading?: boolean
  error?: boolean
}>(), {
  showHeader: true,
  showStats: true,
  showLineNumbers: true,
  loading: false,
  error: false
})

const emit = defineEmits<{
  retry: []
}>()

const viewMode = ref<'split' | 'unified'>('split')
const showLineNumbers = ref(props.showLineNumbers)
const hoverLine = ref(-1)
const diffContainer = ref<HTMLElement>()

const highlightSyntax = (content: string) => {
  if (!content) return ''
  const lang = props.language || 'java'
  try {
    return hljs.highlight(content, { language: lang }).value
  } catch {
    return content
  }
}

const diffStats = computed<DiffStats>(() => {
  const oldLines = props.oldContent?.split('\n') || []
  const newLines = props.newContent?.split('\n') || []
  let additions = 0
  let deletions = 0
  const unchanged = Math.min(oldLines.length, newLines.length)

  newLines.forEach((line, i) => {
    if (!oldLines.includes(line)) additions++
  })

  oldLines.forEach((line, i) => {
    if (!newLines.includes(line)) deletions++
  })

  return { additions, deletions, unchanged }
})

const diffLines = computed<DiffLine[]>(() => {
  const oldLines = props.oldContent?.split('\n') || []
  const newLines = props.newContent?.split('\n') || []
  const lines: DiffLine[] = []

  const maxLen = Math.max(oldLines.length, newLines.length)

  for (let i = 0; i < maxLen; i++) {
    const oldLine = oldLines[i]
    const newLine = newLines[i]

    if (oldLine === undefined && newLine !== undefined) {
      lines.push({
        newLineNum: i + 1,
        oldContent: '',
        newContent: newLine,
        content: newLine,
        type: 'add'
      })
    } else if (newLine === undefined && oldLine !== undefined) {
      lines.push({
        oldLineNum: i + 1,
        oldContent: oldLine,
        newContent: '',
        content: oldLine,
        type: 'remove'
      })
    } else if (oldLine !== newLine) {
      lines.push({
        oldLineNum: i + 1,
        oldContent: oldLine,
        content: oldLine,
        type: 'remove'
      })
      lines.push({
        newLineNum: i + 1,
        newContent: newLine,
        content: newLine,
        type: 'add'
      })
    } else {
      lines.push({
        oldLineNum: i + 1,
        newLineNum: i + 1,
        oldContent: oldLine,
        newContent: newLine,
        content: oldLine,
        type: 'unchanged'
      })
    }
  }

  return lines
})

function getLineClass(line: DiffLine) {
  return {
    'line-add': line.type === 'add',
    'line-remove': line.type === 'remove',
    'line-unchanged': line.type === 'unchanged',
    'line-hover': hoverLine === diffLines.value.indexOf(line)
  }
}

function getMarker(type: string) {
  const markers: Record<string, string> = {
    add: '+',
    remove: '-',
    unchanged: ' ',
    context: ' '
  }
  return markers[type] || ' '
}

function getChangeTypeTag(type: string) {
  const types: Record<string, string> = {
    add: 'success',
    remove: 'danger',
    modify: 'warning',
    rename: 'info'
  }
  return types[type] || 'info'
}

function getChangeTypeText(type: string) {
  const texts: Record<string, string> = {
    add: '新增',
    remove: '删除',
    modify: '修改',
    rename: '重命名'
  }
  return texts[type] || type
}

function toggleLineNumbers() {
  showLineNumbers.value = !showLineNumbers.value
}

function copyContent() {
  const content = props.newContent || props.oldContent
  navigator.clipboard.writeText(content).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

onMounted(() => {
  hljs.highlightAll()
})

watch([() => props.oldContent, () => props.newContent], () => {
  setTimeout(() => hljs.highlightAll(), 100)
})
</script>

<style scoped>
.code-diff-container {
  width: 100%;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.diff-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f8f9fa;
  border-bottom: 1px solid #ebeef5;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-name {
  font-weight: 500;
  color: #303133;
}

.diff-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.diff-stats {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 16px;
  background: #fdfdfd;
  border-bottom: 1px solid #ebeef5;
  font-size: 13px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.stat-item.additions {
  color: #67c23a;
  font-weight: 600;
}

.stat-item.deletions {
  color: #f56c6c;
  font-weight: 600;
}

.stat-item.unchanged {
  color: #909399;
}

.diff-content {
  overflow-x: auto;
  overflow-y: auto;
  max-height: 600px;
}

.loading-skeleton {
  padding: 20px;
}

.error-message,
.empty-message {
  padding: 40px;
}

.code-diff-wrapper {
  min-width: 100%;
}

.diff-table {
  width: 100%;
  border-collapse: collapse;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.diff-table.split-view .line-num-col {
  width: 60px;
}

.diff-table.split-view .code-col {
  width: 45%;
}

th {
  background: #f5f7fa;
  padding: 8px 12px;
  text-align: left;
  font-weight: 500;
  color: #606266;
  border-bottom: 1px solid #ebeef5;
}

.line-num-col {
  background: #f8f9fa;
  border-right: 1px solid #ebeef5;
  user-select: none;
}

.line-num {
  color: #909399;
  font-size: 12px;
  text-align: right;
  padding: 4px 12px;
  min-width: 50px;
  border-right: 1px solid #ebeef5;
}

.line-num-empty {
  background: #fafafa;
}

.line-num-unified {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.marker-cell {
  width: 20px;
  padding: 4px 8px;
  text-align: center;
  font-weight: bold;
  background: #f8f9fa;
  border-right: 1px solid #ebeef5;
}

.marker {
  display: inline-block;
  width: 16px;
  text-align: center;
}

.code-cell {
  padding: 2px 12px;
  white-space: pre;
  vertical-align: top;
}

.diff-line {
  transition: background 0.2s;
}

.diff-line:hover {
  background: #f5f7fa;
}

.line-add {
  background: #f0f9eb;
}

.line-add .code-cell {
  background: #f0f9eb;
}

.line-remove {
  background: #fef0f0;
}

.line-remove .code-cell {
  background: #fef0f0;
}

.line-unchanged {
  background: #fff;
}

.line-hover {
  background: #ecf5ff !important;
}

pre {
  margin: 0;
  padding: 0;
}

code {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}
</style>
