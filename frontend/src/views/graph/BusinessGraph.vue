<template>
  <div class="business-graph">
    <!-- 页面头部：标题 + 内联统计 + AI视图切换 -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-title-row">
          <h3>业务图谱</h3>
          <div
            v-if="graphNodeCount > 0 || graphEdgeCount > 0"
            class="inline-stats">
            <span class="stat-item">
              <span class="stat-num primary">{{ graphNodeCount }}</span>
              <span class="stat-text">节点</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num info">{{ graphEdgeCount }}</span>
              <span class="stat-text">关系</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num success">{{ graphStats.autoMergeRate }}%</span>
              <span class="stat-text">自动合并</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num warning">{{ graphStats.pendingReview }}</span>
              <span class="stat-text">待审核</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num primary">{{ (graphStats.avgConfidence * 100).toFixed(1) }}%</span>
              <span class="stat-text">置信度</span>
            </span>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-button
          type="primary"
          size="small"
          @click="toggleAiView">
          <el-icon><View /></el-icon>
          {{ showAiView ? '显示原始' : 'AI归纳视图' }}
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 左侧：业务领域树 -->
      <el-col :span="5">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">业务领域</span>
          </div>
          <div class="panel-body">
            <el-tree
              :data="domainTree"
              :props="{ label: 'name', children: 'children' }"
              node-key="id"
              default-expand-all
              @node-click="handleDomainClick"
            >
              <template #default="{ node, data }">
                <span class="custom-tree-node">
                  <span>{{ node.label }}</span>
                  <el-tag
                    v-if="data.confidence"
                    size="small"
                    :type="data.confidence >= 0.8 ? 'success' : data.confidence >= 0.6 ? 'warning' : 'danger'"
                    style="margin-left: 8px;">
                    {{ (data.confidence * 100).toFixed(0) }}%
                  </el-tag>
                </span>
              </template>
            </el-tree>
          </div>
        </div>
      </el-col>

      <!-- 中间：图谱区域 -->
      <el-col :span="14">
        <div class="graph-area">
          <div class="graph-toolbar">
            <span class="toolbar-view">当前视图: {{ showAiView ? 'AI归纳' : '原始数据' }}</span>
            <el-tag type="info">{{ graphNodeCount }} 节点 / {{ graphEdgeCount }} 关系</el-tag>
          </div>
          <GraphViewer
            v-loading="loading"
            :nodes="flowNodes"
            :edges="flowEdges"
            height="600px"
            :editable="false"
            @node-click="handleGraphNodeClick"
          />
        </div>
      </el-col>

      <!-- 右侧：节点详情 -->
      <el-col :span="5">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">节点详情</span>
          </div>
          <div class="panel-body">
            <div
              v-if="!selectedNode"
              class="empty-state">
              <el-empty description="点击节点查看详情" />
            </div>
            <div
              v-else
              class="node-detail">
              <div class="node-name">{{ selectedNode.label }}</div>
              <div class="node-type-row">
                <el-tag size="small">{{ selectedNode.type }}</el-tag>
                <el-tag
                  size="small"
                  :type="selectedNode.confidence >= 0.85 ? 'success' : selectedNode.confidence >= 0.7 ? 'warning' : 'danger'">
                  置信度 {{ (selectedNode.confidence * 100).toFixed(1) }}%
                </el-tag>
              </div>
              <div class="node-properties">
                <div class="property-item">
                  <span class="property-label">节点ID</span>
                  <span class="property-value">{{ selectedNode.id }}</span>
                </div>
                <div
                  v-if="selectedNode.description"
                  class="property-item">
                  <span class="property-label">描述</span>
                  <span class="property-value">{{ selectedNode.description }}</span>
                </div>
              </div>
              <div
                v-if="selectedNode.evidence && selectedNode.evidence.length > 0"
                class="evidence-list">
                <div class="evidence-title">证据来源:</div>
                <el-tag
                  v-for="ev in selectedNode.evidence"
                  :key="ev.sourceUri"
                  size="small"
                  class="evidence-tag">
                  {{ ev.sourceType }}: {{ ev.sourceUri.split('/').pop() }}
                </el-tag>
              </div>
              <div class="action-buttons">
                <el-button
                  type="primary"
                  size="small"
                  @click="generateTestCases">
                  生成测试用例
                </el-button>
                <el-button
                  size="small"
                  @click="goToReview">
                  进入审核
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { View } from '@element-plus/icons-vue'
import type { Node as FlowNode, Edge as FlowEdge } from '@vue-flow/core'
import GraphViewer from '@/components/graph/GraphViewerOptimized.vue'
import { graphApi } from '@/api'
import { loadScanVersions } from '@/utils/versionsCache'

