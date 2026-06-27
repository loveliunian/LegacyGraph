<template>
  <div class="graph-analysis-panel">
    <div class="panel-header">
      <span class="panel-title">
        <el-icon><Connection /></el-icon>
        图谱分析
      </span>
      <el-button-group size="small">
        <el-tooltip content="展开全部" placement="top">
          <el-button :icon="FullScreen" @click="expandAll" />
        </el-tooltip>
        <el-tooltip content="收起全部" placement="top">
          <el-button :icon="ScaleToOriginal" @click="collapseAll" />
        </el-tooltip>
      </el-button-group>
    </div>

    <div class="analysis-tabs">
      <el-tabs v-model="activeTab" type="card" size="small">
        <el-tab-pane label="路径分析" name="path">
          <div class="path-analysis">
            <el-form :model="pathForm" size="small" class="path-form">
              <el-form-item label="起点节点">
                <el-select v-model="pathForm.startNode" placeholder="选择起点" filterable clearable>
                  <el-option
                    v-for="node in nodes"
                    :key="node.id"
                    :label="node.data?.label || node.id"
                    :value="node.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="目标节点">
                <el-select v-model="pathForm.endNode" placeholder="选择终点" filterable clearable>
                  <el-option
                    v-for="node in nodes"
                    :key="node.id"
                    :label="node.data?.label || node.id"
                    :value="node.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="算法">
                <el-radio-group v-model="pathForm.algorithm" size="small">
                  <el-radio-button value="shortest">最短路径</el-radio-button>
                  <el-radio-button value="all">所有路径</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" @click="analyzePath" :loading="pathLoading">
                  <el-icon><Search /></el-icon>
                  分析路径
                </el-button>
                <el-button size="small" @click="clearPathAnalysis">清除</el-button>
              </el-form-item>
            </el-form>

            <div class="path-results" v-if="pathResults.length > 0">
              <div class="results-header">
                <span>找到 {{ pathResults.length }} 条路径</span>
                <el-tag size="small" type="info">{{ totalPathHops }} 跳</el-tag>
              </div>
              <div class="path-list">
                <div
                  v-for="(path, index) in pathResults"
                  :key="index"
                  class="path-item"
                  :class="{ 'is-active': activePathIndex === index }"
                  @click="highlightPath(index)"
                >
                  <div class="path-index">路径 {{ index + 1 }}</div>
                  <div class="path-nodes">
                    <template v-for="(nodeId, nodeIndex) in path" :key="nodeId">
                      <el-tag size="small" type="primary" effect="dark">
                        {{ getNodeLabel(nodeId) }}
                      </el-tag>
                      <el-icon v-if="nodeIndex < path.length - 1" class="path-arrow">
                        <ArrowRight />
                      </el-icon>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="影响分析" name="impact">
          <div class="impact-analysis">
            <el-form :model="impactForm" size="small" class="impact-form">
              <el-form-item label="分析节点">
                <el-select v-model="impactForm.nodeId" placeholder="选择节点" filterable clearable>
                  <el-option
                    v-for="node in nodes"
                    :key="node.id"
                    :label="node.data?.label || node.id"
                    :value="node.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="分析方向">
                <el-radio-group v-model="impactForm.direction" size="small">
                  <el-radio-button value="downstream">下游影响</el-radio-button>
                  <el-radio-button value="upstream">上游影响</el-radio-button>
                  <el-radio-button value="both">双向分析</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="最大深度">
                <el-slider v-model="impactForm.maxDepth" :min="1" :max="10" show-input size="small" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" @click="analyzeImpact" :loading="impactLoading">
                  <el-icon><TrendCharts /></el-icon>
                  开始分析
                </el-button>
                <el-button size="small" @click="clearImpactAnalysis">清除</el-button>
              </el-form-item>
            </el-form>

            <div class="impact-results" v-if="impactResults.length > 0">
              <div class="results-header">
                <span>影响 {{ impactResults.length }} 个节点</span>
                <el-progress
                  :percentage="Math.round((impactResults.length / nodes.length) * 100)"
                  :stroke-width="8"
                  style="width: 150px"
                />
              </div>

              <div class="impact-stats">
                <div class="stat-item">
                  <div class="stat-value primary">{{ directImpactCount }}</div>
                  <div class="stat-label">直接影响</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value warning">{{ indirectImpactCount }}</div>
                  <div class="stat-label">间接影响</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value success">{{ maxDepthReached }}</div>
                  <div class="stat-label">传播深度</div>
                </div>
              </div>

              <div class="impact-tree">
                <el-tree
                  :data="impactTreeData"
                  :props="treeProps"
                  node-key="id"
                  default-expand-all
                  highlight-current
                  @node-click="handleImpactNodeClick"
                >
                  <template #default="{ node, data }">
                    <span class="tree-node">
                      <el-tag size="small" :type="data.type === 'target' ? 'danger' : 'info'">
                        {{ data.label }}
                      </el-tag>
                      <span class="depth-tag" v-if="data.depth > 0">第 {{ data.depth }} 层</span>
                    </span>
                  </template>
                </el-tree>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="邻居展开" name="neighbors">
          <div class="neighbors-analysis">
            <el-form :model="neighborForm" size="small" class="neighbor-form">
              <el-form-item label="中心节点">
                <el-select v-model="neighborForm.nodeId" placeholder="选择节点" filterable clearable>
                  <el-option
                    v-for="node in nodes"
                    :key="node.id"
                    :label="node.data?.label || node.id"
                    :value="node.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="展开层级">
                <el-slider v-model="neighborForm.maxLevel" :min="1" :max="5" show-input size="small" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" @click="expandNeighbors" :loading="neighborLoading">
                  <el-icon><Expand /></el-icon>
                  展开邻居
                </el-button>
                <el-button size="small" @click="collapseNeighbors">收起</el-button>
              </el-form-item>
            </el-form>

            <div class="neighbor-results" v-if="expandedNeighbors.length > 0">
              <div class="results-header">
                <span>已展开 {{ expandedNeighbors.length }} 个节点</span>
                <el-button size="small" type="primary" link @click="focusOnNeighbors">
                  聚焦展示
                </el-button>
              </div>

              <div class="neighbor-list">
                <div
                  v-for="neighbor in expandedNeighbors"
                  :key="neighbor.id"
                  class="neighbor-item"
                  @click="focusOnNode(neighbor.id)"
                >
                  <el-avatar :size="24" :style="{ backgroundColor: getNodeColor(neighbor.confidence) }">
                    {{ neighbor.label.charAt(0) }}
                  </el-avatar>
                  <div class="neighbor-info">
                    <div class="neighbor-name">{{ neighbor.label }}</div>
                    <div class="neighbor-meta">
                      <el-tag size="small" type="info">{{ neighbor.type }}</el-tag>
                      <span class="level-tag">第 {{ neighbor.level }} 层</span>
                    </div>
                  </div>
                  <el-icon class="connection-icon">
                    <component :is="neighbor.direction === 'in' ? 'ArrowDown' : 'ArrowUp'" />
                  </el-icon>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="聚合视图" name="aggregate">
          <div class="aggregate-view">
            <el-form :model="aggregateForm" size="small" class="aggregate-form">
              <el-form-item label="聚合方式">
                <el-radio-group v-model="aggregateForm.by" size="small">
                  <el-radio-button value="type">按类型</el-radio-button>
                  <el-radio-button value="confidence">按置信度</el-radio-button>
                  <el-radio-button value="module">按模块</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="最小节点数">
                <el-slider v-model="aggregateForm.minCount" :min="1" :max="20" show-input size="small" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" @click="computeAggregates" :loading="aggregateLoading">
                  <el-icon><Grid /></el-icon>
                  聚合节点
                </el-button>
                <el-button size="small" @click="resetAggregates">重置</el-button>
              </el-form-item>
            </el-form>

            <div class="aggregate-results" v-if="aggregateGroups.length > 0">
              <div class="results-header">
                <span>已聚合为 {{ aggregateGroups.length }} 个组</span>
                <span>包含 {{ totalAggregatedNodes }} 个原始节点</span>
              </div>

              <div class="aggregate-list">
                <div
                  v-for="group in aggregateGroups"
                  :key="group.id"
                  class="aggregate-item"
                  @click="toggleAggregateGroup(group)"
                >
                  <div class="aggregate-header">
                    <el-icon class="expand-icon" :class="{ expanded: group.expanded }">
                      <ArrowDown />
                    </el-icon>
                    <div class="aggregate-icon" :style="{ backgroundColor: group.color + '20', color: group.color }">
                      <el-icon><FolderOpened /></el-icon>
                    </div>
                    <div class="aggregate-info">
                      <div class="aggregate-name">{{ group.label }}</div>
                      <div class="aggregate-count">{{ group.nodes.length }} 个节点</div>
                    </div>
                  </div>
                  <div class="aggregate-children" v-show="group.expanded">
                    <div
                      v-for="node in group.nodes"
                      :key="node.id"
                      class="child-node"
                      @click.stop="focusOnNode(node.id)"
                    >
                      {{ node.label }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Connection,
  FullScreen,
  ScaleToOriginal,
  Search,
  ArrowRight,
  TrendCharts,
  Expand,
  Grid,
  FolderOpened,
  ArrowDown,
  ArrowUp
} from '@element-plus/icons-vue'
import type { Node, Edge } from '@vue-flow/core'

