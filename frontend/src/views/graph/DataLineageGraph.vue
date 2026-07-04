<template>
  <div class="data-lineage-graph">
    <div class="page-header">
      <h3>数据血缘图谱</h3>
      <el-button
        type="primary"
        size="small"
        :loading="loading"
        @click="refreshGraph">
        <el-icon><Refresh /></el-icon>
        刷新图谱
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="5">
        <el-card class="table-card">
          <template #header>
            <span>数据表</span>
            <el-tag
              size="small"
              type="info">
              {{ tables.length }} 个
            </el-tag>
          </template>
          <el-input
            v-model="searchKeyword"
            placeholder="搜索表名"
            prefix-icon="Search"
            size="small"
            style="margin-bottom: 12px;"
          />
          <div class="table-list">
            <div
              v-if="tables.length === 0 && !loading"
              class="table-empty">
              <el-empty
                description="暂无数据表"
                :image-size="60" />
            </div>
            <div
              v-for="table in filteredTables"
              :key="table.id"
              class="table-item"
              :class="{ active: selectedTable === table.id }"
              @click="selectTable(table.id)"
            >
              <div
                class="table-icon"
                :class="table.type">
                <el-icon><Tickets /></el-icon>
              </div>
              <div class="table-info">
                <div class="table-name">{{ table.name }}</div>
                <div class="table-desc">
                  {{ table.columnCount }} 字段 · {{ table.relationCount }} 关联
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <el-button-group>
              <el-button
                size="small"
                :type="viewMode === 'lineage' ? 'primary' : ''"
                @click="viewMode = 'lineage'">
                完整血缘
              </el-button>
              <el-button
                size="small"
                :type="viewMode === 'downstream' ? 'primary' : ''"
                @click="viewMode = 'downstream'">
                下游影响
              </el-button>
              <el-button
                size="small"
                :type="viewMode === 'upstream' ? 'primary' : ''"
                @click="viewMode = 'upstream'">
                上游来源
              </el-button>
            </el-button-group>
            <div
              v-if="selectedTableName"
              class="graph-context">
              当前表: <b>{{ selectedTableName }}</b>
            </div>
          </div>
          <div
            ref="graphContainer"
            class="graph-container">
            <div
              v-if="loading"
              class="graph-loading">
              <el-icon
                :size="32"
                class="is-loading">
                <Refresh />
              </el-icon>
              <p>加载中...</p>
            </div>
            <div
              v-else-if="!lineageData.nodes.length && !selectedTable"
              class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon
                  :size="64"
                  color="#c0c4cc">
                  <Share />
                </el-icon>
                <p>数据血缘可视化</p>
                <p class="placeholder-tip">选择左侧数据表查看表之间的数据流转关系</p>
              </div>
            </div>
            <div
              v-else-if="!lineageData.nodes.length && selectedTable"
              class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon
                  :size="64"
                  color="#e6a23c">
                  <WarningFilled />
                </el-icon>
                <p>暂无血缘数据</p>
                <p class="placeholder-tip">该表暂未检测到上下游血缘关系</p>
              </div>
            </div>
            <VueFlow
              v-else
              :nodes="vueFlowNodes"
              :edges="vueFlowEdges"
              fit-view
              class="vue-flow-container"
            >
              <template #node-custom="nodeProps">
                <div
                  class="lineage-node"
                  :class="{
                    'table-node': nodeProps.data.type === 'Table' || nodeProps.data.type === 'table',
                    'api-node': nodeProps.data.type === 'ApiEndpoint' || nodeProps.data.type === 'api',
                    'service-node': nodeProps.data.type === 'Service' || nodeProps.data.type === 'service'
                  }"
                >
                  <div class="node-label">{{ nodeProps.data.label }}</div>
                  <div class="node-type">{{ nodeProps.data.type }}</div>
                </div>
              </template>
            </VueFlow>
          </div>
        </el-card>
      </el-col>

      <el-col :span="5">
        <el-card
          v-if="selectedTableName"
          class="detail-card">
          <template #header>
            <span>表结构详情</span>
          </template>
          <div class="table-detail">
            <h4>{{ selectedTableName }}</h4>
            <div class="detail-item">
              <span class="label">字段数</span>
              <span class="value">{{ tableDetail.columnCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">上游来源</span>
              <span class="value">{{ tableDetail.upstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">下游影响</span>
              <span class="value">{{ tableDetail.downstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">关联API</span>
              <span class="value">{{ tableDetail.apiCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">关系数</span>
              <span class="value">{{ tableDetail.relationCount }}</span>
            </div>
          </div>
        </el-card>

        <el-card
          v-if="tableRisks.length > 0"
          class="risk-card"
          style="margin-top: 16px;">
          <template #header>
            <span>数据风险提示</span>
          </template>
          <div class="risk-list">
            <div
              v-for="risk in tableRisks"
              :key="risk.id"
              class="risk-item">
              <el-tag
                :type="risk.level === 'high' ? 'danger' : risk.level === 'medium' ? 'warning' : 'info'"
                size="small"
              >
                {{ risk.level === 'high' ? '高风险' : risk.level === 'medium' ? '中风险' : '低风险' }}
              </el-tag>
              <span class="risk-text">{{ risk.text }}</span>
            </div>
          </div>
        </el-card>

        <el-card
          v-if="tables.length > 0 && !selectedTable"
          class="stats-card"
          style="margin-top: 16px;">
          <template #header>
            <span>数据统计</span>
          </template>
          <div class="stat-list">
            <div class="stat-item">
              <span class="stat-label">已扫描表</span>
              <span class="stat-value">{{ tables.length }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">总字段数</span>
              <span class="stat-value">{{ totalColumns }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">总关系数</span>
              <span class="stat-value">{{ totalRelations }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Tickets, Share, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { graphApi } from '@/api'
import { useRoute } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { loadScanVersions } from '@/utils/versionsCache'

interface TableColumn {
  columnName: string
  dataType: string
  columnComment?: string
}

interface TableListItem {
  id: string
  name: string
  /** Neo4j nodeName，用于 Cypher 查询匹配（getTableImpact 的 tableName 参数） */
  queryKey: string
  type: string
  columnCount: number
  relationCount: number
  upstreamCount: number
  downstreamCount: number
  apiCount: number
  serviceCount?: number
  columns?: TableColumn[]
}

interface RiskItem {
  id: string
  level: 'high' | 'medium' | 'low'
  text: string
}

interface VueFlowNode {
  id: string
  type: string
  position: { x: number; y: number }
  data: {
    label: string
    type: string
    [key: string]: any
  }
}

interface VueFlowEdge {
  id: string
  source: string
  target: string
  label: string
}

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = ref<string>('')
const versions = ref<any[]>([])

const viewMode = ref<'lineage' | 'downstream' | 'upstream'>('lineage')
const searchKeyword = ref('')
const selectedTable = ref<string | null>(null)
const loading = ref(false)

// 从后端加载的数据表列表，初始为空
const tables = ref<TableListItem[]>([])

const filteredTables = computed(() => {
  if (!searchKeyword.value) return tables.value
  return tables.value.filter(t => t.name.includes(searchKeyword.value))
})

const selectedTableName = computed(() => {
  const t = getSelectedTable()
  return t ? t.name : ''
})

const tableDetail = computed(() => ({
  columnCount: getSelectedTable()?.columnCount || 0,
  upstreamCount: getSelectedTable()?.upstreamCount || 0,
  downstreamCount: getSelectedTable()?.downstreamCount || 0,
  apiCount: getSelectedTable()?.apiCount || 0,
  relationCount: getSelectedTable()?.relationCount || 0,
}))

const totalColumns = computed(() => tables.value.reduce((s, t) => s + t.columnCount, 0))
const totalRelations = computed(() => tables.value.reduce((s, t) => s + t.relationCount, 0))

const tableRisks = ref<RiskItem[]>([])

// 血缘图数据
const lineageData = ref<{ nodes: any[]; edges: any[] }>({ nodes: [], edges: [] })

// VueFlow 格式数据
const vueFlowNodes = ref<VueFlowNode[]>([])
const vueFlowEdges = ref<VueFlowEdge[]>([])

function getSelectedTable(): TableListItem | undefined {
  return tables.value.find(t => t.id === selectedTable.value)
}

async function loadVersions() {
  try {
    const pid = projectId.value
    if (!pid) return
    versions.value = await loadScanVersions(pid)
  } catch (error) {
    console.error('loadVersions error:', error)
    ElMessage.error('操作失败')
  }
  }

async function loadTablesFromBackend() {
  if (!projectId.value || !currentVersion.value) return
  loading.value = true
  try {
    // 使用轻量 Table 节点查询接口，避免加载全量统一图谱
    const data: any = await graphApi.getTables(projectId.value, currentVersion.value)
    const tableNodes = Array.isArray(data) ? data : (data?.list || [])
    if (tableNodes.length > 0) {
      tables.value = tableNodes.map((n: any, idx: number) => ({
        id: n.id || n.key || `table-${idx}`,
        name: n.label || n.key || n.name || `table-${idx}`,
        queryKey: n.name || n.key || '',      // Neo4j nodeName，用于 getTableImpact API
        type: 'table',
        columnCount: n.columnCount || 0,
        relationCount: n.relationCount || 0,
        upstreamCount: 0,
        downstreamCount: 0,
        apiCount: 0,
      }))
    } else {
      tables.value = []
    }
  } catch (error) {
    console.error('加载后端表列表失败', error)
    tables.value = []
    ElMessage.warning('加载数据表列表失败')
  } finally {
    loading.value = false
  }
}

/**
 * 从后端加载指定表的血缘数据
 * 使用 getTableImpact 接口获取表的影响链路
 */
async function loadLineageData(tableName: string) {
  if (!projectId.value || !currentVersion.value) return
  if (!tableName) {
    ElMessage.warning('无法加载血缘数据：表名缺失')
    return
  }

  loading.value = true
  try {
    // 调用后端 API 查询该表的上游/下游影响链路
    const impact = await graphApi.getTableImpact(projectId.value, currentVersion.value, tableName)

    if (impact && Array.isArray(impact) && impact.length > 0) {
      // 解析影响链路为血缘图的节点和边
      const nodeMap = new Map<string, any>()
      const edgeList: any[] = []

      impact.forEach((path: any) => {
        const pathNodes = path.nodes || path.p?.nodes || []
        const pathEdges = path.edges || path.relationships || path.p?.edges || path.p?.relationships || []

        pathNodes.forEach((node: any) => {
          const nodeId = normalizeGraphId(node.id || node.elementId || node.properties?.nodeKey || node.properties?.key)
          if (nodeId && !nodeMap.has(nodeId)) {
            nodeMap.set(nodeId, {
              id: nodeId,
              labels: node.labels || ['Node'],
              properties: node.properties || {},
            })
          }
        })

        pathEdges.forEach((edge: any, idx: number) => {
          const source = normalizeGraphId(edge.source || edge.startNodeId || edge.startNodeElementId || edge.fromNodeId || edge.from)
          const target = normalizeGraphId(edge.target || edge.endNodeId || edge.endNodeElementId || edge.toNodeId || edge.to)
          if (!source || !target) return
          const edgeId = normalizeGraphId(edge.id || `${source}-${target}-${idx}`)
          if (!edgeList.find(e => e.id === edgeId || (e.source === source && e.target === target))) {
            edgeList.push({
              id: edgeId,
              source,
              target,
              type: edge.type || edge.label || edge.properties?.type || '',
            })
          }
        })

        // 兼容旧格式：{start, end, relationship}
        if (path.start && path.end) {
          const startId = normalizeGraphId(path.start.id || path.start.elementId)
          const endId = normalizeGraphId(path.end.id || path.end.elementId)
          if (startId && endId) {
            edgeList.push({
              id: `${startId}-${endId}`,
              source: startId,
              target: endId,
              type: path.relationship?.type || '',
            })
          }
        }
        // 兼容只返回节点序列的路径
        if (path.p && path.p.nodes) {
          for (let i = 0; i < path.p.nodes.length - 1; i++) {
            const from = path.p.nodes[i]
            const to = path.p.nodes[i + 1]
            const fromId = normalizeGraphId(from.id || from.elementId)
            const toId = normalizeGraphId(to.id || to.elementId)
            if (fromId && toId && !edgeList.find(e => e.source === fromId && e.target === toId)) {
              edgeList.push({
                id: `${fromId}-${toId}`,
                source: fromId,
                target: toId,
                type: '',
              })
            }
          }
        }
      })

      lineageData.value = {
        nodes: Array.from(nodeMap.values()),
        edges: edgeList,
      }
      convertToVueFlow()
      ElMessage.success(`已加载 ${tableName} 的血缘链路`)

      // 更新选中表的上游/下游计数
      const selected = getSelectedTable()
      if (selected) {
        const upstreamSet = new Set<string>()
        const downstreamSet = new Set<string>()
        edgeList.forEach(e => {
          if (e.target === selected.id && e.source) upstreamSet.add(e.source)
          if (e.source === selected.id && e.target) downstreamSet.add(e.target)
        })
        selected.upstreamCount = upstreamSet.size
        selected.downstreamCount = downstreamSet.size
      }
    } else {
      lineageData.value = { nodes: [], edges: [] }
      vueFlowNodes.value = []
      vueFlowEdges.value = []
      ElMessage.info(`${tableName} 暂无血缘数据`)
    }
  } catch (error) {
    console.error('加载表血缘数据失败', error)
    lineageData.value = { nodes: [], edges: [] }
    vueFlowNodes.value = []
    vueFlowEdges.value = []
    ElMessage.warning('加载血缘数据失败')
  } finally {
    loading.value = false
  }
}

/**
 * 将后端数据转换为 VueFlow 格式
 */
function convertToVueFlow() {
  const nodes: VueFlowNode[] = []
  const edges: VueFlowEdge[] = []
  let x = 50
  let y = 50
  const existingNodes = new Set<string>()

  lineageData.value.nodes.forEach((node: any) => {
    const nodeId = node.id || node.elementId
    if (!nodeId || existingNodes.has(nodeId)) return
    existingNodes.add(nodeId)

    const labels = node.labels ? (Array.isArray(node.labels) ? node.labels[0] : node.labels) : 'Node'
    const props = node.properties || {}

    nodes.push({
      id: nodeId,
      type: 'custom',
      position: { x, y },
      data: {
        label: props.nodeName || props.displayName || props.label || nodeId,
        type: labels,
        confidence: props.confidence || 1,
      },
    })

    x += 180
    if (x > 800) {
      x = 50
      y += 100
    }
  })

  // 去重边
  const edgeKeys = new Set<string>()
  lineageData.value.edges.forEach((edge: any) => {
    const source = edge.source || edge.startNodeId
    const target = edge.target || edge.endNodeId
    const edgeKey = `${source}-${target}`
    if (!source || !target || edgeKeys.has(edgeKey)) return
    edgeKeys.add(edgeKey)

    edges.push({
      id: edge.id || edgeKey,
      source,
      target,
      label: edge.type || edge.label || '',
    })
  })

  vueFlowNodes.value = nodes
  vueFlowEdges.value = edges
}

function normalizeGraphId(value: any): string {
  return value == null ? '' : String(value)
}

async function selectTable(id: string) {
  selectedTable.value = selectedTable.value === id ? null : id
  if (!selectedTable.value) {
    lineageData.value = { nodes: [], edges: [] }
    vueFlowNodes.value = []
    vueFlowEdges.value = []
    return
  }

  const selected = getSelectedTable()
  if (selected) {
    try {
      await loadLineageData(selected.queryKey || selected.name)
    } catch (error) {
      console.error('selectTable error:', error)
      ElMessage.error('加载血缘数据失败')
    }
  }
}

async function refreshGraph() {
  try {
    if (selectedTable.value) {
      const selected = getSelectedTable()
      if (selected) {
        await loadLineageData(selected.queryKey || selected.name)
      }
    } else {
      await loadTablesFromBackend()
    }
  } catch (error) {
    console.error('refreshGraph error:', error)
    ElMessage.error('刷新血缘图失败')
  }
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
    if (projectId.value && currentVersion.value) {
      await loadTablesFromBackend()
    }
  } catch (error) {
    console.error('onMounted error:', error)
    ElMessage.error('页面初始化失败')
  }
})
</script>

<style scoped>
.data-lineage-graph {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.table-list {
  max-height: 500px;
  overflow-y: auto;
}

.table-empty {
  padding: 20px 0;
}

.table-item {
  display: flex;
  align-items: center;
  padding: 10px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: all 0.2s;
}

.table-item:hover {
  background-color: #f5f7fa;
}

.table-item.active {
  background-color: #ecf5ff;
}

.table-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 10px;
  background-color: #f0f9eb;
  color: #67c23a;
}

.table-icon.relation {
  background-color: #ecf5ff;
  color: #409eff;
}

.table-icon.main {
  background-color: #fef0f0;
  color: #f56c6c;
}

.table-info {
  flex: 1;
  min-width: 0;
}

.table-name {
  font-weight: 500;
  font-size: 14px;
  color: #303133;
}

.table-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.graph-context {
  font-size: 13px;
  color: #606266;
}

.graph-container {
  height: 550px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border-radius: 4px;
  position: relative;
}

.graph-loading {
  text-align: center;
  color: #909399;
}

.graph-placeholder .placeholder-content {
  text-align: center;
  color: #909399;
}

.graph-placeholder p {
  margin: 12px 0;
  font-size: 16px;
}

.placeholder-tip {
  font-size: 13px !important;
  color: #c0c4cc !important;
}

.stats {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  justify-content: center;
}

.vue-flow-container {
  width: 100%;
  height: 100%;
}

.lineage-node {
  padding: 6px 10px;
  border-radius: 6px;
  background: #fff;
  border: 2px solid #ddd;
  min-width: 80px;
  text-align: center;
  font-size: 12px;
}

.lineage-node.table-node {
  border-color: #13c2c2;
  background: #e6fffb;
}

.lineage-node.api-node {
  border-color: #e6a23c;
  background: #fff7e6;
}

.lineage-node.service-node {
  border-color: #409eff;
  background: #ecf5ff;
}

.node-label {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-type {
  font-size: 10px;
  color: #999;
}

.table-detail h4 {
  margin: 0 0 12px 0;
  font-size: 15px;
  font-weight: 600;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.detail-item .label {
  color: #909399;
  font-size: 13px;
}

.detail-item .value {
  font-weight: 500;
  color: #303133;
}

.risk-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.risk-text {
  font-size: 13px;
  color: #606266;
  line-height: 1.4;
}

.stat-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.stat-label {
  color: #909399;
  font-size: 13px;
}

.stat-value {
  font-weight: 600;
  color: #303133;
  font-size: 14px;
}
</style>
