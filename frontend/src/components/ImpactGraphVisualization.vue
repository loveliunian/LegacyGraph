<template>
  <div class="impact-graph-viz">
    <!-- 图例 -->
    <div class="legend-bar">
      <span
        v-for="level in levelOrder"
        :key="level.key"
        class="legend-item">
        <span
          class="legend-dot"
          :style="{ background: level.color }" />
        {{ level.label }}
      </span>
    </div>

    <!-- SVG 图区 -->
    <div
      ref="containerRef"
      class="svg-container">
      <svg
        :width="width"
        :height="height"
        class="impact-svg">
        <!-- 箭头定义 -->
        <defs>
          <marker
            id="arrowhead"
            markerWidth="8"
            markerHeight="6"
            refX="7"
            refY="3"
            orient="auto">
            <polygon points="0 0, 8 3, 0 6" fill="#909399" />
          </marker>
        </defs>
        <!-- 边 -->
        <g class="edges-layer">
          <g
            v-for="(edge, i) in edgeLines"
            :key="'e' + i"
            class="edge-group">
            <line
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
              class="edge-line"
              :stroke-dasharray="edge.dashed ? '4 3' : 'none'"
              :marker-end="edge.arrowMarker" />
            <text
              :x="(edge.x1 + edge.x2) / 2"
              :y="(edge.y1 + edge.y2) / 2 - 4"
              class="edge-label"
              text-anchor="middle">
              {{ edge.relationType }}
            </text>
          </g>
        </g>

        <!-- 节点 -->
        <g class="nodes-layer">
          <g
            v-for="node in nodeLayout"
            :key="node.id"
            class="node-group"
            :transform="`translate(${node.x}, ${node.y})`">
            <circle
              :r="node.radius"
              :fill="node.color"
              :stroke="node.highlight ? '#e63757' : '#fff'"
              :stroke-width="node.highlight ? 3 : 1.5"
              class="node-circle"
              @mouseenter="onHover(node)"
              @mouseleave="onHover(null)" />
            <text
              y="4"
              class="node-label"
              text-anchor="middle"
              :font-size="10">
              {{ node.shortLabel }}
            </text>
            <text
              :y="node.radius + 14"
              class="node-type"
              text-anchor="middle"
              :font-size="9">
              {{ node.impactLevel }}
            </text>
          </g>
        </g>

        <!-- 空状态 -->
        <text
          v-if="nodeLayout.length === 0"
          :x="width / 2"
          :y="height / 2"
          class="empty-text"
          text-anchor="middle">
          暂无影响数据
        </text>
      </svg>
    </div>

    <!-- 悬浮提示 -->
    <div
      v-if="hovered"
      class="hover-tip"
      :style="{ left: tipX + 'px', top: tipY + 'px' }">
      <div class="tip-title">{{ hovered.label }}</div>
      <div class="tip-row">类型：{{ hovered.type || '-' }}</div>
      <div class="tip-row">层级：{{ hovered.impactLevel }}</div>
      <div class="tip-row">深度：{{ hovered.depth }}</div>
      <div class="tip-row">风险：{{ formatScore(hovered.riskScore) }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { VizNode, VizEdge } from '@/api'

const props = defineProps<{
  nodes: VizNode[]
  edges: VizEdge[]
}>()

const containerRef = ref<HTMLElement | null>(null)
const width = ref(720)
const height = ref(520)
const hovered = ref<(VizNode & { x: number; y: number }) | null>(null)
const tipX = ref(0)
const tipY = ref(0)

/** 影响层级 → 颜色映射 */
const levelColors: Record<string, string> = {
  L0: '#e63757',
  L1: '#ff8c00',
  L2: '#ffd700',
  L3: '#4a90d9',
  L4: '#b0b0b0',
}

const levelOrder = [
  { key: 'L0', label: 'L0 直接', color: levelColors.L0 },
  { key: 'L1', label: 'L1 代码', color: levelColors.L1 },
  { key: 'L2', label: 'L2 交互', color: levelColors.L2 },
  { key: 'L3', label: 'L3 质量', color: levelColors.L3 },
  { key: 'L4', label: 'L4 架构', color: levelColors.L4 },
]

/** 计算节点布局：按 depth 分层径向排列（L0 居中，其余按层环形分布） */
const nodeLayout = computed(() => {
  const nodes = props.nodes
  if (!nodes || nodes.length === 0) return []

  const cx = width.value / 2
  const cy = height.value / 2
  const ringGap = Math.min(width.value, height.value) / 6

  // 按 depth 分组
  const byDepth = new Map<number, VizNode[]>()
  for (const n of nodes) {
    const d = n.depth ?? 0
    if (!byDepth.has(d)) byDepth.set(d, [])
    byDepth.get(d)!.push(n)
  }

  const layout: Array<VizNode & { x: number; y: number; radius: number; color: string; shortLabel: string; highlight: boolean }> = []
  for (const [depth, group] of byDepth.entries()) {
    if (depth === 0) {
      // 中心层：居中或水平排列
      group.forEach((n, i) => {
        const offset = group.length > 1 ? (i - (group.length - 1) / 2) * 100 : 0
        layout.push({
          ...n,
          x: cx + offset,
          y: cy,
          radius: nodeRadius(n),
          color: levelColors[n.impactLevel] ?? levelColors.L4,
          shortLabel: truncate(n.label, 8),
          highlight: isHighRisk(n),
        })
      })
    } else {
      // 外层：环形分布
      const radius = ringGap * depth
      const step = (2 * Math.PI) / group.length
      const startAngle = -Math.PI / 2 // 从正上方开始
      group.forEach((n, i) => {
        const angle = startAngle + step * i
        layout.push({
          ...n,
          x: cx + radius * Math.cos(angle),
          y: cy + radius * Math.sin(angle),
          radius: nodeRadius(n),
          color: levelColors[n.impactLevel] ?? levelColors.L4,
          shortLabel: truncate(n.label, 8),
          highlight: isHighRisk(n),
        })
      })
    }
  }
  return layout
})

/** 计算边连线坐标（source/target 引用的是 nodeKey，这里用 label 近似匹配） */
const edgeLines = computed(() => {
  const lines: Array<{
    x1: number; y1: number; x2: number; y2: number
    relationType: string; dashed: boolean; arrowMarker: string
  }> = []
  if (!props.edges || props.edges.length === 0) return lines

  // 构建可查找的坐标表：同时支持按 id 和 label 匹配
  const lookup = new Map<string, { x: number; y: number; radius: number }>()
  for (const n of nodeLayout.value) {
    lookup.set(n.id, { x: n.x, y: n.y, radius: n.radius })
    lookup.set(n.label, { x: n.x, y: n.y, radius: n.radius })
  }

  for (const edge of props.edges) {
    const src = lookup.get(edge.source)
    const dst = lookup.get(edge.target)
    if (!src || !dst) continue
    lines.push({
      x1: src.x,
      y1: src.y,
      x2: dst.x,
      y2: dst.y,
      relationType: edge.relationType || '',
      dashed: edge.relationType === 'DATA_FLOW',
      arrowMarker: 'url(#arrowhead)',
    })
  }
  return lines
})

/** 节点半径：基于 riskScore 映射到 [12, 28] */
function nodeRadius(node: VizNode): number {
  const score = node.riskScore ?? 0
  return 12 + score * 16
}

/** 判断是否高风险节点 */
function isHighRisk(node: VizNode): boolean {
  return (node.riskScore ?? 0) >= 0.7
}

/** 截断标签 */
function truncate(text: string, max: number): string {
  if (!text) return '-'
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** 格式化风险分数 */
function formatScore(score: number): string {
  return (score ?? 0).toFixed(3)
}

/** 悬浮事件 */
function onHover(node: (VizNode & { x: number; y: number }) | null) {
  hovered.value = node
  if (node) {
    tipX.value = node.x + 20
    tipY.value = node.y - 10
  }
}

// 监听容器尺寸变化（简化处理）
watch(containerRef, (el) => {
  if (el) {
    const rect = el.getBoundingClientRect()
    if (rect.width > 0) width.value = Math.min(rect.width, 900)
  }
})
</script>

<style scoped>
.impact-graph-viz {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.legend-bar {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-regular);
}

.legend-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1px solid #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.15);
}

.svg-container {
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color-page);
}

.impact-svg {
  display: block;
}

.edge-line {
  stroke: var(--el-text-color-secondary);
  stroke-width: 1.2;
  opacity: 0.6;
}

.edge-label {
  fill: var(--el-text-color-placeholder);
  font-size: 8px;
  opacity: 0.7;
}

.node-circle {
  cursor: pointer;
  transition: stroke-width 0.15s;
}

.node-circle:hover {
  stroke-width: 4;
}

.node-label {
  fill: #fff;
  font-weight: 600;
  pointer-events: none;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.4);
}

.node-type {
  fill: var(--el-text-color-secondary);
  pointer-events: none;
}

.empty-text {
  fill: var(--el-text-color-placeholder);
  font-size: 14px;
}

.hover-tip {
  position: absolute;
  z-index: 10;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 8px 12px;
  box-shadow: var(--el-box-shadow-light);
  font-size: 12px;
  pointer-events: none;
  white-space: nowrap;
}

.tip-title {
  font-weight: 600;
  margin-bottom: 4px;
  color: var(--el-text-color-primary);
}

.tip-row {
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
</style>
