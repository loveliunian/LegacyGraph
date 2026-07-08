<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>代码图谱查询</span>
          <el-tag type="info">Controller -> Service -> Mapper -> SQL -> Table</el-tag>
          <div class="actions">
            <el-dropdown @command="(cmd: string) => handleExport(cmd)">
              <el-button type="primary">
                导出
                <el-icon><arrow-down /></el-icon>
              </el-button>
              <el-dropdown-menu>
                <el-dropdown-item command="png">PNG 图片</el-dropdown-item>
                <el-dropdown-item command="svg">SVG 矢量</el-dropdown-item>
                <el-dropdown-item command="json">JSON 数据</el-dropdown-item>
                <el-dropdown-item command="csv">CSV 表格</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
          </div>
        </div>
      </template>
      <el-form
        :inline="true"
        :model="query"
        class="demo-form-inline">
        <el-form-item label="版本">
          <el-select
            v-model="query.versionId"
            placeholder="请选择版本"
            filterable
            style="width: 300px"
            @change="onVersionChange">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="formatVersionLabel(v)"
              :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="方法">
          <el-select
            v-model="query.method"
            placeholder="请选择 API 方法"
            filterable
            :loading="methodsLoading"
            style="width: 400px"
            :disabled="!query.versionId">
            <el-option
              v-for="m in methods"
              :key="m.nodeKey"
              :label="m.displayName"
              :value="m.nodeKey" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :disabled="!query.versionId || !query.method"
            @click="queryGraph">
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card
      v-if="graphData"
      class="mt-4">
      <div class="graph-container">
        <VueFlow
          :nodes="nodes"
          :edges="edges"
          fit-view
        >
          <template #node-custom="nodeProps">
            <div
              class="custom-node"
              :class="{
                'high-confidence': nodeProps.data.confidence >= 0.8,
                'low-confidence': nodeProps.data.confidence < 0.8
              }"
            >
              <div class="node-header">{{ nodeProps.data.label }}</div>
              <div class="node-body">{{ nodeProps.data.type }}</div>
            </div>
          </template>
        </VueFlow>
      </div>
    </el-card>

    <el-card
      v-if="resultList && resultList.length > 0"
      class="mt-4">
      <template #header>
        <span>节点列表</span>
      </template>
      <el-table
        :data="resultList"
        border>
        <el-table-column
          prop="labels"
          label="类型"
          width="120" />
        <el-table-column
          prop="properties.nodeName"
          label="名称"
          width="180" />
        <el-table-column
          prop="properties.displayName"
          label="显示名称"
          width="180" />
        <el-table-column
          prop="properties.confidence"
          label="置信度"
          width="100">
          <template #default="{row}">
            <el-tag :type="row.properties.confidence >= 0.8 ? 'success' : 'warning'">
              {{ (row.properties.confidence * 100).toFixed(0) }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="properties.status"
          label="状态"
          width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { graphApi } from '@/api'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { loadScanVersions } from '@/utils/versionsCache'
import { dictLabel } from '@/utils/dict'

interface GraphNode {
  id: string
  type: string
  position: { x: number; y: number }
  data: {
    label: string
    type: string
    confidence: number
  }
}

interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
}

interface ApiEndpoint {
  id: string
  nodeKey: string
  displayName: string
  nodeName: string
}

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)
const versions = ref<any[]>([])
const methods = ref<ApiEndpoint[]>([])
const methodsLoading = ref(false)

const query = reactive({
  versionId: '',
  method: ''
})

const graphData = ref<any>(null)
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const resultList = ref<any[]>([])

/** 格式化版本下拉标签 */
function formatVersionLabel(v: any): string {
  const name = v.versionName || v.versionNo || v.id
  const status = v.scanStatus ? ` [${v.scanStatus}]` : ''
  return `${name}${status}`
}

/** 加载扫描版本列表 */
async function loadVersions() {
  try {
    const pid = projectId.value
    if (!pid) return
    const result = await loadScanVersions(pid)
    versions.value = result || []
  } catch (error) {
    console.error('loadVersions error:', error)
    ElMessage.error('加载版本列表失败')
  }
}

/** 加载指定版本下的 ApiEndpoint 列表 */
async function loadMethods(versionId: string) {
  if (!versionId) {
    methods.value = []
    return
  }
  methodsLoading.value = true
  try {
    const result = await graphApi.getApiEndpoints(projectId.value, versionId)
    methods.value = (Array.isArray(result) ? result : []) as ApiEndpoint[]
  } catch (error) {
    console.error('loadMethods error:', error)
    methods.value = []
    ElMessage.error('加载方法列表失败')
  } finally {
    methodsLoading.value = false
  }
}

/** 版本切换时：清空方法选择，重新加载方法列表 */
function onVersionChange(versionId: string) {
  query.method = ''
  methods.value = []
  graphData.value = null
  nodes.value = []
  edges.value = []
  resultList.value = []
  if (versionId) {
    loadMethods(versionId)
  }
}

