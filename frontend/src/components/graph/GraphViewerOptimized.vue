<template>
  <div class="graph-container">
    <VueFlow
      ref="vueFlowRef"
      v-model:nodes="visibleNodes"
      v-model:edges="visibleEdges"
      :fit-view-on-init="false"
      :default-viewport="{ x: 0, y: 0, zoom: 0.3 }"
      :min-zoom="0.05"
      :max-zoom="4"
      :default-edge-options="{ type: 'smoothstep', animated: false }"
      :node-types="nodeTypes"
      :only-render-visible-elements="nodes.length > 100"
      class="vue-flow-container"
      @node-click="(e: any) => handleNodeClick(e)"
      @edge-click="(e: any) => handleEdgeClick(e)"
      @node-drag-stop="(e: any) => handleNodeDragStop(e)"
      @connect="handleConnect"
      @move="(e: any) => handleMove(e)"
    />
    
    <div class="graph-panel graph-panel-left">
      <div class="panel-title">
        <el-icon><Connection /></el-icon>
        <span>关系图谱</span>
      </div>
      <div class="panel-stats">
        <el-tag
          size="small"
          type="info">
          总节点: {{ totalNodesCount }}
        </el-tag>
        <el-tag
          size="small"
          type="info">
          显示节点: {{ visibleNodes.length }}
        </el-tag>
        <el-tag
          size="small"
          type="success">
          总关系: {{ totalEdgesCount }}
        </el-tag>
        <el-tag
          size="small"
          type="success">
          显示关系: {{ visibleEdges.length }}
        </el-tag>
      </div>
      <div
        v-if="isAggregating"
        class="aggregation-info">
        <el-tag
          size="small"
          type="warning">
          已聚合 {{ aggregatedGroupCount }} 组
        </el-tag>
      </div>
    </div>
    
    <div class="graph-panel graph-panel-right">
      <el-space wrap>
        <el-button
          size="small"
          @click="fitView">
          <el-icon><ZoomOut /></el-icon>
          适应视图
        </el-button>
        <el-button
          size="small"
          @click="centerView">
          <el-icon><FullScreen /></el-icon>
          居中
        </el-button>
        <el-button
          size="small"
          :loading="isLayouting"
          @click="toggleLayout">
          <el-icon><Grid /></el-icon>
          {{ currentLayout }}布局
        </el-button>
        <el-button
          size="small"
          @click="exportGraph">
          <el-icon><Download /></el-icon>
          导出
        </el-button>
      </el-space>
    </div>

    <div
      v-if="isLayouting"
      class="loading-overlay">
      <el-icon
        class="is-loading loading-icon"
        :size="48">
        <Loading />
      </el-icon>
      <div class="loading-text">正在计算布局... ({{ nodes.length }} 个节点)</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, markRaw } from 'vue'
import {
  VueFlow,
  useVueFlow,
  MarkerType
} from '@vue-flow/core'
import type { Node, Edge } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { Connection, ZoomOut, FullScreen, Grid, Download, Loading } from '@element-plus/icons-vue'
import CustomNode from './CustomNode.vue'

interface GraphNodeData {
  label: string
  type: string
  confidence: number
  status: string
  evidenceCount: number
  isAggregated?: boolean
  aggregatedCount?: number
  childNodes?: string[]
}

interface Props {
  nodes: Node<GraphNodeData>[]
  edges: Edge[]
  height?: string
  editable?: boolean
  aggregationThreshold?: number
  workerEnabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  height: '600px',
  editable: true,
  aggregationThreshold: 3000, // S4-T3: 节点 > 3000 自动收敛
  workerEnabled: true
})

const emit = defineEmits<{
  nodeClick: [node: Node<GraphNodeData>]
  edgeClick: [edge: Edge]
  nodeDrag: [node: Node<GraphNodeData>]
  connect: [connection: { source: string; target: string }]
}>()

const vueFlowRef = ref()
// L-18: Vue Flow setCenter 签名为 (x, y, options?: { zoom?: number })，无需 @ts-expect-error
// L-18: 移除不存在的 getZoom 和未使用的 zoomIn/zoomOut
const { fitView, setCenter } = useVueFlow()

const nodeTypes = {
  custom: markRaw(CustomNode) as any
}

const currentLayout = ref('力导向')
const visibleNodes = ref<any[]>([])
const visibleEdges = ref<any[]>([])
const isLayouting = ref(false)
const isAggregating = ref(false)
const aggregatedGroupCount = ref(0)
const currentZoom = ref(1)
const graphCenter = ref({ x: 500, y: 400 })

let layoutWorker: Worker | null = null

