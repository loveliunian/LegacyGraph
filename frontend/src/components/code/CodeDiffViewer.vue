<template>
  <div class="code-diff-container">
    <div class="diff-header" v-if="showHeader">
      <div class="diff-file-info">
        <el-icon><Document /></el-icon>
        <span class="file-name">{{ fileName || '代码对比' }}</span>
        <el-tag v-if="stats.total > 0" size="small" type="success">+{{ stats.additions }}</el-tag>
        <el-tag v-if="stats.total > 0" size="small" type="danger">-{{ stats.deletions }}</el-tag>
      </div>
      <div class="diff-actions">
        <el-button-group size="small">
          <el-tooltip :content="$t('common.expandAll')" placement="top">
            <el-button :icon="Expand" @click="expandAll" />
          </el-tooltip>
          <el-tooltip :content="$t('common.collapse')" placement="top">
            <el-button :icon="Fold" @click="collapseAll" />
          </el-tooltip>
          <el-tooltip :content="$t('common.copy')" placement="top">
            <el-button :icon="CopyDocument" @click="copyNewCode" />
          </el-tooltip>
        </el-button-group>
        <el-radio-group v-model="viewMode" size="small" @change="handleViewModeChange">
          <el-radio-button value="split">{{ $t('graph.codeGraph') }}</el-radio-button>
          <el-radio-button value="unified">{{ $t('graph.unifiedGraph') }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="diff-content" :class="{ 'split-view': viewMode === 'split' }">
      <div v-if="loading" class="loading-overlay">
        <el-skeleton :rows="15" animated />
      </div>
      <div v-else-if="!oldCode && !newCode" class="empty-state">
        <el-empty :description="$t('common.noData')" />
      </div>
      <div v-else class="diff-view">
        <template v-if="viewMode === 'split'">
          <div class="diff-column old-code">
            <div class="column-header">
              <el-icon><Clock /></el-icon>
              <span>{{ oldVersion || $t('migration.riskType') === 8 }}</span>
            </div>
            <div class="code-lines">
              <div
                v-for="line in oldLines"
                :key="`old-${line.oldLine}`"
                class="code-line"
                :class="getLineClass(line.type, 'old')"
              >
                <span class="line-number">{{ line.oldLine || '' }}</span>
                <span class="line-symbol">{{ getLineSymbol(line.type) }}</span>
                <span class="line-content" v-html="highlightLine(line.content, language, line.type)"></span>
              </div>
            </div>
          </div>
          <div class="diff-column new-code">
            <div class="column-header">
              <el-icon><Promotion /></el-icon>
              <span>{{ newVersion || $t('migration.mitigated') }}</span>
            </div>
            <div class="code-lines">
              <div
                v-for="line in newLines"
                :key="`new-${line.newLine}`"
                class="code-line"
                :class="getLineClass(line.type, 'new')"
              >
                <span class="line-number">{{ line.newLine || '' }}</span>
                <span class="line-symbol">{{ getLineSymbol(line.type) }}</span>
                <span class="line-content" v-html="highlightLine(line.content, language, line.type)"></span>
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="code-lines unified">
            <div
              v-for="(line, index) in unifiedLines"
              :key="index"
              class="code-line"
              :class="getLineClass(line.type, 'unified')"
            >
              <span class="line-number old-num">{{ line.oldLine || '' }}</span>
              <span class="line-number new-num">{{ line.newLine || '' }}</span>
              <span class="line-symbol">{{ getLineSymbol(line.type) }}</span>
              <span class="line-content" v-html="highlightLine(line.content, language, line.type)"></span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="diff-legend" v-if="showLegend">
      <div class="legend-item">
        <span class="legend-color added"></span>
        <span>{{ $t('migration.codeRefactoring') }}</span>
      </div>
      <div class="legend-item">
        <span class="legend-color removed"></span>
        <span>{{ $t('migration.dataMigration') }}</span>
      </div>
      <div class="legend-item">
        <span class="legend-color modified"></span>
        <span>{{ $t('migration.compatibility') }}</span>
      </div>
      <div class="legend-item">
        <span class="legend-color unchanged"></span>
        <span>{{ $t('migration.accepted') }}</span>
      </div>
    </div>

    <div v-if="showStats && stats.total > 0" class="diff-stats">
      <el-statistic title="总变更行数" :value="stats.total" size="small" />
      <el-statistic title="新增行数" :value="stats.additions" size="small" class="success">
        <template #prefix><el-icon><Top /></el-icon></template>
      </el-statistic>
      <el-statistic title="删除行数" :value="stats.deletions" size="small" class="danger">
        <template #prefix><el-icon><Bottom /></el-icon></template>
      </el-statistic>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'
import * as Diff from 'diff'
import {
  Document,
  Expand,
  Fold,
  CopyDocument,
  Clock,
  Promotion,
  Top,
  Bottom
} from '@element-plus/icons-vue'

interface DiffLine {
  oldLine: number | null
  newLine: number | null
  content: string
  type: 'added' | 'removed' | 'unchanged' | 'empty'
}

const props = withDefaults(defineProps<{
  oldCode: string
  newCode: string
  fileName?: string
  language?: string
  oldVersion?: string
  newVersion?: string
  showHeader?: boolean
  showLegend?: boolean
  showStats?: boolean
  loading?: boolean
  contextSize?: number
}>(), {
  oldCode: '',
  newCode: '',
  fileName: '',
  language: 'javascript',
  oldVersion: 'Original',
  newVersion: 'Modified',
  showHeader: true,
  showLegend: true,
  showStats: true,
  loading: false,
  contextSize: 3
})

const emit = defineEmits<{
  viewModeChange: [mode: 'split' | 'unified']
  lineClick: [line: DiffLine]
}>()

const viewMode = ref<'split' | 'unified'>('split')
const expanded = ref(true)
const oldLineNumber = ref(1)
const newLineNumber = ref(1)

const diffResult = computed(() => {
  return Diff.diffLines(props.oldCode || '', props.newCode || '')
})

const stats = computed(() => {
  let additions = 0
  let deletions = 0

  diffResult.value.forEach((part: any) => {
    if (part.added) additions += part.count || 0
    if (part.removed) deletions += part.count || 0
  })

  return { additions, deletions, total: additions + deletions }
})

const unifiedLines = computed((): DiffLine[] => {
  const lines: DiffLine[] = []
  let oldLine = 1
  let newLine = 1

  diffResult.value.forEach((part: any) => {
    const partLines = part.value.split('\n')
    if (partLines[partLines.length - 1] === '') {
      partLines.pop()
    }

    partLines.forEach((line: string) => {
      if (part.added) {
        lines.push({ oldLine: null, newLine: newLine++, content: line, type: 'added' })
      } else if (part.removed) {
        lines.push({ oldLine: oldLine++, newLine: null, content: line, type: 'removed' })
      } else {
        lines.push({ oldLine: oldLine++, newLine: newLine++, content: line, type: 'unchanged' })
      }
    })
  })

  return lines
})

const oldLines = computed((): DiffLine[] => {
  const lines: DiffLine[] = []
  let oldLine = 1
  let newLine = 1

  diffResult.value.forEach((part: any) => {
    const partLines = part.value.split('\n')
    if (partLines[partLines.length - 1] === '') {
      partLines.pop()
    }

    partLines.forEach((line: string) => {
      if (part.added) {
        newLine++
      } else if (part.removed) {
        lines.push({ oldLine: oldLine++, newLine: null, content: line, type: 'removed' })
      } else {
        lines.push({ oldLine: oldLine++, newLine: newLine++, content: line, type: 'unchanged' })
      }
    })
  })

  return lines
})

const newLines = computed((): DiffLine[] => {
  const lines: DiffLine[] = []
  let oldLine = 1
  let newLine = 1

  diffResult.value.forEach((part: any) => {
    const partLines = part.value.split('\n')
    if (partLines[partLines.length - 1] === '') {
      partLines.pop()
    }

    partLines.forEach((line: string) => {
      if (part.added) {
        lines.push({ oldLine: null, newLine: newLine++, content: line, type: 'added' })
      } else if (part.removed) {
        oldLine++
      } else {
        lines.push({ oldLine: oldLine++, newLine: newLine++, content: line, type: 'unchanged' })
      }
    })
  })

  return lines
})

function getLineClass(type: string, view: string): string {
  switch (type) {
    case 'added':
      return `line-added ${view}`
    case 'removed':
      return `line-removed ${view}`
    case 'empty':
      return `line-empty ${view}`
    default:
      return `line-unchanged ${view}`
  }
}

function getLineSymbol(type: string): string {
  switch (type) {
    case 'added':
      return '+'
    case 'removed':
      return '-'
    default:
      return ' '
  }
}

function highlightLine(content: string, language: string, type: string): string {
  try {
    const highlighted = hljs.highlight(content || ' ', { language }).value
    return highlighted
  } catch {
    return escapeHtml(content || ' ')
  }
}

function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

function copyOldCode() {
  navigator.clipboard.writeText(props.oldCode).then(() => {
    ElMessage.success('已复制旧代码')
  })
}

function copyNewCode() {
  navigator.clipboard.writeText(props.newCode).then(() => {
    ElMessage.success('已复制新代码')
  })
}

function expandAll() {
  expanded.value = true
  ElMessage.info('已展开所有区域')
}

function collapseAll() {
  expanded.value = false
  ElMessage.info('已折叠未变更区域')
}

function handleViewModeChange(mode: 'split' | 'unified') {
  emit('viewModeChange', mode)
}

defineExpose({
  copyOldCode,
  copyNewCode,
  expandAll,
  collapseAll
})
</script>

<style scoped>
.code-diff-container {
  width: 100%;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}

.diff-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  flex-wrap: wrap;
  gap: 12px;
}