interface FlowNodeData {
  label: string
  type: string
  confidence: number
  status: string
  evidenceCount: number
  description?: string
  properties?: Record<string, any>
}

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = ref<string>('')
const versions = ref<any[]>([])

const showAiView = ref(false)
const selectedNode = ref<any | null>(null)
const selectedDomain = ref('')
const loading = ref(false)
const flowNodes = ref<FlowNode<FlowNodeData>[]>([])
const flowEdges = ref<FlowEdge[]>([])
const domainTree = ref<any[]>([])

const graphNodeCount = computed(() => flowNodes.value.length)
const graphEdgeCount = computed(() => flowEdges.value.length)

const graphStats = ref({
  autoMergeRate: 0,
  pendingReview: 0,
  avgConfidence: 0,
  testPassRate: 0
})

async function loadVersions() {
  try {
    const pid = projectId.value
    if (!pid) return
    const result = await loadScanVersions(pid)
    versions.value = result || []
  } catch (error) {
    console.error('loadVersions error:', error)
    ElMessage.error('操作失败')
  }
  }

function buildDomainTree(rawNodes: any[]) {
  const nodes = rawNodes.map((n: any) => {
    const props = n.properties || n
    return {
      id: n.id || n.elementId || props.id,
      key: props.nodeKey || props.key || n.elementId || n.id,
      label: props.displayName || props.label || props.nodeName || props.name || n.id,
      type: props.nodeType || (Array.isArray(n.labels) ? n.labels[0] : 'Node'),
      confidence: Number(props.confidence ?? 0.5),
      status: props.status || 'PENDING_CONFIRM',
    }
  })
  const domainNodes = nodes.filter((n: any) => n.type === 'BusinessDomain')
  const seen = new Set<string>()
  const domains = domainNodes
    .map((n: any) => ({
      id: n.key || n.id,
      name: n.label || n.name || n.key || n.id,
      confidence: Number(n.confidence ?? 0.5),
      children: []
    }))
    .filter((n: any) => {
      if (!n.name || seen.has(n.name)) return false
      seen.add(n.name)
      return true
    })

  domainTree.value = [
    {
      id: '__all__',
      name: '全部业务图谱',
      confidence: nodes.length
        ? nodes.reduce((s: number, n: any) => s + Number(n.confidence ?? 0.5), 0) / nodes.length
        : 0,
      children: []
    },
    ...domains
  ]

  const confirmed = nodes.filter((n: any) => n.status === 'CONFIRMED' || n.status === 'approved').length
  graphStats.value = {
    autoMergeRate: nodes.length > 0 ? Math.round(confirmed / nodes.length * 100) : 0,
    pendingReview: nodes.filter((n: any) => n.status === 'PENDING_CONFIRM' || n.status === 'pending').length,
    avgConfidence: nodes.length > 0
      ? nodes.reduce((s: number, n: any) => s + Number(n.confidence ?? 0.5), 0) / nodes.length
      : 0,
    testPassRate: 0,
  }
}

async function loadGraph(domain = '') {
  if (!projectId.value || !currentVersion.value) return
  loading.value = true
  try {
    const data = await graphApi.getBusinessView(projectId.value, currentVersion.value, domain)
    const nodes = Array.isArray(data?.nodes) ? data.nodes : []
    const edges = Array.isArray(data?.edges) ? data.edges : []

    selectedDomain.value = domain
    selectedNode.value = null
    flowNodes.value = nodes.map(toFlowNode)
    flowEdges.value = edges.map(toFlowEdge).filter((edge: FlowEdge) => edge.source && edge.target)

    // 首次加载时一并构建领域树（domain 为空表示全量加载）
    if (!domain) {
      buildDomainTree(nodes)
    }

    if (nodes.length === 0) {
      ElMessage.info(domain ? '该业务域暂无图谱数据' : '当前版本暂无业务图谱数据')
    } else {
      ElMessage.success(`加载完成: ${nodes.length} 节点`)
    }
  } catch (error) {
    console.error('加载业务图谱失败', error)
    flowNodes.value = []
    flowEdges.value = []
    ElMessage.error('加载业务图谱失败')
  } finally {
    loading.value = false
  }
}