const queryGraph = async () => {
  if (!projectId.value) {
    ElMessage.warning('缺少项目ID')
    return
  }
  if (!query.versionId || !query.method) {
    ElMessage.warning('请选择版本和方法')
    return
  }
  try {
    const data = await graphApi.getApiChain(projectId.value, query.versionId, query.method)
    graphData.value = data
    processGraphData(data)
  } catch (e) {
    console.error(e)
    ElMessage.error('查询调用链失败')
  }
}

const processGraphData = (data: any[]) => {
  const paths = Array.isArray(data) ? data : []
  const processedNodes = new Map<string, GraphNode>()
  const processedEdges: GraphEdge[] = []
  let x = 50
  let y = 50

  paths.forEach(path => {
    const pathNodes = path.nodes || path.p?.nodes || []
    pathNodes.forEach((node: any) => {
      const nodeId = normalizeGraphId(node.id || node.elementId || node.properties?.nodeKey)
      if (!nodeId) return
      if (!processedNodes.has(nodeId)) {
        const labels = node.labels ? node.labels[0] : 'Node'
        const props = node.properties || {}
        processedNodes.set(nodeId, {
          id: nodeId,
          type: labels,
          position: { x, y },
          data: {
            label: props.displayName || props.nodeName || props.nodeKey || nodeId,
            type: labels,
            confidence: props.confidence || 1
          }
        })
        x += 180
        if (x > 900) {
          x = 50
          y += 100
        }
      }
    })
  })

  paths.forEach(path => {
    const pathEdges = path.edges || path.relationships || path.p?.edges || path.p?.relationships || []
    pathEdges.forEach((edge: any, idx: number) => {
      const source = normalizeGraphId(edge.source || edge.startNodeId || edge.startNodeElementId)
      const target = normalizeGraphId(edge.target || edge.endNodeId || edge.endNodeElementId)
      const edgeId = normalizeGraphId(edge.id || `${source}-${target}-${idx}`)
      if (!source || !target || processedEdges.find(e => e.id === edgeId)) return
      processedEdges.push({
        id: edgeId,
        source,
        target,
        label: edge.label || dictLabel('graph_edge_type', edge.type || edge.properties?.type || '') || 'RELATED'
      })
    })

    const pathNodes = path.nodes || path.p?.nodes || []
    if (pathEdges.length === 0 && pathNodes.length >= 2) {
      for (let i = 0; i < pathNodes.length - 1; i++) {
        const fromId = normalizeGraphId(pathNodes[i].id || pathNodes[i].elementId)
        const toId = normalizeGraphId(pathNodes[i + 1].id || pathNodes[i + 1].elementId)
        const edgeId = `${fromId}-${toId}`
        if (!fromId || !toId || processedEdges.find(e => e.id === edgeId)) continue
        processedEdges.push({
          id: edgeId,
          source: fromId,
          target: toId,
          label: 'RELATED'
        })
      }
    }
  })

  nodes.value = Array.from(processedNodes.values())
  edges.value = processedEdges
  resultList.value = nodes.value.map(node => ({
    labels: node.data.type,
    properties: {
      nodeName: node.data.label,
      displayName: node.data.label,
      confidence: node.data.confidence,
      status: ''
    }
  }))
  if (nodes.value.length === 0) {
    ElMessage.info('未查询到调用链数据')
  }
}

const normalizeGraphId = (value: any): string => {
  return value == null ? '' : String(value)
}

const handleExport = (cmd: string) => {
  ElMessage.info(`导出图谱为 ${cmd} 格式`)
}

onMounted(async () => {
  try {
    await loadVersions()
    // 优先使用 URL 参数指定的版本，否则自动选择第一个版本
    const urlVersion = (route.query.version as string) || ''
    let selectedVersion = ''
    if (urlVersion && versions.value.some(v => v.id === urlVersion)) {
      selectedVersion = urlVersion
    } else if (versions.value.length > 0) {
      selectedVersion = versions.value[0].id
    }
    if (selectedVersion) {
      query.versionId = selectedVersion
      await loadMethods(selectedVersion)
      // URL 参数中带了方法名，自动选中并查询
      const urlMethod = (route.query.api as string) || (route.query.method as string) || ''
      if (urlMethod && methods.value.some(m => m.nodeKey === urlMethod)) {
        query.method = urlMethod
        await queryGraph()
      }
    }
  } catch (error) {
    console.error('onMounted error:', error)
    ElMessage.error('页面初始化失败')
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.actions {
  display: flex;
  gap: 8px;
}

.graph-container {
  height: 600px;
  width: 100%;
}

.mt-4 {
  margin-top: 1rem;
}

.custom-node {
  padding: 8px 12px;
  border-radius: 8px;
  background: #fff;
  border: 2px solid #ddd;
  min-width: 100px;
  text-align: center;
}

.custom-node.high-confidence {
  border-color: #67c23a;
}

.custom-node.low-confidence {
  border-color: #e6a23c;
}

.node-header {
  font-weight: bold;
  font-size: 12px;
}

.node-body {
  font-size: 10px;
  color: #999;
}
</style>
