<template>
  <div class="unified-graph">
    <div class="page-header">
      <div class="header-left">
        <h3>
          <el-icon><Connection /></el-icon>
          统一知识图谱
        </h3>
        <p class="header-desc">可视化展示代码结构、业务关系和数据流向</p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="currentVersion"
          placeholder="选择版本"
          size="small"
          style="width: 200px;"
          @change="loadVersion">
          <el-option
            v-for="item in versions"
            :key="item.id"
            :label="item.createdAt + ' - ' + item.nodeCount + '节点 ' + item.edgeCount + '关系'"
            :value="item.id"
          />
        </el-select>
        <el-button
          type="primary"
          size="small"
          :loading="loading"
          @click="refreshGraph">
          <el-icon><Refresh /></el-icon>
          刷新图谱
        </el-button>
      </div>
    </div>

    <div class="top-stat-bar">
      <div class="stat-item">
        <div class="stat-meta">
          <el-icon><DataLine /></el-icon>
          <span>显示节点</span>
        </div>
        <div class="stat-value primary">{{ filteredNodes.length }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-meta">
          <el-icon><Connection /></el-icon>
          <span>显示关系</span>
        </div>
        <div class="stat-value success">{{ filteredEdges.length }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-meta">
          <el-icon><CircleCheck /></el-icon>
          <span>待审核</span>
        </div>
        <div class="stat-value warning">{{ pendingCount }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-meta">
          <el-icon><Odometer /></el-icon>
          <span>平均置信度</span>
        </div>
        <div class="stat-value info">{{ averageConfidence }}%</div>
      </div>
    </div>

    <el-row
      :gutter="16"
      class="graph-layout">
      <!-- 主图谱区域 -->
      <el-col :span="18">
        <el-card
          class="graph-card"
          shadow="hover"
          :body-style="{ padding: 0 }">
          <template #header>
            <div class="graph-header">
              <span>图谱视图</span>
            </div>
          </template>

          <GraphViewerOptimized
            :nodes="filteredNodesForTemplate"
            :edges="filteredEdgesForTemplate"
            height="calc(100vh - 255px)"
            :aggregation-threshold="500"
            :worker-enabled="true"
            @node-click="handleNodeClick"
            @edge-click="handleEdgeClick"
          />
        </el-card>
      </el-col>

      <!-- 右侧控制栏 -->
      <el-col
        :span="6"
        class="side-panel">
        <el-card
          class="filter-card"
          shadow="hover">
          <template #header>
            <div class="card-header">
              <span>
                <el-icon><Filter /></el-icon>
                节点筛选
              </span>
              <el-button
                type="text"
                size="small"
                @click="resetFilters">
                重置
              </el-button>
            </div>
          </template>

          <div class="filter-section">
            <div class="filter-title">
              <el-icon><Grid /></el-icon>
              节点类型
            </div>
            <el-checkbox-group v-model="selectedNodeTypes">
              <div
                v-for="type in nodeTypes"
                :key="type.value"
                class="filter-item">
                <el-checkbox :value="type.value">
                  <span
                    class="color-dot"
                    :style="{ backgroundColor: type.color }" />
                  <span>{{ type.label }}</span>
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>

          <el-divider />

          <div class="filter-section">
            <div class="filter-title">
              <el-icon><Odometer /></el-icon>
              置信度
            </div>
            <el-slider
              v-model="minConfidence"
              :min="0"
              :max="1"
              :step="0.05"
              :format-tooltip="(v: number) => (v * 100).toFixed(0) + '%'"
            />
            <div class="filter-range">
              <span>最低: {{ (minConfidence * 100).toFixed(0) }}%</span>
            </div>
          </div>

          <el-divider />

          <div class="filter-section">
            <div class="filter-title">
              <el-icon><CircleCheck /></el-icon>
              审核状态
            </div>
            <el-checkbox-group v-model="selectedReviewStatus">
              <div class="filter-item">
                <el-checkbox value="approved">
                  <span class="status-dot success" />
                  <span>已通过</span>
                </el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox value="pending">
                  <span class="status-dot warning" />
                  <span>待审核</span>
                </el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox value="rejected">
                  <span class="status-dot danger" />
                  <span>已拒绝</span>
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </el-card>

        <el-card
          v-if="selectedNode"
          class="detail-card"
          shadow="hover">
          <template #header>
            <div class="card-header">
              <span>
                <el-icon><View /></el-icon>
                节点详情
              </span>
              <el-button
                type="text"
                size="small"
                @click="selectedNode = null">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
          </template>

          <div class="node-detail-header">
            <div
              class="node-icon"
              :style="{ backgroundColor: getNodeColor(selectedNode.data?.confidence) + '20', color: getNodeColor(selectedNode.data?.confidence) }">
              <el-icon :size="28">
                <component :is="getNodeIcon(selectedNode.data?.type)" />
              </el-icon>
            </div>
            <div class="node-info">
              <h4>{{ selectedNode.data?.label }}</h4>
              <el-tag
                size="small"
                :type="getTagType(selectedNode.data?.type)">
                {{ getTypeLabel(selectedNode.data?.type) }}
              </el-tag>
            </div>
          </div>

          <el-descriptions
            :column="1"
            border
            size="small"
            class="detail-desc">
            <el-descriptions-item label="置信度">
              <el-progress
                :percentage="(selectedNode.data?.confidence || 0) * 100"
                :color="getNodeColor(selectedNode.data?.confidence)"
                :stroke-width="8"
              />
            </el-descriptions-item>
            <el-descriptions-item label="审核状态">
              <el-tag
                size="small"
                :type="getStatusType(selectedNode.data?.status)">
                {{ getStatusLabel(selectedNode.data?.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="证据数量">
              {{ selectedNode.data?.evidenceCount || 0 }} 条
            </el-descriptions-item>
          </el-descriptions>

          <div class="action-section">
            <h4>快捷操作</h4>
            <el-space wrap>
              <el-button
                type="success"
                size="small"
                @click="approveNode">
                <el-icon><CircleCheck /></el-icon>
                确认正确
              </el-button>
              <el-button
                type="danger"
                size="small"
                @click="rejectNode">
                <el-icon><CircleClose /></el-icon>
                标记错误
              </el-button>
              <el-button
                type="primary"
                size="small"
                @click="viewEvidence">
                <el-icon><Document /></el-icon>
                查看证据
              </el-button>
              <el-button
                size="small"
                @click="locateNode">
                <el-icon><Location /></el-icon>
                定位节点
              </el-button>
            </el-space>
          </div>

          <div class="related-section">
            <h4>关联关系</h4>
            <el-table
              :data="nodeEdges"
              size="small"
              border
              style="width: 100%">
              <el-table-column
                prop="label"
                label="关系"
                width="80" />
              <el-table-column
                prop="targetName"
                label="目标节点"
                show-overflow-tooltip />
            </el-table>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 证据详情抽屉 -->
    <el-drawer
      v-model="evidenceDrawerVisible"
      title="证据详情"
      size="40%"
      direction="rtl"
    >
      <div class="evidence-content">
        <p>节点: {{ selectedNode?.data?.label }}</p>
        <el-alert
          title="共找到 {{ nodeEvidence.length }} 条相关证据"
          type="info"
          style="margin-bottom: 16px;" />
        <EvidencePanel :evidence="nodeEvidence" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
// ⚠️ TODO F-H7: 本组件 915 行，混入图谱加载/过滤/状态管理/节点定位。
// 建议提取 composable useUnifiedGraph + 子组件 GraphFilters / NodeLocator
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Connection,
  Refresh,
  Filter,
  Grid,
  Odometer,
  CircleCheck,
  DataLine,
  View,
  Close,
  Document,
  Location,
  CircleClose,
  Files,
  Operation,
  Coin,
  Platform,
  Menu,
  Link,
  QuestionFilled
} from '@element-plus/icons-vue'
import { graphApi, reviewApi, factApi } from '@/api'
import { loadScanVersions, type ScanVersion } from '@/utils/versionsCache'
import { dictLabel } from '@/utils/dict'
import GraphViewerOptimized from '@/components/graph/GraphViewerOptimized.vue'
import EvidencePanel from '@/components/EvidencePanel.vue'
import type { Node, Edge } from '@vue-flow/core'
import type { Evidence } from '@/types'

const route = useRoute()

const projectId = computed(() => route.params.projectId as string)

// 从 URL query 参数读取初始值（必须在 ref 初始化之前）
const urlVersionId = (route.query.versionId as string) || ''
const urlMinConfidence = route.query.minConfidence ? Number(route.query.minConfidence) : undefined

const loading = ref(false)
const defaultVisibleStatusGroups = ['approved', 'pending']

const currentVersion = ref<string>('')
const minConfidence = ref(urlMinConfidence ?? 0.5)
const selectedNodeTypes = ref<string[]>([])
const selectedReviewStatus = ref<string[]>([...defaultVisibleStatusGroups])
const selectedNode = ref<Node | null>(null)
const evidenceDrawerVisible = ref(false)
const nodeEvidence = ref<Evidence[]>([])
const versions = ref<ScanVersion[]>([])

const nodeTypes = [
  { value: 'BusinessDomain', label: '业务域', color: '#67c23a' },
  { value: 'BusinessProcess', label: '业务流程', color: '#85ce61' },
  { value: 'FeatureModule', label: '功能模块', color: '#409eff' },
  { value: 'Feature', label: '功能点', color: '#66b1ff' },
  { value: 'ApiEndpoint', label: 'API接口', color: '#e6a23c' },
  { value: 'Controller', label: 'Controller', color: '#ebb563' },
  { value: 'Service', label: 'Service', color: '#f5d76e' },
  { value: 'Mapper', label: 'Mapper', color: '#909399' },
  { value: 'SqlStatement', label: 'SQL语句', color: '#f56c6c' },
  { value: 'Table', label: '数据库表', color: '#f78989' }
]

interface GraphNodeData {
  id: string
  key: string
  label: string
  type: string
  confidence: number
  status: string
  description?: string
  sourcePath?: string
  evidenceCount?: number
  [key: string]: unknown
}

interface GraphEdgeData {
  id: string
  source: string
  target: string
  type: string
  label?: string
  confidence: number
}

interface InternalNode {
  id: string
  type?: string
  position: { x: number; y: number }
  data: GraphNodeData
}

interface InternalEdge {
  id: string
  source: string
  target: string
  label?: string
  data: GraphEdgeData
}

// 内部使用简单类型存储，避免 VueFlow 深层递归
const internalNodes = ref<InternalNode[]>([])
const internalEdges = ref<InternalEdge[]>([])

// 转换为 VueFlow 类型供模板使用
const filteredNodesForTemplate = computed((): Node[] => filteredNodes.value as unknown as Node[])
const filteredEdgesForTemplate = computed((): Edge[] => filteredEdges.value as unknown as Edge[])

const filteredNodes = computed((): InternalNode[] => {
  return internalNodes.value.filter((node) => {
    const data = node.data
    const confidence = data.confidence || 0
    const status = normalizeStatusGroup(data.status)
    const type = data.type

    if (confidence < minConfidence.value) return false
    // 状态过滤：未设置状态(null/undefined)的节点默认可见
    if (status && selectedReviewStatus.value.length > 0 && !selectedReviewStatus.value.includes(status)) return false
    if (selectedNodeTypes.value.length > 0 && !selectedNodeTypes.value.includes(type)) return false

    return true
  })
})

const filteredEdges = computed((): InternalEdge[] => {
  const nodeIds = new Set(filteredNodes.value.map((n) => n.id))
  return internalEdges.value.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
})

const pendingCount = computed(() => {
  return filteredNodes.value.filter(n => normalizeStatusGroup(n.data?.status) === 'pending').length
})

const averageConfidence = computed(() => {
  if (filteredNodes.value.length === 0) return 0
  const total = filteredNodes.value.reduce((sum, n) => sum + (n.data?.confidence || 0), 0)
  return ((total / filteredNodes.value.length) * 100).toFixed(1)
})

const nodeEdges = computed((): Array<InternalEdge & { targetName: string }> => {
  if (!selectedNode.value) return []
  const selectedId = selectedNode.value.id
  return filteredEdges.value
    .filter(e => e.source === selectedId || e.target === selectedId)
    .map(e => ({
      ...e,
      targetName: internalNodes.value.find(n => n.id === (e.source === selectedId ? e.target : e.source))?.data.label || '未知'
    }))
})

function getNodeColor(confidence: number = 0.8): string {
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

function getNodeIcon(type?: string): any {
  const iconMap: Record<string, any> = {
    BusinessDomain: Menu,
    BusinessProcess: Operation,
    FeatureModule: Files,
    Feature: Link,
    ApiEndpoint: Operation,
    Controller: Platform,
    Service: Platform,
    Mapper: Platform,
    SqlStatement: Coin,
    Table: Coin,
    Column: Document
  }
  return iconMap[type || ''] || QuestionFilled
}

function getTagType(type?: string): string {
  const typeMap: Record<string, string> = {
    BusinessDomain: 'success',
    BusinessProcess: 'warning',
    FeatureModule: 'primary',
    Feature: 'info',
    ApiEndpoint: 'primary',
    Controller: 'success',
    Service: 'warning',
    Mapper: 'info',
    SqlStatement: 'danger',
    Table: 'danger',
    Column: 'info'
  }
  return typeMap[type || ''] || 'info'
}

function getTypeLabel(type?: string): string {
  return dictLabel('graph_node_type', type || '')
}

function getStatusType(status?: string): string {
  const group = normalizeStatusGroup(status)
  const statusMap: Record<string, string> = {
    approved: 'success',
    pending: 'warning',
    rejected: 'danger'
  }
  return statusMap[group] || 'info'
}

function getStatusLabel(status?: string): string {
  return dictLabel('review_status', status || '')
}

function normalizeStatusGroup(status?: string): string {
  const value = String(status || '').toUpperCase()
  if (value === 'CONFIRMED' || value === 'APPROVED') return 'approved'
  if (value === 'PENDING_CONFIRM' || value === 'PENDING') return 'pending'
  if (value === 'REJECTED') return 'rejected'
  return status || ''
}

function resetFilters() {
  selectedNodeTypes.value = []
  minConfidence.value = 0.5
  selectedReviewStatus.value = [...defaultVisibleStatusGroups]
  ElMessage.success('筛选条件已重置')
}

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

async function loadGraph(versionId: string) {
  if (!projectId.value || !versionId) return
  loading.value = true
  try {
    const data: any = await graphApi.getUnifiedGraph(projectId.value, versionId, minConfidence.value)

    // 转换节点格式为 VueFlow 格式
    internalNodes.value = (data.nodes || []).map((node: GraphNodeData) => ({
      id: node.id,
      position: {
        x: Math.random() * 800,
        y: Math.random() * 600
      },
      data: {
        ...node,
        label: node.label
      }
    }))

    // 转换边格式为 VueFlow 格式
    internalEdges.value = (data.edges || []).map((edge: GraphEdgeData) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label || edge.type,
      data: {
        ...edge
      }
    }))

    ElMessage.success(`已加载 ${data.nodeCount || 0} 节点, ${data.edgeCount || 0} 关系`)
  } catch (error) {
    console.error('加载图谱失败', error)
    ElMessage.error('加载图谱失败')
    internalNodes.value = []
    internalEdges.value = []
  } finally {
    loading.value = false
  }
}

function loadVersion(versionId: string) {
  currentVersion.value = versionId
  loadGraph(versionId)
}

async function refreshGraph() {
  try {
    if (currentVersion.value) {
      await loadGraph(currentVersion.value)
    } else {
      ElMessage.warning('请先选择一个扫描版本')
    }
  } catch (error) {
    console.error('refreshGraph error:', error)
    ElMessage.error('刷新图谱失败')
  }
}

function handleNodeClick(node: Node) {
  selectedNode.value = node
}

function handleEdgeClick(_edge: Edge) {
  // emit 到父组件处理
}

async function approveNode() {
  if (!selectedNode.value?.data?.id) {
    ElMessage.warning('无法确认: 节点信息不完整')
    return
  }
  try {
    await reviewApi.confirmReview(projectId.value, {
      targetId: selectedNode.value.data.id,
      targetType: selectedNode.value.data.type,
      comment: '人工确认正确'
    })
    selectedNode.value.data.status = 'CONFIRMED'
    ElMessage.success('节点已确认')
  } catch (error) {
    console.error('确认失败', error)
    ElMessage.error('确认失败: ' + (error as Error).message)
  }
}

async function rejectNode() {
  if (!selectedNode.value?.data?.id) {
    ElMessage.warning('无法拒绝: 节点信息不完整')
    return
  }
  try {
    await reviewApi.rejectReview(projectId.value, {
      targetId: selectedNode.value.data.id,
      targetType: selectedNode.value.data.type,
      comment: '人工标记错误'
    })
    selectedNode.value.data.status = 'REJECTED'
    ElMessage.success('节点已标记错误')
  } catch (error) {
    console.error('标记失败', error)
    ElMessage.error('标记失败: ' + (error as Error).message)
  }
}

async function viewEvidence() {
  evidenceDrawerVisible.value = true
  // 加载选中节点的证据数据
  if (!projectId.value || !selectedNode.value?.data?.id) return
  const node = selectedNode.value!
  try {
    // 调用后端证据检索接口获取真实证据
    const searchResult: any = await factApi.searchEvidence(projectId.value, {
      pageNum: 1,
      pageSize: 20,
      evidenceType: undefined,
      keyword: node.data.label || undefined
    })
    if (searchResult && searchResult.list && searchResult.list.length > 0) {
      nodeEvidence.value = searchResult.list.map((e: any) => ({
        id: e.id,
        evidenceType: e.evidenceType || 'CODE',
        sourceName: e.sourceName || node.data.label,
        sourcePath: e.sourcePath || e.location,
        summary: e.summary || e.content,
        content: e.content,
        location: e.location,
        createdAt: e.createdAt,
      }))
    } else {
      // 回退：从节点属性中构造简单证据
      const nodeData = node.data
      if (nodeData.sourcePath || nodeData.description) {
        nodeEvidence.value = [{
          id: nodeData.id + '-evidence',
          evidenceType: 'FILE_LINE',
          sourceName: nodeData.label || '节点证据',
          sourcePath: nodeData.sourcePath,
          summary: nodeData.description || `该节点的证据源，来自 ${nodeData.sourcePath || '代码分析'}`,
          createdAt: new Date().toISOString(),
        }]
      } else {
        nodeEvidence.value = []
      }
    }
  } catch (error) {
    console.error('加载证据失败', error)
    // 出错时也不使用硬编码示例
    nodeEvidence.value = []
  }
}

function locateNode() {
  if (selectedNode.value) {
    // 触发图谱聚焦到当前选中节点
    emitFocusNode(selectedNode.value.id)
  }
}

function emitFocusNode(nodeId: string) {
  // 通过自定义事件通知图谱容器聚焦到指定节点
  const event = new CustomEvent('graph-focus-node', { detail: { nodeId } })
  window.dispatchEvent(event)
}

onMounted(async () => {
  try {
    await loadVersions()
    // 优先使用 URL 参数指定的版本，否则自动选择第一个版本
    if (urlVersionId && versions.value.some(v => v.id === urlVersionId)) {
      currentVersion.value = urlVersionId
      await loadGraph(urlVersionId)
    } else if (versions.value.length > 0) {
      currentVersion.value = versions.value[0].id
      await loadGraph(currentVersion.value)
    }
  } catch (error) {
    console.error('onMounted error:', error)
    ElMessage.error('页面初始化失败')
  }
})
</script>

<style scoped>
.unified-graph {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.header-left h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.header-desc {
  margin: 0;
  font-size: 14px;
  color: #909399;
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.top-stat-bar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.top-stat-bar .stat-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 58px;
  padding: 10px 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
}

.stat-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.graph-layout {
  align-items: stretch;
}

.filter-card,
.detail-card {
  margin-bottom: 16px;
}

.filter-card :deep(.el-card__body),
.detail-card :deep(.el-card__body) {
  padding: 14px;
}

.filter-card :deep(.el-divider--horizontal) {
  margin: 14px 0;
}

.side-panel {
  max-height: calc(100vh - 255px);
  overflow-y: auto;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header span {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.filter-section {
  margin-bottom: 8px;
}

.filter-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.filter-item {
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}

.color-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 8px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 8px;
}

.status-dot.success {
  background-color: #67c23a;
}

.status-dot.warning {
  background-color: #e6a23c;
}

.status-dot.danger {
  background-color: #f56c6c;
}

.filter-range {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  text-align: right;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1;
}

.stat-value.primary {
  color: #409eff;
}

.stat-value.success {
  color: #67c23a;
}

.stat-value.warning {
  color: #e6a23c;
}

.stat-value.info {
  color: #909399;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.graph-card {
  height: 100%;
}

.graph-card :deep(.el-card__header) {
  padding: 10px 16px;
}

.node-detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.node-icon {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.node-info h4 {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.detail-desc {
  margin-bottom: 20px;
}

.action-section,
.related-section {
  margin-top: 20px;
}

.action-section h4,
.related-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.evidence-content {
  padding: 10px;
}

.graph-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.graph-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.graph-actions .el-tooltip {
  display: flex;
  align-items: center;
}
</style>
