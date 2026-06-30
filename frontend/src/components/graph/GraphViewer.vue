<template>
  <div class="graph-container">
    <VueFlow
      ref="vueFlowRef"
      v-model:nodes="graphNodes"
      v-model:edges="graphEdges"
      :fit-view-on-init="false"
      :default-viewport="{ x: 0, y: 0, zoom: 0.5 }"
      :min-zoom="0.1"
      :max-zoom="4"
      :default-edge-options="{ type: 'smoothstep', animated: false }"
      :node-types="nodeTypes"
      class="vue-flow-container"
      @node-click="(e: any) => handleNodeClick(e)"
      @edge-click="(e: any) => handleEdgeClick(e)"
      @node-drag-stop="(e: any) => handleNodeDragStop(e)"
      @connect="handleConnect"
      @zoom="(e: any) => handleZoom(e)"
    />
    <div class="graph-panel graph-panel-left">
      <div class="panel-title">
        <el-icon><Connection /></el-icon>
        <span>关系图谱</span>
      </div>
      <div class="panel-stats">
        <el-tag size="small" type="info">节点: {{ graphNodes.length }}</el-tag>
        <el-tag size="small" type="success">关系: {{ graphEdges.length }}</el-tag>
      </div>
    </div>
    <div class="graph-panel graph-panel-right">
      <el-space wrap>
        <el-button size="small" @click="fitView">
          <el-icon><ZoomOut /></el-icon>
          适应视图
        </el-button>
        <el-button size="small" @click="centerView">
          <el-icon><FullScreen /></el-icon>
          居中
        </el-button>
        <el-button size="small" @click="toggleLayout">
          <el-icon><Grid /></el-icon>
          {{ currentLayout }}布局
        </el-button>
        <el-button size="small" @click="exportGraph">
          <el-icon><Download /></el-icon>
          导出
        </el-button>
      </el-space>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  VueFlow,
  useVueFlow,
  MarkerType
} from '@vue-flow/core'
import type { Node, Edge } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { Connection, ZoomOut, FullScreen, Grid, Download } from '@element-plus/icons-vue'
import CustomNode from './CustomNode.vue'

/**
 * 图谱节点数据结构
 */
interface GraphNodeData {
  /** 节点显示标签 */
  label: string
  /** 节点类型 */
  type: string
  /** LLM置信度 0-1 */
  confidence: number
  /** 审核状态 */
  status: string
  /** 关联证据数量 */
  evidenceCount: number
}

/**
 * 组件属性定义
 */
interface Props {
  /** 图谱节点列表 */
  nodes: Node<GraphNodeData>[]
  /** 图谱边列表 */
  edges: Edge[]
  /** 组件高度，默认600px */
  height?: string
  /** 是否可编辑（允许拖拽和连线），默认true */
  editable?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  height: '600px',
  editable: true
})

const emit = defineEmits<{
  nodeClick: [node: Node<GraphNodeData>]
  edgeClick: [edge: Edge]
  nodeDrag: [node: Node<GraphNodeData>]
  connect: [connection: { source: string; target: string }]
}>()

const vueFlowRef = ref()
const { fitView, zoomIn, zoomOut, setCenter } = useVueFlow()

const nodeTypes = {
  custom: CustomNode as any
}

const currentLayout = ref('力导向')

const graphNodes = ref<any[]>([])
const graphEdges = ref<any[]>([])

watch(
  () => [props.nodes, props.edges] as const,
  ([newNodes, newEdges]) => {
    graphNodes.value = newNodes.map((node) => ({
      id: node.id,
      type: 'custom',
      position: node.position || { x: Math.random() * 600, y: Math.random() * 400 },
      data: (node.data || {}) as any,
      style: {
        width: 180,
        cursor: 'pointer'
      }
    }))
    // 只保留 source/target 都存在的边
    const nodeIds = new Set(graphNodes.value.map(n => n.id))
    graphEdges.value = newEdges
      .filter(e => nodeIds.has(e.source) && nodeIds.has(e.target))
      .map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.label || '',
        type: 'smoothstep',
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: getEdgeColor(edge.data?.confidence || 0.8)
        },
        style: {
          stroke: getEdgeColor(edge.data?.confidence || 0.8),
          strokeWidth: 2 + (edge.data?.confidence || 0.8)
        },
        data: edge.data
      }))
  },
  { immediate: true }
)