const totalNodesCount = computed(() => props.nodes.length)
const totalEdgesCount = computed(() => props.edges.length)
// S4-T3: LOD 收敛阈值可通过 URL 参数覆盖（如 ?lodThreshold=2000），避免命中率断崖
const effectiveAggregationThreshold = computed(() => {
  if (typeof window === 'undefined') return props.aggregationThreshold
  const urlParam = new URLSearchParams(window.location.search).get('lodThreshold')
  const urlValue = urlParam ? parseInt(urlParam, 10) : NaN
  return Number.isFinite(urlValue) && urlValue > 0 ? urlValue : props.aggregationThreshold
})
const shouldUseAggregation = computed(() => props.nodes.length > effectiveAggregationThreshold.value)
const shouldUseWorker = computed(() => props.workerEnabled && props.nodes.length > 200)

watch(
  () => [props.nodes, props.edges] as const,
  ([newNodes, newEdges]) => {
    processNodes(newNodes)
    processEdgesSafe(newNodes, newEdges)
  },
  { immediate: true }
)

watch(currentZoom, (zoom) => {
  if (shouldUseAggregation.value) {
    updateAggregationByZoom(zoom)
  }
})

function processNodes(nodes: Node<GraphNodeData>[]) {
  if (nodes.length < effectiveAggregationThreshold.value) {
    visibleNodes.value = nodes.map((node, index) => ({
      ...node,
      id: node.id,
      type: 'custom',
      position: node.position || {
        x: 100 + (index % 10) * 200,
        y: 100 + Math.floor(index / 10) * 150
      },
      style: {
        width: 180,
        cursor: 'pointer'
      }
    }))
    isAggregating.value = false
  } else {
    // S4-T3: 多级 LOD — 低缩放时用大 gridSize 高度聚合
    applyNodeAggregation(nodes, lodGridSize(currentZoom.value))
  }
}