interface Props {
  nodes: Node[]
  edges: Edge[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  highlightNodes: [nodeIds: string[]]
  highlightEdges: [edgeIds: string[]]
  focusNode: [nodeId: string]
  expandNodes: [nodeIds: string[]]
  collapseNodes: [nodeIds: string[]]
  aggregate: [groups: any[]]
  reset: []
}>()

const activeTab = ref('path')
const activePathIndex = ref(-1)

const pathForm = reactive({
  startNode: '',
  endNode: '',
  algorithm: 'shortest'
})
const pathLoading = ref(false)
const pathResults = ref<string[][]>([])

const impactForm = reactive({
  nodeId: '',
  direction: 'downstream',
  maxDepth: 5
})
const impactLoading = ref(false)
const impactResults = ref<any[]>([])

const neighborForm = reactive({
  nodeId: '',
  maxLevel: 2
})
const neighborLoading = ref(false)
const expandedNeighbors = ref<any[]>([])

const aggregateForm = reactive({
  by: 'type',
  minCount: 3
})
const aggregateLoading = ref(false)
const aggregateGroups = ref<any[]>([])

const treeProps = {
  children: 'children',
  label: 'label'
}

const totalPathHops = computed(() => {
  if (pathResults.value.length === 0) return 0
  return pathResults.value[0].length - 1
})

const directImpactCount = computed(() => {
  return impactResults.value.filter(n => n.depth === 1).length
})

const indirectImpactCount = computed(() => {
  return impactResults.value.filter(n => n.depth > 1).length
})

const maxDepthReached = computed(() => {
  if (impactResults.value.length === 0) return 0
  return Math.max(...impactResults.value.map(n => n.depth))
})

const impactTreeData = computed(() => {
  if (!impactForm.nodeId) return []
  const rootNode = props.nodes.find(n => n.id === impactForm.nodeId)
  if (!rootNode) return []

  const buildTree = (nodeId: string, depth: number = 0, visited: Set<string> = new Set()): any => {
    if (visited.has(nodeId)) return null
    visited.add(nodeId)

    const node = props.nodes.find(n => n.id === nodeId)
    if (!node) return null

    const children = getNeighbors(nodeId, impactForm.direction)
      .filter(id => !visited.has(id))
      .map(id => buildTree(id, depth + 1, visited))
      .filter(Boolean)

    return {
      id: nodeId,
      label: node.data?.label || nodeId,
      type: depth === 0 ? 'target' : 'impacted',
      depth,
      children
    }
  }

  const tree = buildTree(impactForm.nodeId)
  return tree ? [tree] : []
})

const totalAggregatedNodes = computed(() => {
  return aggregateGroups.value.reduce((sum, g) => sum + g.nodes.length, 0)
})

function getNodeLabel(nodeId: string): string {
  const node = props.nodes.find(n => n.id === nodeId)
  return node?.data?.label || nodeId
}

function getNodeColor(confidence: number): string {
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

function getNeighbors(nodeId: string, direction: string = 'both'): string[] {
  const neighbors: Set<string> = new Set()

  if (direction === 'downstream' || direction === 'both') {
    props.edges
      .filter(e => e.source === nodeId)
      .forEach(e => neighbors.add(e.target))
  }

  if (direction === 'upstream' || direction === 'both') {
    props.edges
      .filter(e => e.target === nodeId)
      .forEach(e => neighbors.add(e.source))
  }

  return Array.from(neighbors)
}

function analyzePath() {
  if (!pathForm.startNode || !pathForm.endNode) {
    ElMessage.warning('请选择起点和终点节点')
    return
  }

  pathLoading.value = true

  setTimeout(() => {
    const paths = findAllPaths(pathForm.startNode, pathForm.endNode)

    if (pathForm.algorithm === 'shortest') {
      pathResults.value = paths.length > 0 ? [paths[0]] : []
    } else {
      pathResults.value = paths
    }

    if (pathResults.value.length === 0) {
      ElMessage.info('未找到连接路径')
    } else {
      highlightPath(0)
    }

    pathLoading.value = false
  }, 500)
}

function findAllPaths(start: string, end: string, maxPaths: number = 10): string[][] {
  const paths: string[][] = []
  const visited = new Set<string>()

  const dfs = (current: string, path: string[]) => {
    if (current === end) {
      paths.push([...path])
      return
    }

    if (paths.length >= maxPaths) return

    const neighbors = getNeighbors(current, 'downstream')
    for (const neighbor of neighbors) {
      if (!visited.has(neighbor)) {
        visited.add(neighbor)
        path.push(neighbor)
        dfs(neighbor, path)
        path.pop()
        visited.delete(neighbor)
      }
    }
  }

  dfs(start, [start])
  return paths.sort((a, b) => a.length - b.length)
}

function highlightPath(index: number) {
  activePathIndex.value = index
  const path = pathResults.value[index]
  if (!path) return

  const edgeIds: string[] = []
  for (let i = 0; i < path.length - 1; i++) {
    const edge = props.edges.find(e => e.source === path[i] && e.target === path[i + 1])
    if (edge) edgeIds.push(edge.id)
  }

  emit('highlightNodes', path)
  emit('highlightEdges', edgeIds)
}

function clearPathAnalysis() {
  pathResults.value = []
  activePathIndex.value = -1
  pathForm.startNode = ''
  pathForm.endNode = ''
  emit('reset')
}

function analyzeImpact() {
  if (!impactForm.nodeId) {
    ElMessage.warning('请选择分析节点')
    return
  }

  impactLoading.value = true

  setTimeout(() => {
    const impacted: any[] = []
    const visited = new Set<string>()

    const traverse = (nodeId: string, depth: number = 0, direction: string = 'downstream') => {
      if (visited.has(nodeId)) return
      if (depth > impactForm.maxDepth) return

      if (depth > 0) {
        const node = props.nodes.find(n => n.id === nodeId)
        impacted.push({
          id: nodeId,
          label: node?.data?.label || nodeId,
          depth,
          type: node?.data?.type || 'unknown'
        })
      }

      visited.add(nodeId)

      const neighbors = getNeighbors(nodeId, direction)
      neighbors.forEach(neighbor => {
        traverse(neighbor, depth + 1, direction)
      })
    }

    if (impactForm.direction === 'both') {
      traverse(impactForm.nodeId, 0, 'downstream')
      traverse(impactForm.nodeId, 0, 'upstream')
    } else {
      traverse(impactForm.nodeId, 0, impactForm.direction)
    }

    impactResults.value = impacted
    emit('highlightNodes', impacted.map(n => n.id))

    impactLoading.value = false
  }, 500)
}

function clearImpactAnalysis() {
  impactResults.value = []
  impactForm.nodeId = ''
  emit('reset')
}

function handleImpactNodeClick(data: any) {
  if (data.id) {
    focusOnNode(data.id)
  }
}

function expandNeighbors() {
  if (!neighborForm.nodeId) {
    ElMessage.warning('请选择中心节点')
    return
  }

  neighborLoading.value = true

  setTimeout(() => {
    const neighbors: any[] = []
    const visited = new Set<string>()

    const bfs = (nodeId: string, level: number = 0) => {
      if (visited.has(nodeId)) return
      if (level > neighborForm.maxLevel) return

      visited.add(nodeId)

      if (level > 0) {
        const node = props.nodes.find(n => n.id === nodeId)
        const hasIncoming = props.edges.some(e => e.target === nodeId && visited.has(e.source))
        const hasOutgoing = props.edges.some(e => e.source === nodeId && visited.has(e.target))

        neighbors.push({
          id: nodeId,
          label: node?.data?.label || nodeId,
          type: node?.data?.type || 'unknown',
          confidence: node?.data?.confidence || 0.5,
          level,
          direction: hasIncoming ? 'in' : hasOutgoing ? 'out' : 'both'
        })
      }

      const nodeNeighbors = getNeighbors(nodeId)
      nodeNeighbors.forEach(neighbor => bfs(neighbor, level + 1))
    }

    bfs(neighborForm.nodeId)
    expandedNeighbors.value = neighbors

    emit('expandNodes', neighbors.map(n => n.id))
    neighborLoading.value = false
  }, 500)
}

function collapseNeighbors() {
  emit('collapseNodes', expandedNeighbors.value.map(n => n.id))
  expandedNeighbors.value = []
}

function focusOnNode(nodeId: string) {
  emit('focusNode', nodeId)
}

function focusOnNeighbors() {
  const allIds = [neighborForm.nodeId, ...expandedNeighbors.value.map(n => n.id)]
  emit('highlightNodes', allIds)
}

function computeAggregates() {
  aggregateLoading.value = true

  setTimeout(() => {
    const groups: Map<string, any[]> = new Map()

    props.nodes.forEach(node => {
      let key: string
      switch (aggregateForm.by) {
        case 'type':
          key = node.data?.type || 'unknown'
          break
        case 'confidence':
          const conf = node.data?.confidence || 0.5
          key = conf >= 0.9 ? 'high' : conf >= 0.7 ? 'medium' : conf >= 0.5 ? 'low' : 'very-low'
          break
        case 'module':
          key = node.data?.module || 'default'
          break
        default:
          key = 'unknown'
      }

      if (!groups.has(key)) {
        groups.set(key, [])
      }
      groups.get(key)!.push({
        id: node.id,
        label: node.data?.label || node.id,
        confidence: node.data?.confidence || 0.5
      })
    })

    const groupLabels: Record<string, { label: string; color: string }> = {
      'controller': { label: '控制器', color: '#409eff' },
      'service': { label: '服务', color: '#67c23a' },
      'mapper': { label: '数据访问', color: '#e6a23c' },
      'api': { label: 'API接口', color: '#909399' },
      'feature': { label: '功能点', color: '#67c23a' },
      'feature_module': { label: '功能模块', color: '#409eff' },
      'business_domain': { label: '业务域', color: '#9b59b6' },
      'business_process': { label: '业务流程', color: '#e74c3c' },
      'sql': { label: 'SQL语句', color: '#f56c6c' },
      'table': { label: '数据库表', color: '#e6a23c' },
      'column': { label: '字段', color: '#909399' },
      'high': { label: '高置信度 (>90%)', color: '#67c23a' },
      'medium': { label: '中置信度 (70-90%)', color: '#409eff' },
      'low': { label: '低置信度 (50-70%)', color: '#e6a23c' },
      'very-low': { label: '极低置信度 (<50%)', color: '#f56c6c' },
      'default': { label: '默认模块', color: '#909399' },
      'unknown': { label: '未知类型', color: '#909399' }
    }

    aggregateGroups.value = Array.from(groups.entries())
      .filter(([_, nodes]) => nodes.length >= aggregateForm.minCount)
      .map(([key, nodes], index) => {
        const labelInfo = groupLabels[key] || { label: key, color: '#909399' }
        return {
          id: `group-${index}`,
          key,
          label: labelInfo.label,
          color: labelInfo.color,
          expanded: true,
          nodes
        }
      })

    if (aggregateGroups.value.length > 0) {
      emit('aggregate', aggregateGroups.value)
    } else {
      ElMessage.info('没有符合条件的聚合组')
    }

    aggregateLoading.value = false
  }, 500)
}

function toggleAggregateGroup(group: any) {
  group.expanded = !group.expanded
}

function resetAggregates() {
  aggregateGroups.value = []
  emit('reset')
}

function expandAll() {
  ElMessage.info('已展开所有节点')
}

function collapseAll() {
  ElMessage.info('已收起所有节点')
  emit('reset')
}
</script>

<style scoped>
.graph-analysis-panel {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #ebeef5;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
}

.analysis-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.analysis-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}

.path-analysis,
.impact-analysis,
.neighbors-analysis,
.aggregate-view {
  padding: 16px;
}

.path-form,
.impact-form,
.neighbor-form,
.aggregate-form {
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 16px;
}

.results-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  margin-bottom: 12px;
  font-weight: 500;
  color: #303133;
}