function getEdgeColor(confidence: number): string {
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

function nodeColor(node: Node<GraphNodeData>): string {
  const confidence = node.data?.confidence || 0.8
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

function handleNodeClick(event: any) {
  emit('nodeClick', event)
}

function handleEdgeClick(event: any) {
  emit('edgeClick', event)
}

function handleNodeDragStop(event: any) {
  emit('nodeDrag', event)
}

function handleConnect(params: { source: string; target: string }) {
  emit('connect', params)
}

function handleZoom(transform: any) {
  console.log('Zoom:', transform.zoom)
}

function centerView() {
  if (graphNodes.value.length > 0) {
    const centerX = graphNodes.value.reduce((sum, node) => sum + node.position.x, 0) / graphNodes.value.length
    const centerY = graphNodes.value.reduce((sum, node) => sum + node.position.y, 0) / graphNodes.value.length
    setCenter(centerX + 90, centerY + 30, 1 as any)
  }
}

function toggleLayout() {
  const layouts = ['力导向', '环形', '网格', '分层']
  const currentIndex = layouts.indexOf(currentLayout.value)
  currentLayout.value = layouts[(currentIndex + 1) % layouts.length]
  applyLayout(currentLayout.value)
}

function applyLayout(layout: string) {
  const nodeCount = graphNodes.value.length
  if (nodeCount === 0) return

  const centerX = 400
  const centerY = 300

  switch (layout) {
    case '环形':
      graphNodes.value.forEach((node, index) => {
        const angle = (2 * Math.PI * index) / nodeCount
        node.position = {
          x: centerX + 250 * Math.cos(angle) - 90,
          y: centerY + 200 * Math.sin(angle) - 30
        }
      })
      break
    case '网格':
      const cols = Math.ceil(Math.sqrt(nodeCount))
      graphNodes.value.forEach((node, index) => {
        node.position = {
          x: (index % cols) * 220,
          y: Math.floor(index / cols) * 160
        }
      })
      break
    case '分层':
      const rows = Math.ceil(nodeCount / 4)
      graphNodes.value.forEach((node, index) => {
        node.position = {
          x: (index % 4) * 220,
          y: Math.floor(index / 4) * 160
        }
      })
      break
    case '力导向':
    default:
      graphNodes.value.forEach((node, index) => {
        node.position = {
          x: 100 + Math.random() * 600,
          y: 100 + Math.random() * 400
        }
      })
  }
}

function exportGraph() {
  const exportData = {
    nodes: graphNodes.value,
    edges: graphEdges.value,
    exportTime: new Date().toISOString()
  }
  const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `graph-export-${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  setTimeout(() => {
    fitView()
  }, 100)
})
</script>

<style scoped>
.graph-container {
  width: 100%;
  height: v-bind(height);
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fafafa;
  position: relative;
}

.vue-flow-container {
  width: 100%;
  height: 100%;
}

.graph-panel {
  position: absolute;
  z-index: 10;
  background: white;
  padding: 12px 16px;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.graph-panel-left {
  top: 12px;
  left: 12px;
}

.graph-panel-right {
  top: 12px;
  right: 12px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.panel-stats {
  display: flex;
  gap: 8px;
}

:deep(.vue-flow__node) {
  transition: all 0.2s ease;
}

:deep(.vue-flow__node:hover) {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

:deep(.vue-flow__edge) {
  transition: all 0.2s ease;
}

:deep(.vue-flow__edge:hover) {
  filter: brightness(1.2);
}
</style>
