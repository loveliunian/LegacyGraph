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
        <el-select v-model="currentVersion" placeholder="选择版本" size="small" style="width: 200px;" @change="loadVersion">
            <el-option
              v-for="item in versions" :key="(item as any)?.id"
              :label="((item as any)?.createdAt || '') + ' - ' + ((item as any)?.nodeCount || 0) + '节点 ' + ((item as any)?.edgeCount || 0) + '关系'"
              :value="(item as any)?.id"
          />
        </el-select>
        <el-button type="primary" size="small" @click="refreshGraph" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新图谱
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 左侧过滤器 -->
      <el-col :span="3">
        <el-card class="filter-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>
                <el-icon><Filter /></el-icon>
                节点筛选
              </span>
              <el-button type="text" size="small" @click="resetFilters">重置</el-button>
            </div>
          </template>

          <div class="filter-section">
            <div class="filter-title">
              <el-icon><Grid /></el-icon>
              节点类型
            </div>
            <el-checkbox-group v-model="selectedNodeTypes">
              <div class="filter-item" v-for="type in nodeTypes" :key="type.value">
                <el-checkbox :value="type.value">
                  <span class="color-dot" :style="{ backgroundColor: type.color }"></span>
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
                  <span class="status-dot success"></span>
                  <span>已通过</span>
                </el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox value="pending">
                  <span class="status-dot warning"></span>
                  <span>待审核</span>
                </el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox value="rejected">
                  <span class="status-dot danger"></span>
                  <span>已拒绝</span>
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </el-card>

        <el-card class="stat-card" shadow="hover">
          <template #header>
            <span>
              <el-icon><DataLine /></el-icon>
              图谱统计
            </span>
          </template>
          <div class="stat-grid">
            <div class="stat-item">
              <div class="stat-value primary">{{ filteredNodes.length }}</div>
              <div class="stat-label">显示节点</div>
            </div>
            <div class="stat-item">
              <div class="stat-value success">{{ filteredEdges.length }}</div>
              <div class="stat-label">显示关系</div>
            </div>
            <div class="stat-item">
              <div class="stat-value warning">{{ pendingCount }}</div>
              <div class="stat-label">待审核</div>
            </div>
            <div class="stat-item">
              <div class="stat-value info">{{ averageConfidence }}%</div>
              <div class="stat-label">平均置信度</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 中间图谱区域 -->
      <el-col :span="15">
        <el-card class="graph-card" shadow="hover" :body-style="{ padding: 0 }">
          <template #header>
            <div class="graph-header">
              <span>图谱视图</span>
              <div class="graph-actions">
                <el-switch
                  v-model="useOptimizedViewer"
                  active-text="高性能模式"
                  inactive-text="标准模式"
                  size="small"
                />
                <el-tooltip content="大数据量时建议使用高性能模式">
                  <el-icon><InfoFilled /></el-icon>
                </el-tooltip>
              </div>
            </div>
          </template>
          
          <component
            :is="currentViewer"
            :nodes="filteredNodes"
            :edges="filteredEdges"
            height="660px"
            :aggregation-threshold="500"
            :worker-enabled="true"
            @node-click="handleNodeClick"
            @edge-click="handleEdgeClick"
          />
        </el-card>
      </el-col>

      <!-- 右侧详情面板 -->
      <el-col :span="6">
        <el-card class="detail-card" shadow="hover" v-if="selectedNode">
          <template #header>
            <div class="card-header">
              <span>
                <el-icon><View /></el-icon>
                节点详情
              </span>
              <el-button type="text" size="small" @click="selectedNode = null">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
          </template>

          <div class="node-detail-header">
            <div class="node-icon" :style="{ backgroundColor: getNodeColor(selectedNode.data?.confidence) + '20', color: getNodeColor(selectedNode.data?.confidence) }">
              <el-icon :size="28">
                <component :is="getNodeIcon(selectedNode.data?.type)" />
              </el-icon>
            </div>
            <div class="node-info">
              <h4>{{ selectedNode.data?.label }}</h4>
              <el-tag size="small" :type="getTagType(selectedNode.data?.type)">
                {{ getTypeLabel(selectedNode.data?.type) }}
              </el-tag>
            </div>
          </div>

          <el-descriptions :column="1" border size="small" class="detail-desc">
            <el-descriptions-item label="置信度">
              <el-progress
                :percentage="(selectedNode.data?.confidence || 0) * 100"
                :color="getNodeColor(selectedNode.data?.confidence)"
                :stroke-width="8"
              />
            </el-descriptions-item>
            <el-descriptions-item label="审核状态">
              <el-tag size="small" :type="getStatusType(selectedNode.data?.status)">
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
              <el-button type="success" size="small" @click="approveNode">
                <el-icon><CircleCheck /></el-icon>
                确认正确
              </el-button>
              <el-button type="danger" size="small" @click="rejectNode">
                <el-icon><CircleClose /></el-icon>
                标记错误
              </el-button>
              <el-button type="primary" size="small" @click="viewEvidence">
                <el-icon><Document /></el-icon>
                查看证据
              </el-button>
              <el-button size="small" @click="locateNode">
                <el-icon><Location /></el-icon>
                定位节点
              </el-button>
            </el-space>
          </div>

          <div class="related-section">
            <h4>关联关系</h4>
            <el-table :data="nodeEdges" size="small" border style="width: 100%">
              <el-table-column prop="label" label="关系" width="80" />
              <el-table-column prop="targetName" label="目标节点" show-overflow-tooltip />
            </el-table>
          </div>
        </el-card>

        <el-card class="help-card" shadow="hover" v-else>
          <template #header>
            <span>
              <el-icon><InfoFilled /></el-icon>
              操作说明
            </span>
          </template>
          <div class="help-list">
            <div class="help-item">
              <el-icon color="#409eff"><Pointer /></el-icon>
              <span>点击节点查看详情</span>
            </div>
            <div class="help-item">
              <el-icon color="#67c23a"><ZoomIn /></el-icon>
              <span>滚轮缩放图谱</span>
            </div>
            <div class="help-item">
              <el-icon color="#e6a23c"><Aim /></el-icon>
              <span>拖拽移动节点位置</span>
            </div>
            <div class="help-item">
              <el-icon color="#f56c6c"><Rank /></el-icon>
              <span>拖拽空白平移视图</span>
            </div>
          </div>

          <div class="legend-section">
            <h4>图例说明</h4>
            <div class="legend-list">
              <div class="legend-item" v-for="type in nodeTypes" :key="type.value">
                <span class="legend-color" :style="{ backgroundColor: type.color }"></span>
                <span class="legend-label">{{ type.label }}</span>
              </div>
            </div>
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
        <el-alert title="共找到 {{ nodeEvidence.length }} 条相关证据" type="info" style="margin-bottom: 16px;" />
        <EvidencePanel :evidence="nodeEvidence" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
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
  InfoFilled,
  Pointer,
  ZoomIn,
  Aim,
  Rank,
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
import { projectApi } from '@/api'
import { get } from '@/utils/request'
import GraphViewer from '@/components/graph/GraphViewer.vue'
import GraphViewerOptimized from '@/components/graph/GraphViewerOptimized.vue'
import EvidencePanel from '@/components/EvidencePanel.vue'
import type { Node, Edge } from '@vue-flow/core'
import type { Evidence } from '@/types'