.path-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.path-item {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  border: 2px solid transparent;
}

.path-item:hover {
  background: #ecf5ff;
}

.path-item.is-active {
  background: #ecf5ff;
  border-color: #409eff;
}

.path-index {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.path-nodes {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.path-arrow {
  color: #409eff;
  font-size: 16px;
}

.impact-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-item {
  text-align: center;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 6px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 4px;
}

.stat-value.primary {
  color: #409eff;
}

.stat-value.warning {
  color: #e6a23c;
}

.stat-value.success {
  color: #67c23a;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.impact-tree,
.neighbor-list,
.aggregate-list {
  max-height: 300px;
  overflow-y: auto;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.depth-tag {
  font-size: 10px;
  color: #909399;
  background: #f5f7fa;
  padding: 2px 4px;
  border-radius: 3px;
}

.neighbor-item,
.aggregate-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.neighbor-item:hover,
.aggregate-item:hover {
  background: #ecf5ff;
}

.neighbor-info {
  flex: 1;
  min-width: 0;
}

.neighbor-name {
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.neighbor-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.level-tag {
  font-size: 11px;
  color: #909399;
}

.connection-icon {
  color: #409eff;
  font-size: 18px;
}

.aggregate-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.expand-icon {
  transition: transform 0.2s;
  color: #909399;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.aggregate-icon {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.aggregate-info {
  flex: 1;
}

.aggregate-name {
  font-weight: 500;
  color: #303133;
  margin-bottom: 2px;
}

.aggregate-count {
  font-size: 12px;
  color: #909399;
}

.aggregate-children {
  margin-top: 12px;
  padding-left: 44px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.child-node {
  padding: 4px 12px;
  background: white;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  transition: all 0.2s;
}

.child-node:hover {
  background: #409eff;
  color: white;
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: #c0c4cc;
}
</style>