function toFlowNode(node: any, index: number): FlowNode<FlowNodeData> {
  const props = node.properties || node
  const labels = normalizeLabels(node.labels)
  const id = normalizeId(node.id || node.elementId || props.id || props.nodeKey || props.key || `node-${index}`)
  const type = props.nodeType || node.type || labels[0] || 'Node'
  const label = props.displayName || props.label || props.nodeName || props.name || props.nodeKey || props.key || id
  return {
    id,
    type: 'custom',
    position: gridPosition(index),
    data: {
      label,
      type,
      confidence: toNumber(props.confidence ?? node.confidence, 0.8),
      status: props.status || node.status || 'PENDING_CONFIRM',
      evidenceCount: toNumber(props.evidenceCount ?? node.evidenceCount, 0),
      description: props.description || node.description,
      properties: props
    }
  }
}

function toFlowEdge(edge: any, index: number): FlowEdge {
  const props = edge.properties || edge.data || {}
  const source = normalizeId(edge.source || edge.startNodeId || edge.startNodeElementId || edge.fromNodeId || edge.from)
  const target = normalizeId(edge.target || edge.endNodeId || edge.endNodeElementId || edge.toNodeId || edge.to)
  return {
    id: normalizeId(edge.id || `${source}-${target}-${index}`),
    source,
    target,
    label: edge.label || edge.type || props.type || '',
    data: {
      confidence: toNumber(edge.confidence ?? props.confidence, 0.8),
      ...props
    }
  }
}

function normalizeLabels(labels: any): string[] {
  if (Array.isArray(labels)) return labels.map(String)
  if (labels && typeof labels[Symbol.iterator] === 'function') return Array.from(labels, String)
  return labels ? [String(labels)] : []
}

function normalizeId(value: any): string {
  return value == null ? '' : String(value)
}

function toNumber(value: any, fallback: number): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

function gridPosition(index: number) {
  const cols = 5
  return {
    x: 80 + (index % cols) * 230,
    y: 100 + Math.floor(index / cols) * 150
  }
}

function handleGraphNodeClick(node: FlowNode<FlowNodeData>) {
  selectedNode.value = {
    id: node.id,
    ...node.data
  }
}

const handleDomainClick = (data: any) => {
  const domain = data.id === '__all__' ? '' : data.name
  loadGraph(domain)
}

const toggleAiView = () => {
  showAiView.value = !showAiView.value
  ElMessage.success(showAiView.value ? '已切换到AI归纳视图' : '已切换到原始视图')
}

const generateTestCases = () => {
  if (!selectedNode.value) return
  router.push(`/projects/${projectId.value}/test-cases?nodeId=${selectedNode.value.id}`)
}

const goToReview = () => {
  if (!selectedNode.value) return
  router.push(`/projects/${projectId.value}/reviews?nodeId=${selectedNode.value.id}`)
}

onMounted(async () => {
  try {
    await loadVersions()
    // 优先使用 URL 参数指定的版本，否则自动选择第一个版本
    const urlVersion = (route.query.version as string) || ''
    if (urlVersion && versions.value.some(v => v.id === urlVersion)) {
      currentVersion.value = urlVersion
    } else if (versions.value.length > 0) {
      currentVersion.value = versions.value[0].id
    }
    if (currentVersion.value) {
      const domainQuery = route.query.domain as string
      await loadGraph(domainQuery || '')
    }
  } catch (error) {
    console.error('onMounted error:', error)
    ElMessage.error('页面初始化失败')
  }
})
</script>

<style scoped>
.business-graph {
  padding: 16px;
}

/* ===== 页面头部 ===== */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.header-title-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.header-left h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

/* ===== 行内统计 ===== */
.inline-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.inline-stats .stat-item {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}

.stat-num {
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
}

.stat-num.primary {
  color: var(--el-color-primary);
}

.stat-num.success {
  color: var(--el-color-success);
}

.stat-num.warning {
  color: var(--el-color-warning);
}

.stat-num.info {
  color: var(--el-text-color-secondary);
}

.stat-text {
  color: var(--el-text-color-secondary);
}

.stat-sep {
  color: var(--el-border-color);
}

/* ===== 面板通用样式 ===== */
.panel-section {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-body {
  padding: 12px 14px;
}

/* ===== 业务领域树 ===== */
.custom-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 1;
}

/* ===== 图谱区域 ===== */
.graph-area {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
  font-size: 13px;
}

.toolbar-view {
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.toolbar-stats {
  color: var(--el-text-color-secondary);
}

/* ===== 节点详情 ===== */
.empty-state {
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.node-detail {
  max-height: 500px;
  overflow-y: auto;
}

.node-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 10px;
  word-break: break-word;
  line-height: 1.4;
}

.node-type-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.node-properties {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
  padding-top: 4px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.property-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.property-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.property-value {
  font-size: 13px;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.evidence-list {
  margin-top: 12px;
}

.evidence-title {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
}

.evidence-tag {
  margin-right: 6px;
  margin-bottom: 6px;
}

.action-buttons {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
</style>