const route = useRoute()
const loading = ref(false)
const useOptimizedViewer = ref(false)

const currentViewer = computed(() => {
  return useOptimizedViewer.value ? GraphViewerOptimized : GraphViewer
})
const currentVersion = ref<string>('')
const minConfidence = ref(0.5)
const selectedNodeTypes = ref<string[]>([])
const selectedReviewStatus = ref<string[]>(['CONFIRMED', 'PENDING_CONFIRM', 'approved', 'pending'])
const selectedNode = ref<Node | null>(null)
const evidenceDrawerVisible = ref(false)
const nodeEvidence = ref<Evidence[]>([])

const projectId = computed(() => route.params.projectId as string)
const versions = ref<Record<string, any>[]>([])

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
}

interface GraphEdgeData {
  id: string
  source: string
  target: string
  type: string
  label?: string
  confidence: number
}

const allNodes = ref<Node[]>([])
const allEdges = ref<Edge[]>([])

const filteredNodes = computed(() => {
  return (allNodes.value as any[]).filter((node: any) => {
    const confidence = node.data?.confidence || 0
    const status = node.data?.status
    const type = node.data?.type

    if (confidence < minConfidence.value) return false
    if (selectedReviewStatus.value.length > 0 && !selectedReviewStatus.value.includes(status)) return false
    if (selectedNodeTypes.value.length > 0 && !selectedNodeTypes.value.includes(type)) return false

    return true
  })
})

