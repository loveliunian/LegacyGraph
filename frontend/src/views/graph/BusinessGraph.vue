<template>
  <div class="business-graph">
    <div class="page-header">
      <h3>业务图谱</h3>
      <el-button
        type="primary"
        size="small"
        @click="toggleAiView">
        <el-icon><View /></el-icon>
        {{ showAiView ? '显示原始' : 'AI归纳视图' }}
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="5">
        <el-card class="domain-card">
          <template #header>
            <span>业务领域</span>
          </template>
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
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <span>当前视图: {{ showAiView ? 'AI归纳' : '原始数据' }}</span>
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
        </el-card>
      </el-col>

      <el-col :span="5">
        <el-card class="detail-card">
          <template #header>
            <span>节点详情</span>
          </template>
          <div
            v-if="!selectedNode"
            class="empty-state">
            <el-empty description="点击节点查看详情" />
          </div>
          <div
            v-else
            class="node-detail">
            <el-descriptions
              :column="1"
              border>
              <el-descriptions-item label="节点ID">{{ selectedNode.id }}</el-descriptions-item>
              <el-descriptions-item label="名称">{{ selectedNode.label }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ selectedNode.type }}</el-descriptions-item>
              <el-descriptions-item label="置信度">
                <el-tag :type="selectedNode.confidence >= 0.85 ? 'success' : selectedNode.confidence >= 0.7 ? 'warning' : 'danger'">
                  {{ (selectedNode.confidence * 100).toFixed(1) }}%
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item
                v-if="selectedNode.description"
                label="描述">
                {{ selectedNode.description }}
              </el-descriptions-item>
            </el-descriptions>
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
            <div
              class="action-buttons"
              style="margin-top: 16px;">
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
        </el-card>

        <el-card
          class="ai-card"
          style="margin-top: 16px;">
          <template #header>
            <div class="card-header">
              <span><el-icon><MagicStick /></el-icon> AI 统计</span>
            </div>
          </template>
          <div class="ai-stats">
            <div class="stat-item">
              <span class="stat-label">自动合并率</span>
              <span class="stat-value">{{ graphStats.autoMergeRate }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">待审核</span>
              <span class="stat-value">{{ graphStats.pendingReview }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">平均置信度</span>
              <span class="stat-value">{{ (graphStats.avgConfidence * 100).toFixed(1) }}%</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">测试通过率</span>
              <span class="stat-value">{{ graphStats.testPassRate }}%</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { View, MagicStick } from '@element-plus/icons-vue'
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
  const pid = projectId.value
  if (!pid) return
  const result = await loadScanVersions(pid)
  versions.value = result || []
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
})
</script>

<style scoped>
.business-graph {
  padding: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.custom-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 1;
}

.graph-card {
  height: 100%;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.graph-container {
  min-height: 600px;
  background: #fafafa;
  border-radius: 4px;
}

.graph-container > div {
  border-radius: 4px;
  overflow: hidden;
}

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

.evidence-list {
  margin-top: 12px;
}

.evidence-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.evidence-tag {
  margin-right: 6px;
  margin-bottom: 6px;
}

.action-buttons {
  display: flex;
  gap: 8px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-stats {
  font-size: 13px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.stat-item:last-child {
  border-bottom: none;
}

.stat-label {
  color: #606266;
}

.stat-value {
  font-weight: 600;
  color: #409eff;
}

.ai-analysis {
  font-size: 13px;
  line-height: 1.8;
  color: #606266;
}

.ai-analysis ol {
  margin: 12px 0 0 0;
  padding-left: 20px;
}

.ai-analysis li {
  margin-bottom: 8px;
}
</style>
