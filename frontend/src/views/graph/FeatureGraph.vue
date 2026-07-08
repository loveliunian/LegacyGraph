<template>
  <div class="feature-graph">
    <div class="page-header">
      <h3>功能图谱</h3>
      <el-button
        type="primary"
        size="small"
        @click="exportReport">
        <el-icon><Download /></el-icon>
        导出功能清单
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="module-card">
          <template #header>
            <span>功能模块</span>
          </template>
          <div class="module-list">
            <div
              v-for="module in modules"
              :key="module.id"
              class="module-item"
              :class="{ active: selectedModule === module.id }"
              @click="selectModule(module.id)"
            >
              <div
                class="module-icon"
                :style="{ backgroundColor: module.color }">
                <el-icon><Grid /></el-icon>
              </div>
              <div class="module-info">
                <div class="module-name">{{ module.name }}</div>
                <div class="module-stats">
                  {{ module.featureCount }} 个功能点
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <span>模块: {{ getSelectedModuleName() }}</span>
            <el-tag type="info">{{ flowNodes.length }} 节点 / {{ flowEdges.length }} 关系</el-tag>
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

      <el-col :span="6">
        <el-card class="detail-card">
          <template #header>
            <span>功能详情</span>
          </template>
          <div
            v-if="!selectedNode"
            class="empty-state">
            <el-empty description="点击功能节点查看详情" />
          </div>
          <div
            v-else
            class="node-detail">
            <el-descriptions
              :column="1"
              border
              size="small">
              <el-descriptions-item label="功能">{{ selectedNode.label }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ selectedNode.type }}</el-descriptions-item>
              <el-descriptions-item
                v-if="selectedNode.page"
                label="页面">
                {{ selectedNode.page }}
              </el-descriptions-item>
              <el-descriptions-item
                v-if="selectedNode.api"
                label="API">
                {{ selectedNode.api }}
              </el-descriptions-item>
              <el-descriptions-item label="置信度">
                <el-tag :type="selectedNode.confidence >= 0.85 ? 'success' : selectedNode.confidence >= 0.7 ? 'warning' : 'danger'">
                  {{ (selectedNode.confidence * 100).toFixed(1) }}%
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <div
              class="action-buttons"
              style="margin-top: 16px;">
              <el-button
                type="primary"
                size="small"
                @click="generateTests">
                生成测试
              </el-button>
              <el-button
                size="small"
                @click="viewEvidence">
                查看证据
              </el-button>
            </div>
          </div>
        </el-card>

        <el-card
          v-if="projectId && currentVersion"
          class="test-card"
          style="margin-top: 16px;">
          <template #header>
            <span>测试覆盖率</span>
          </template>
          <div
            v-if="loading"
            style="text-align:center;padding:12px;">
            <el-icon class="is-loading"><Refresh /></el-icon>
          </div>
          <template v-else>
            <div class="coverage-stats">
              <div class="coverage-item">
                <span class="coverage-label">整体覆盖率</span>
                <span
                  class="coverage-value"
                  :class="coverageLevel(coverageData.overall)">{{ coverageData.overall }}%</span>
              </div>
              <el-progress
                :percentage="coverageData.overall"
                :status="coverageData.overall >= 60 ? 'success' : coverageData.overall >= 30 ? 'warning' : 'exception'" />
            </div>
            <div class="coverage-item">
              <span class="coverage-label">核心功能</span>
              <span class="coverage-value success">{{ coverageData.core }}%</span>
            </div>
            <div class="coverage-item">
              <span class="coverage-label">边缘场景</span>
              <span
                class="coverage-value"
                :class="coverageData.edge >= 50 ? 'success' : 'danger'">{{ coverageData.edge }}%</span>
            </div>
            <el-button
              type="primary"
              size="small"
              style="width: 100%; margin-top: 16px;"
              @click="generateAllTests">
              生成补充测试用例
            </el-button>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Grid, Refresh } from '@element-plus/icons-vue'