function processEdges(edges: Edge[]) {
  visibleEdges.value = edges.map((edge) => ({
    ...edge,
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
}

function processEdgesSafe(nodes: Node[], edges: Edge[]) {
  const nodeIds = new Set(nodes.map(n => n.id))
  processEdges(edges.filter(e => nodeIds.has(e.source) && nodeIds.has(e.target)))
}

/**
 * S4-T3: 多级 LOD 聚合 — gridSize 随缩放级别变化。
 * zoom < 0.3 → gridSize 300（高度聚合，万级节点收敛到可渲染量）
 * zoom 0.3-0.8 → gridSize 200（中度聚合）
 * zoom > 0.8 → gridSize 100（轻度聚合，接近展开）
 */
function lodGridSize(zoom: number): number {
  if (zoom < 0.3) return 300
  if (zoom < 0.8) return 200
  return 100
}

function applyNodeAggregation(nodes: Node<GraphNodeData>[], gridSize = 150) {
  isAggregating.value = true
  const groups = new Map<string, Node<GraphNodeData>[]>()

  nodes.forEach((node) => {
    const pos = node.position || { x: 0, y: 0 }
    const gridKey = `${Math.floor(pos.x / gridSize)}-${Math.floor(pos.y / gridSize)}`
    
    if (!groups.has(gridKey)) {
      groups.set(gridKey, [])
    }
    groups.get(gridKey)!.push(node)
  })

  const aggregated: Node<GraphNodeData>[] = []
  let groupIndex = 0

  groups.forEach((groupNodes, _key) => {
    if (groupNodes.length <= 5) {
      aggregated.push(...groupNodes)
    } else {
      const avgPos = groupNodes.reduce(
        (acc, node) => ({
          x: acc.x + (node.position?.x || 0),
          y: acc.y + (node.position?.y || 0)
        }),
        { x: 0, y: 0 }
      )
      avgPos.x /= groupNodes.length
      avgPos.y /= groupNodes.length

      const avgConfidence = groupNodes.reduce(
        (sum, node) => sum + (node.data?.confidence || 0), 0
      ) / groupNodes.length

      const aggregatedNode: Node<GraphNodeData> = {
        id: `aggregated-${groupIndex}`,
        type: 'custom',
        position: avgPos,
        data: {
          label: `${groupNodes.length} 个节点`,
          type: 'aggregated',
          confidence: avgConfidence,
          status: 'aggregated',
          evidenceCount: groupNodes.reduce((sum, n) => sum + (n.data?.evidenceCount || 0), 0),
          isAggregated: true,
          aggregatedCount: groupNodes.length,
          childNodes: groupNodes.map(n => n.id)
        },
        style: {
          width: 200,
          cursor: 'pointer',
          border: '3px dashed #67c23a',
          background: 'rgba(103, 194, 58, 0.1)'
        }
      }
      aggregated.push(aggregatedNode)
      groupIndex++
    }
  })

  aggregatedGroupCount.value = groupIndex
  visibleNodes.value = aggregated
}

function updateAggregationByZoom(zoom: number) {
  // S4-T3: 三级 LOD — 高缩放展开，中缩放中度聚合，低缩放高度聚合
  if (zoom > 1.5) {
    visibleNodes.value = props.nodes.map((node, index) => ({
      ...node,
      id: node.id,
      type: 'custom',
      position: node.position || {
        x: 100 + (index % 10) * 200,
        y: 100 + Math.floor(index / 10) * 150
      },
      style: {
        width: 180,
        cursor: 'pointer'
      }
    }))
    isAggregating.value = false
  } else if (shouldUseAggregation.value) {
    applyNodeAggregation(props.nodes, lodGridSize(zoom))
  }
}

function getEdgeColor(confidence: number): string {
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

function handleNodeClick(event: any) {
  const node = event.node ?? event
  if (node.data?.isAggregated) {
    expandAggregatedNode(node)
  } else {
    emit('nodeClick', node)
  }
}

function expandAggregatedNode(node: any) {
  const childIds = node.data?.childNodes || []
  const childNodes = props.nodes.filter(n => childIds.includes(n.id))
  
  visibleNodes.value = visibleNodes.value.filter(n => n.id !== node.id)
  visibleNodes.value.push(...childNodes.map((n, i) => ({
    ...n,
    position: {
      x: (node.position?.x || 0) + (i % 3 - 1) * 100,
      y: (node.position?.y || 0) + Math.floor(i / 3 - 1) * 80
    },
    style: {
      width: 180,
      cursor: 'pointer'
    }
  })))
}

function handleEdgeClick(event: any) {
  emit('edgeClick', event.edge ?? event)
}

function handleNodeDragStop(event: any) {
  emit('nodeDrag', event.node ?? event)
}

function handleConnect(params: { source: string; target: string }) {
  emit('connect', params)
}

function handleMove(transform: any) {
  currentZoom.value = transform.zoom
  graphCenter.value = { x: transform.x, y: transform.y }
}

function centerView() {
  if (visibleNodes.value.length > 0) {
    const centerX = visibleNodes.value.reduce((sum, node) => sum + (node.position?.x || 0), 0) / visibleNodes.value.length
    const centerY = visibleNodes.value.reduce((sum, node) => sum + (node.position?.y || 0), 0) / visibleNodes.value.length
    // L-18: setCenter 第三个参数为 options 对象 { zoom?: number }
    setCenter(centerX + 90, centerY + 30, { zoom: 1 })
  }
}

function toggleLayout() {
  const layouts = ['力导向', '环形', '网格', '分层']
  const currentIndex = layouts.indexOf(currentLayout.value)
  currentLayout.value = layouts[(currentIndex + 1) % layouts.length]
  applyLayout(currentLayout.value)
}

function applyLayout(layout: string) {
  const nodeCount = visibleNodes.value.length
  if (nodeCount === 0) return

  isLayouting.value = true

  if (shouldUseWorker.value && layout === '力导向' && nodeCount > 300) {
    applyLayoutWithWorker(layout)
  } else {
    setTimeout(() => {
      applyLayoutSync(layout)
      isLayouting.value = false
    }, 10)
  }
}

function applyLayoutSync(layout: string) {
  const nodeCount = visibleNodes.value.length
  const centerX = 500
  const centerY = 400

  switch (layout) {
    case '环形':
      visibleNodes.value.forEach((node, index) => {
        const angle = (2 * Math.PI * index) / nodeCount
        node.position = {
          x: centerX + Math.max(200, nodeCount * 3) * Math.cos(angle) - 90,
          y: centerY + Math.max(150, nodeCount * 2.5) * Math.sin(angle) - 30
        }
      })
      break
    case '网格':
    {
      const cols = Math.ceil(Math.sqrt(nodeCount))
      visibleNodes.value.forEach((node, index) => {
        node.position = {
          x: 50 + (index % cols) * 220,
          y: 50 + Math.floor(index / cols) * 160
        }
      })
      break
    }
    case '分层':
      visibleNodes.value.forEach((node, index) => {
        node.position = {
          x: 50 + (index % 6) * 220,
          y: 50 + Math.floor(index / 6) * 160
        }
      })
      break
    case '力导向':
    default:
      visibleNodes.value.forEach((node, index) => {
        node.position = {
          x: 100 + (index % 15) * 150 + Math.random() * 50,
          y: 100 + Math.floor(index / 15) * 120 + Math.random() * 30
        }
      })
  }
}

function applyLayoutWithWorker(layout: string) {
  if (!layoutWorker) {
    try {
      const workerCode = `
        self.onmessage = function(event) {
          const { nodes, layout, centerX, centerY } = event.data
          const positions = {}
          const nodeCount = nodes.length

          switch (layout) {
            case '环形':
              nodes.forEach((node, index) => {
                const angle = (2 * Math.PI * index) / nodeCount
                positions[node.id] = {
                  x: centerX + Math.max(200, nodeCount * 3) * Math.cos(angle) - 90,
                  y: centerY + Math.max(150, nodeCount * 2.5) * Math.sin(angle) - 30
                }
              })
              break
            case '网格':
              const cols = Math.ceil(Math.sqrt(nodeCount))
              nodes.forEach((node, index) => {
                positions[node.id] = {
                  x: 50 + (index % cols) * 220,
                  y: 50 + Math.floor(index / cols) * 160
                }
              })
              break
            case '力导向':
              nodes.forEach((node, index) => {
                positions[node.id] = {
                  x: 100 + (index % 15) * 150 + Math.random() * 50,
                  y: 100 + Math.floor(index / 15) * 120 + Math.random() * 30
                }
              })
              break
            default:
              nodes.forEach((node, index) => {
                positions[node.id] = node.position || { x: 100, y: 100 }
              })
          }

          self.postMessage(positions)
        }
      `
      const blob = new Blob([workerCode], { type: 'application/javascript' })
      layoutWorker = new Worker(URL.createObjectURL(blob))
    } catch (e) {
      console.warn('Failed to create layout worker, using sync layout', e)
      applyLayoutSync(layout)
      isLayouting.value = false
      return
    }
  }

  layoutWorker.onmessage = (event) => {
    const positions = event.data
    visibleNodes.value.forEach(node => {
      if (positions[node.id]) {
        node.position = positions[node.id]
      }
    })
    isLayouting.value = false
  }

  layoutWorker.postMessage({
    nodes: visibleNodes.value.map(n => ({ id: n.id, position: n.position })),
    layout,
    centerX: graphCenter.value.x,
    centerY: graphCenter.value.y
  })
}

function exportGraph() {
  const exportData = {
    nodes: props.nodes,
    edges: props.edges,
    visibleNodes: visibleNodes.value,
    visibleEdges: visibleEdges.value,
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
    if (totalNodesCount.value < 1000) {
      // L-18: fitView 改为 await + setTimeout(50ms) 再触发一次，修复"居中不立即生效"
      fitView()
      setTimeout(() => fitView(), 50)
    }
  }, 200)
})

/**
 * L-22: 定位到指定节点 — 滚动视口到节点位置并高亮 1.5s。
 * 通过 defineExpose 暴露给父组件，替代旧 CustomEvent 方案。
 */
function locateNode(nodeId: string) {
  const node = visibleNodes.value.find(n => n.id === nodeId)
  if (!node || !node.position) {
    return
  }
  // L-18: setCenter 第三个参数为 options 对象 { zoom?: number }
  setCenter(node.position.x + 90, node.position.y + 30, { zoom: 1 })
  // 高亮节点：临时添加 highlight 样式
  const el = document.querySelector(`[data-id="${nodeId}"]`) as HTMLElement | null
  if (el) {
    el.style.transition = 'box-shadow 0.3s ease'
    el.style.boxShadow = '0 0 0 3px #409eff, 0 4px 12px rgba(64, 158, 255, 0.4)'
    setTimeout(() => {
      if (el) el.style.boxShadow = ''
    }, 1500)
  }
}

/**
 * L-21: 自动布局 — 节点加载后调用，使图谱首次打开即呈现有结构的布局。
 */
function runAutoLayout() {
  if (visibleNodes.value.length === 0) return
  applyLayout(currentLayout.value)
}

// L-22: 暴露方法给父组件
defineExpose({
  locateNode,
  runAutoLayout,
  applyLayout,
  centerView,
  fitView
})

onUnmounted(() => {
  if (layoutWorker) {
    layoutWorker.terminate()
    layoutWorker = null
  }
})
</script>

<style scoped>
.graph-container {
  width: 100%;
  height: v-bind(height);
  min-height: 500px;
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
  max-width: 220px;
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
  flex-wrap: wrap;
  gap: 6px;
}

.aggregation-info {
  margin-top: 8px;
}

.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.loading-icon {
  color: #409eff;
  margin-bottom: 16px;
}

.loading-text {
  font-size: 14px;
  color: #606266;
}

:deep(.vue-flow__node) {
  transition: all 0.15s ease;
}

:deep(.vue-flow__node:hover) {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

:deep(.vue-flow__edge) {
  transition: opacity 0.15s ease;
}

:deep(.vue-flow__edge:hover) {
  filter: brightness(1.2);
}
</style>