.diff-file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
}

.file-name {
  font-weight: 500;
  color: #303133;
}

.diff-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.diff-content {
  min-height: 200px;
  max-height: 600px;
  overflow: auto;
}

.diff-content.split-view .diff-view {
  display: flex;
}

.diff-column {
  flex: 1;
  min-width: 0;
  min-height: 100%;
}

.diff-column:first-child {
  border-right: 1px solid #ebeef5;
}

.column-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #ebeef5;
  font-size: 13px;
  color: #606266;
  font-weight: 500;
  position: sticky;
  top: 0;
  z-index: 10;
}

.old-code .column-header {
  background: #fef0f0;
  border-bottom-color: #fbc4c4;
}

.new-code .column-header {
  background: #f0f9eb;
  border-bottom-color: #c2e7b0;
}

.code-lines {
  font-size: 13px;
  line-height: 1.6;
}

.code-lines.unified {
  display: block;
}

.code-line {
  display: flex;
  align-items: stretch;
  min-height: 24px;
  border-bottom: 1px solid #f5f7fa;
  transition: background-color 0.2s;
}

.code-line:hover {
  background-color: #f5f7fa;
}

.line-number {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  width: 50px;
  padding: 0 8px;
  background: #fafafa;
  color: #909399;
  font-size: 12px;
  border-right: 1px solid #ebeef5;
  user-select: none;
  flex-shrink: 0;
}