import type { Node as FlowNode, Edge as FlowEdge } from '@vue-flow/core'
import GraphViewer from '@/components/graph/GraphViewerOptimized.vue'
import { graphApi, testApi, factApi } from '@/api'
import { loadScanVersions } from '@/utils/versionsCache'
import { dictLabel } from '@/utils/dict'

interface FlowNodeData {
  label: string
  type: string
  confidence: number
  status: string
  evidenceCount: number
  page?: string
  api?: string
  properties?: Record<string, any>
}

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = ref<string>('')
const versions = ref<any[]>([])

const selectedModule = ref<string>('__all__')
const selectedNode = ref<any | null>(null)
const loading = ref(false)
const allFlowNodes = ref<FlowNode<FlowNodeData>[]>([])
const allFlowEdges = ref<FlowEdge[]>([])

const flowNodes = computed(() => {
  if (selectedModule.value === '__all__') return allFlowNodes.value
  return allFlowNodes.value.filter(node => {
    const props = node.data?.properties || {}
    return props.module === selectedModule.value || node.data?.type === selectedModule.value
  })
})

const flowEdges = computed(() => {
  const nodeIds = new Set(flowNodes.value.map(node => node.id))
  return allFlowEdges.value.filter(edge => nodeIds.has(edge.source) && nodeIds.has(edge.target))
})

const modules = ref<any[]>([])

const coverageData = ref<{
  overall: number
  core: number
  edge: number
}>({ overall: 0, core: 0, edge: 0 })

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

async function loadGraph(module = '') {
  if (!projectId.value || !currentVersion.value) {
    ElMessage.warning('缺少项目ID或版本ID')
    return
  }
  loading.value = true
  try {
    const data = await graphApi.getFeatureView(projectId.value, currentVersion.value, module)
    const nodes = Array.isArray(data?.nodes) ? data.nodes : []
    const edges = Array.isArray(data?.edges) ? data.edges : []

    selectedNode.value = null
    allFlowNodes.value = nodes.map(toFlowNode)
    allFlowEdges.value = edges.map(toFlowEdge).filter((edge: FlowEdge) => edge.source && edge.target)
    rebuildModules()

    // 首次加载时一并计算覆盖率（module 为空表示全量加载）
    if (!module) {
      computeCoverage(nodes)
    }

    if (nodes.length === 0) {
      ElMessage.info(module ? '该功能模块暂无图谱数据' : '当前版本暂无功能图谱数据')
    } else {
      ElMessage.success(`加载完成: ${nodes.length} 节点`)
    }
  } catch (error) {
    console.error('加载功能图谱失败', error)
    allFlowNodes.value = []
    allFlowEdges.value = []
    modules.value = []
    ElMessage.error('加载功能图谱失败')
  } finally {
    loading.value = false
  }
}