const filteredEdges = computed((): any[] => {
  const nodeIds = new Set(filteredNodes.value.map((n: any) => n.id))
  return (allEdges.value as any[]).filter((edge: any) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
})

const pendingCount = computed(() => {
  return filteredNodes.value.filter(n => n.data?.status === 'PENDING_CONFIRM' || n.data?.status === 'pending').length
})

const averageConfidence = computed(() => {
  if (filteredNodes.value.length === 0) return 0
  const total = filteredNodes.value.reduce((sum, n) => sum + (n.data?.confidence || 0), 0)
  return ((total / filteredNodes.value.length) * 100).toFixed(1)
})

const nodeEdges = computed(() => {
  if (!selectedNode.value) return []
  return filteredEdges.value
    .filter(e => e.source === selectedNode.value!.id || e.target === selectedNode.value!.id)
    .map(e => ({
      ...e,
      targetName: (allNodes.value as any[]).find(n => n.id === (e.source === selectedNode.value!.id ? e.target : e.source))?.data?.label || '未知'
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
  const labelMap: Record<string, string> = {
    BusinessDomain: '业务域',
    BusinessProcess: '业务流程',
    FeatureModule: '功能模块',
    Feature: '功能点',
    ApiEndpoint: 'API接口',
    Controller: 'Controller',
    Service: 'Service',
    Mapper: 'Mapper',
    SqlStatement: 'SQL语句',
    Table: '数据库表',
    Column: '字段'
  }
  return labelMap[type || ''] || type || '未知'
}

function getStatusType(status?: string): string {
  const statusMap: Record<string, string> = {
    CONFIRMED: 'success',
    approved: 'success',
    PENDING_CONFIRM: 'warning',
    pending: 'warning',
    REJECTED: 'danger',
    rejected: 'danger'
  }
  return statusMap[status || ''] || 'info'
}

function getStatusLabel(status?: string): string {
  const labelMap: Record<string, string> = {
    CONFIRMED: '已确认',
    approved: '已通过',
    PENDING_CONFIRM: '待审核',
    pending: '待审核',
    REJECTED: '已拒绝',
    rejected: '已拒绝'
  }
  return labelMap[status || ''] || '未知'
}

function resetFilters() {
  selectedNodeTypes.value = []
  minConfidence.value = 0.5
  selectedReviewStatus.value = ['CONFIRMED', 'PENDING_CONFIRM', 'approved', 'pending']
  ElMessage.success('筛选条件已重置')
}

async function loadVersions() {
  if (!projectId.value) return
  try {
    const data: any = await get(`/lg/projects/${projectId.value}/scan-versions`)
    versions.value = Array.isArray(data) ? data : (data.list || [])
  } catch (error) {
    console.error('加载版本列表失败', error)
    ElMessage.error('加载版本列表失败')
  }
}

async function loadGraph(versionId: string) {
  if (!projectId.value || !versionId) return
  loading.value = true
  try {
    const data: any = await graphApi.getUnifiedGraph(projectId.value, versionId, minConfidence.value)

    // 转换节点格式为 VueFlow 格式
    allNodes.value = (data.nodes || []).map((node: GraphNodeData) => ({
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
    allEdges.value = (data.edges || []).map((edge: GraphEdgeData) => ({
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
    allNodes.value = []
    allEdges.value = []
  } finally {
    loading.value = false
  }
}

function loadVersion(versionId: string) {
  currentVersion.value = versionId
  loadGraph(versionId)
}

async function refreshGraph() {
  if (currentVersion.value) {
    await loadGraph(currentVersion.value)
  } else {
    ElMessage.warning('请先选择一个扫描版本')
  }
}

function handleNodeClick(node: Node) {
  selectedNode.value = node
}

function handleEdgeClick(edge: Edge) {
  console.log('Edge clicked:', edge)
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
  await loadVersions()
  // 自动选择第一个版本并加载图谱
  if (versions.value.length > 0) {
    currentVersion.value = versions.value[0].id
    await loadGraph(currentVersion.value)
  }
})
</script>

<style scoped>
.unified-graph {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
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

.filter-card,
.stat-card,
.detail-card,
.help-card {
  margin-bottom: 16px;
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

.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.stat-item {
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 4px;
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

.help-list {
  margin-bottom: 20px;
}

.help-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  font-size: 13px;
  color: #606266;
}

.legend-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.legend-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.legend-color {
  width: 14px;
  height: 14px;
  border-radius: 3px;
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