.code-lines.unified .line-number {
  width: 45px;
}

.code-lines.unified .line-number.old-num {
  background: #fef0f0;
  color: #f56c6c;
}

.code-lines.unified .line-number.new-num {
  background: #f0f9eb;
  color: #67c23a;
}

.line-symbol {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  padding: 0 4px;
  font-weight: bold;
  user-select: none;
  flex-shrink: 0;
}

.line-content {
  flex: 1;
  padding: 0 8px;
  white-space: pre;
  overflow-x: auto;
  min-width: 0;
}

.line-added {
  background: #f0f9eb;
}

.line-added .line-symbol {
  color: #67c23a;
  background: #e6f7e6;
}

.line-removed {
  background: #fef0f0;
}

.line-removed .line-symbol {
  color: #f56c6c;
  background: #fde2e2;
}

.line-unchanged .line-symbol {
  color: #c0c4cc;
}

.line-empty {
  background: #fafafa;
}

.line-empty .line-content {
  color: #c0c4cc;
}

.loading-overlay {
  padding: 20px;
}

.empty-state {
  padding: 60px 20px;
}

.diff-legend {
  display: flex;
  gap: 20px;
  padding: 12px 16px;
  background: #fafafa;
  border-top: 1px solid #ebeef5;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #606266;
}

.legend-color {
  width: 16px;
  height: 16px;
  border-radius: 3px;
  border: 1px solid #ebeef5;
}

.legend-color.added {
  background: #f0f9eb;
}

.legend-color.removed {
  background: #fef0f0;
}

.legend-color.modified {
  background: #fdf6ec;
}

.legend-color.unchanged {
  background: #ffffff;
}

.diff-stats {
  display: flex;
  gap: 30px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-top: 1px solid #ebeef5;
}

.diff-stats .el-statistic {
  flex: none;
}

.diff-stats .success :deep(.el-statistic__content) {
  color: #67c23a;
}

.diff-stats .danger :deep(.el-statistic__content) {
  color: #f56c6c;
}

:deep(.hljs) {
  background: transparent !important;
  padding: 0 !important;
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: #f5f7fa;
}

::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
  background: #c0c4cc;
}
</style>
