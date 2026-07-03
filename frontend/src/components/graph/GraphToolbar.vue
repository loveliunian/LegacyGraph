<template>
  <div class="graph-toolbar">
    <div class="toolbar-group">
      <el-tooltip
        content="放大"
        placement="bottom">
        <el-button
          :icon="ZoomIn"
          size="small"
          circle
          @click="emit('zoomIn')" />
      </el-tooltip>
      <el-tooltip
        content="缩小"
        placement="bottom">
        <el-button
          :icon="ZoomOut"
          size="small"
          circle
          @click="emit('zoomOut')" />
      </el-tooltip>
      <el-tooltip
        content="适应视图"
        placement="bottom">
        <el-button
          :icon="FullScreen"
          size="small"
          circle
          @click="emit('fitView')" />
      </el-tooltip>
      <el-tooltip
        content="居中小球"
        placement="bottom">
        <el-button
          :icon="Location"
          size="small"
          circle
          @click="emit('centerView')" />
      </el-tooltip>
    </div>

    <el-divider direction="vertical" />

    <div class="toolbar-group">
      <el-tooltip
        content="力导布局"
        placement="bottom">
        <el-button
          :icon="Connection"
          size="small"
          circle
          :type="layout === 'elk' ? 'primary' : ''"
          @click="changeLayout('elk')" />
      </el-tooltip>
      <el-tooltip
        content="层次布局"
        placement="bottom">
        <el-button
          :icon="Grid"
          size="small"
          circle
          :type="layout === 'dagre' ? 'primary' : ''"
          @click="changeLayout('dagre')" />
      </el-tooltip>
      <el-tooltip
        content="环形布局"
        placement="bottom">
        <el-button
          :icon="RefreshRight"
          size="small"
          circle
          :type="layout === 'circular' ? 'primary' : ''"
          @click="changeLayout('circular')" />
      </el-tooltip>
    </div>

    <el-divider direction="vertical" />

    <div class="toolbar-group">
      <el-tooltip
        content="展开全部"
        placement="bottom">
        <el-button
          :icon="Plus"
          size="small"
          circle
          @click="emit('expandAll')" />
      </el-tooltip>
      <el-tooltip
        content="收起全部"
        placement="bottom">
        <el-button
          :icon="Minus"
          size="small"
          circle
          @click="emit('collapseAll')" />
      </el-tooltip>
    </div>

    <el-divider direction="vertical" />

    <div class="toolbar-group">
      <el-tooltip
        content="搜索节点"
        placement="bottom">
        <el-button
          :icon="Search"
          size="small"
          circle
          @click="showSearch = !showSearch" />
      </el-tooltip>

      <el-tooltip
        content="筛选"
        placement="bottom">
        <el-button
          :icon="Filter"
          size="small"
          circle
          @click="showFilter = !showFilter" />
      </el-tooltip>

      <el-tooltip
        content="设置"
        placement="bottom">
        <el-button
          :icon="Setting"
          size="small"
          circle
          @click="showSettings = !showSettings" />
      </el-tooltip>
    </div>

    <el-divider direction="vertical" />

    <div class="toolbar-group">
      <el-dropdown
        trigger="click"
        @command="handleExport">
        <el-button
          size="small"
          :icon="Download">
          导出
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="png">PNG 图片</el-dropdown-item>
            <el-dropdown-item command="svg">SVG 矢量图</el-dropdown-item>
            <el-dropdown-item command="json">JSON 数据</el-dropdown-item>
            <el-dropdown-item command="csv">CSV 表格</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-dropdown
        trigger="click"
        @command="handleShare">
        <el-button
          size="small"
          :icon="Share">
          分享
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="link">复制链接</el-dropdown-item>
            <el-dropdown-item command="embed">嵌入代码</el-dropdown-item>
            <el-dropdown-item command="report">生成报告</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="toolbar-right">
      <span class="zoom-level">{{ Math.round(zoomLevel * 100) }}%</span>
    </div>

    <el-drawer
      v-model="showSearch"
      title="搜索节点"
      direction="ltr"
      size="360px"
    >
      <el-input
        v-model="searchText"
        placeholder="输入节点名称或属性搜索"
        :prefix-icon="Search"
        clearable
        class="search-input"
      />
      <div class="search-results">
        <div
          v-for="node in filteredNodes"
          :key="node.id"
          class="search-result-item"
          @click="locateNode(node.id)"
        >
          <div class="result-label">{{ node.label }}</div>
          <div class="result-meta">
            <el-tag
              size="small"
              :type="getNodeTypeColor(node.type)">
              {{ node.type }}
            </el-tag>
            <span class="result-desc">{{ truncate(node.description || '', 50) }}</span>
          </div>
        </div>
        <div
          v-if="filteredNodes.length === 0"
          class="empty-result">
          <el-empty
            description="未找到匹配节点"
            :image-size="80" />
        </div>
      </div>
    </el-drawer>

    <el-drawer
      v-model="showFilter"
      title="节点筛选"
      direction="ltr"
      size="360px"
    >
      <div class="filter-section">
        <h4>节点类型</h4>
        <el-checkbox-group v-model="filterTypes">
          <el-checkbox
            v-for="type in nodeTypes"
            :key="type.key"
            :label="type.key"
            border
          >
            {{ type.label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>

      <div class="filter-section">
        <h4>置信度范围</h4>
        <el-slider
          v-model="confidenceRange"
          range
          :step="0.1"
          :min="0"
          :max="1"
          :format="formatPercent"
        />
        <div class="range-labels">
          <span>{{ confidenceRange[0] * 100 }}%</span>
          <span>{{ confidenceRange[1] * 100 }}%</span>
        </div>
      </div>

      <div class="filter-actions">
        <el-button
          size="small"
          @click="resetFilter">
          重置
        </el-button>
        <el-button
          size="small"
          type="primary"
          @click="applyFilter">
          应用筛选
        </el-button>
      </div>
    </el-drawer>

    <el-drawer
      v-model="showSettings"
      title="图谱设置"
      direction="ltr"
      size="360px"
    >
      <div class="settings-section">
        <h4>样式</h4>
        <el-form label-width="100px">
          <el-form-item label="节点大小">
            <el-slider
              v-model="nodeSize"
              :min="20"
              :max="80" />
          </el-form-item>
          <el-form-item label="连接线粗细">
            <el-slider
              v-model="edgeWidth"
              :min="1"
              :max="10" />
          </el-form-item>
          <el-form-item label="字体大小">
            <el-slider
              v-model="fontSize"
              :min="10"
              :max="24" />
          </el-form-item>
        </el-form>
      </div>

      <div class="settings-section">
        <h4>显示选项</h4>
        <el-form label-width="100px">
          <el-form-item label="显示边标签">
            <el-switch v-model="showEdgeLabels" />
          </el-form-item>
          <el-form-item label="显示节点图标">
            <el-switch v-model="showNodeIcons" />
          </el-form-item>
          <el-form-item label="显示网格背景">
            <el-switch v-model="showGrid" />
          </el-form-item>
          <el-form-item label="动画效果">
            <el-switch v-model="enableAnimation" />
          </el-form-item>
        </el-form>
      </div>

      <div class="settings-section">
        <h4>交互</h4>
        <el-form label-width="100px">
          <el-form-item label="拖拽动画">
            <el-switch v-model="dragAnimation" />
          </el-form-item>
          <el-form-item label="悬停高亮">
            <el-switch v-model="hoverHighlight" />
          </el-form-item>
          <el-form-item label="多级展开">
            <el-switch v-model="multiLevelExpand" />
          </el-form-item>
        </el-form>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ZoomIn,
  ZoomOut,
  FullScreen,
  Location,
  Connection,
  Grid,
  RefreshRight,
  Plus,
  Minus,
  Search,
  Filter,
  Setting,
  Download,
  Share
} from '@element-plus/icons-vue'

interface GraphNode {
  id: string
  label: string
  type: string
  description?: string
}

const props = defineProps<{
  nodes?: GraphNode[]
  currentLayout?: string
  currentZoom?: number
}>()

const emit = defineEmits<{
  zoomIn: []
  zoomOut: []
  fitView: []
  centerView: []
  layoutChange: [layout: string]
  expandAll: []
  collapseAll: []
  export: [format: string]
  share: [type: string]
  search: [keyword: string]
  filterChange: [filters: { types: string[]; confidenceRange: [number, number] }]
  locateNode: [nodeId: string]
  settingsChange: [settings: Record<string, any>]
}>()

const layout = ref(props.currentLayout || 'elk')
const zoomLevel = ref(props.currentZoom || 1)
const showSearch = ref(false)
const showFilter = ref(false)
const showSettings = ref(false)
const searchText = ref('')

const filterTypes = ref<string[]>([])
const confidenceRange = ref<[number, number]>([0, 1])

const nodeSize = ref(40)
const edgeWidth = ref(2)
const fontSize = ref(12)
const showEdgeLabels = ref(true)
const showNodeIcons = ref(true)
const showGrid = ref(false)
const enableAnimation = ref(true)
const dragAnimation = ref(true)
const hoverHighlight = ref(true)
const multiLevelExpand = ref(false)

const nodeTypes = [
  { key: 'controller', label: 'Controller' },
  { key: 'service', label: 'Service' },
  { key: 'mapper', label: 'Mapper' },
  { key: 'entity', label: 'Entity' },
  { key: 'table', label: 'Table' },
  { key: 'column', label: 'Column' },
  { key: 'api', label: 'API' },
  { key: 'page', label: 'Page' }
]

const filteredNodes = computed(() => {
  if (!searchText.value) return props.nodes || []
  return (props.nodes || []).filter(node =>
    node.label.toLowerCase().includes(searchText.value.toLowerCase()) ||
    node.id.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

function changeLayout(l: string) {
  layout.value = l
  emit('layoutChange', l)
}

function handleExport(format: string) {
  emit('export', format)
  ElMessage.success(`正在导出 ${format.toUpperCase()} 格式`)
}

function handleShare(type: string) {
  emit('share', type)
  if (type === 'link') {
    ElMessage.success('链接已复制到剪贴板')
  }
}

function locateNode(nodeId: string) {
  emit('locateNode', nodeId)
  showSearch.value = false
}

function getNodeTypeColor(type: string) {
  const colors: Record<string, any> = {
    controller: 'primary',
    service: 'success',
    mapper: 'warning',
    entity: 'danger',
    table: 'info',
    column: '',
    api: 'primary',
    page: 'success'
  }
  return colors[type] || ''
}

function truncate(str: string, len: number): string {
  if (str.length <= len) return str
  return str.substring(0, len) + '...'
}

function formatPercent(value: number): string {
  return `${Math.round(value * 100)}%`
}

function resetFilter() {
  filterTypes.value = []
  confidenceRange.value = [0, 1]
}

function applyFilter() {
  emit('filterChange', {
    types: filterTypes.value,
    confidenceRange: confidenceRange.value
  })
  showFilter.value = false
}

watch([
  nodeSize, edgeWidth, fontSize,
  showEdgeLabels, showNodeIcons, showGrid,
  enableAnimation, dragAnimation, hoverHighlight, multiLevelExpand
], () => {
  emit('settingsChange', {
    nodeSize: nodeSize.value,
    edgeWidth: edgeWidth.value,
    fontSize: fontSize.value,
    showEdgeLabels: showEdgeLabels.value,
    showNodeIcons: showNodeIcons.value,
    showGrid: showGrid.value,
    enableAnimation: enableAnimation.value,
    dragAnimation: dragAnimation.value,
    hoverHighlight: hoverHighlight.value,
    multiLevelExpand: multiLevelExpand.value
  })
})
</script>

<style scoped>
.graph-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.toolbar-right {
  margin-left: auto;
}

.zoom-level {
  font-size: 13px;
  color: #606266;
  font-family: 'Consolas', monospace;
}

.search-input {
  margin-bottom: 16px;
}

.search-results {
  max-height: 400px;
  overflow-y: auto;
}

.search-result-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.search-result-item:hover {
  background: #f5f7fa;
}

.result-label {
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-desc {
  font-size: 12px;
  color: #909399;
}

.empty-result {
  padding: 40px 20px;
  text-align: center;
}

.filter-section {
  margin-bottom: 24px;
}

.filter-section h4 {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin: 0 0 12px 0;
}

.filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.range-labels {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.settings-section {
  margin-bottom: 24px;
}

.settings-section h4 {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin: 0 0 12px 0;
}
</style>