function rebuildModules() {
  const colors = ['#67c23a', '#409eff', '#e6a23c', '#f56c6c', '#909399', '#722ed1', '#13c2c2']
  const moduleMap = new Map<string, number>()
  allFlowNodes.value.forEach(node => {
    const props = node.data?.properties || {}
    const moduleName = props.module || node.data?.type || '未分组'
    moduleMap.set(moduleName, (moduleMap.get(moduleName) || 0) + 1)
  })

  modules.value = [
    {
      id: '__all__',
      name: '全部功能图谱',
      color: '#409eff',
      featureCount: allFlowNodes.value.length
    },
    ...Array.from(moduleMap.entries()).map(([name, count], idx) => ({
      id: name,
      name,
      color: colors[idx % colors.length],
      featureCount: count,
    }))
  ]
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
      page: props.page || props.pagePath,
      api: props.api || props.apiPath || (type === 'ApiEndpoint' ? props.nodeKey : undefined),
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
    label: edge.label || dictLabel('graph_edge_type', edge.type || '') || props.type || '',
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

function computeCoverage(rawNodes: any[]) {
  const nodes = rawNodes.map((n: any) => {
    const props = n.properties || n
    return {
      confidence: Number(props.confidence ?? 0.5),
      status: props.status || 'PENDING_CONFIRM',
    }
  })
  const total = nodes.length
  if (total === 0) {
    coverageData.value = { overall: 0, core: 0, edge: 0 }
    return
  }
  const confirmed = nodes.filter((n: any) =>
    n.status === 'CONFIRMED' || n.status === 'approved' || n.confidence >= 0.7
  ).length
  const highConfidence = nodes.filter((n: any) =>
    n.confidence >= 0.85
  ).length
  const lowConfidence = nodes.filter((n: any) =>
    n.confidence < 0.5 || n.status === 'PENDING'
  ).length

  coverageData.value = {
    overall: Math.round((confirmed / total) * 100),
    core: Math.round((highConfidence / total) * 100),
    edge: Math.round(((total - lowConfidence) / total) * 100),
  }
}

const getSelectedModuleName = () => {
  const module = modules.value.find(m => m.id === selectedModule.value)
  return module ? module.name : '全部'
}

const selectModule = (id: string) => {
  selectedModule.value = selectedModule.value === id ? '__all__' : id
  selectedNode.value = null
}

const coverageLevel = (val: number): string => {
  if (val >= 60) return 'success'
  if (val >= 30) return ''
  return 'danger'
}

const evidenceDrawerVisible = ref(false)
const nodeEvidence = ref<any[]>([])

const generateTests = async () => {
  if (!selectedNode.value) return
  const label = selectedNode.value.label || selectedNode.value.id
  try {
    // 调用后端 AI 生成测试用例
    await testApi.generate(projectId.value, {
      versionId: currentVersion.value || projectId.value,
      scope: { nodeTypes: ['ApiEndpoint', 'Feature'], priority: ['high'] }
    })
    ElMessage.success(`已为「${label}」提交测试用例生成任务`)
    router.push(`/projects/${projectId.value}/test-cases`)
  } catch (e) {
    console.error('生成测试用例失败', e)
    ElMessage.error('生成测试用例请求失败')
  }
}

const generateAllTests = async () => {
  try {
    await testApi.generate(projectId.value, {
      versionId: currentVersion.value || projectId.value,
      scope: { nodeTypes: ['ApiEndpoint', 'Feature'], priority: ['high'] }
    })
    ElMessage.success('已提交批量测试用例生成任务')
    router.push(`/projects/${projectId.value}/test-cases`)
  } catch (e) {
    console.error('批量生成测试用例失败', e)
    ElMessage.error('批量生成请求失败')
  }
}

const viewEvidence = async () => {
  if (!selectedNode.value) return
  const nodeName = selectedNode.value.label || selectedNode.value.id
  try {
    const result: any = await factApi.searchEvidence(projectId.value, {
      pageNum: 1,
      pageSize: 20,
      keyword: nodeName
    })
    nodeEvidence.value = result?.list || result || []
    evidenceDrawerVisible.value = true
  } catch (e) {
    console.error('加载证据失败', e)
    ElMessage.warning('证据加载失败')
  }
}

const exportReport = () => {
  // 跳转到报告导出页面
  router.push(`/projects/${projectId.value}/reports`)
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
      const moduleQuery = route.query.module as string
      await loadGraph(moduleQuery || '')
    }
  } catch (error) {
    console.error('onMounted error:', error)
    ElMessage.error('页面初始化失败')
  }
})
</script>

<style scoped>
.feature-graph {
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

.module-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.module-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid transparent;
}

.module-item:hover {
  background: #f5f7fa;
}

.module-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.module-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.module-info {
  flex: 1;
}

.module-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.module-stats {
  font-size: 12px;
  color: #909399;
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
  max-height: 350px;
  overflow-y: auto;
}

.action-buttons {
  display: flex;
  gap: 8px;
}

.coverage-stats {
  margin-bottom: 20px;
}

.coverage-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.coverage-label {
  font-size: 13px;
  color: #606266;
}

.coverage-value {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.coverage-value.success {
  color: #67c23a;
}

.coverage-value.danger {
  color: #f56c6c;
}
</style>
