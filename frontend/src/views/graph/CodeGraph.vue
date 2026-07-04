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
        <el-form-item label="版本ID">
          <el-input
            v-model="query.versionId"
            placeholder="扫描版本ID"
            style="width: 200px" />
        </el-form-item>
        <el-form-item label="方法">
          <el-input
            v-model="query.method"
            placeholder="例如: TicketController.dispatch"
            style="width: 300px" />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            @click="queryGraph">
            查询
          </el-button>
          <el-button @click="loadExample">加载示例</el-button>
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

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = ref<string>('')
const versions = ref<any[]>([])

const query = reactive({
  versionId: currentVersion.value || '',
  method: (route.query.api as string) || (route.query.method as string) || ''
})

const graphData = ref<any>(null)
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const resultList = ref<any[]>([])

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

const loadExample = async () => {
  if (!projectId.value || !query.versionId) {
    ElMessage.warning('缺少项目ID或版本ID')
    return
  }
  if (!query.method) {
    ElMessage.info('请先输入 API 方法名（如 /api/user/login）再点击示例查询')
    return
  }
  if (query.method) {
    await queryGraph()
  } else {
    ElMessage.info('当前版本未找到可用于示例查询的 API 节点')
  }
}

const queryGraph = async () => {
  if (!projectId.value) {
    ElMessage.warning('缺少项目ID')
    return
  }
  if (!query.versionId || !query.method) {
    ElMessage.warning('请输入版本ID和方法名')
    return
  }
  try {
    const data = await graphApi.getApiChain(projectId.value, query.versionId, query.method)
    graphData.value = data
    processGraphData(data)
  } catch (e) {
    console.error(e)
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
        label: edge.label || edge.type || edge.properties?.type || 'RELATED'
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
    if (urlVersion && versions.value.some(v => v.id === urlVersion)) {
      currentVersion.value = urlVersion
    } else if (versions.value.length > 0) {
      currentVersion.value = versions.value[0].id
    }
    if (currentVersion.value) {
      query.versionId = currentVersion.value
    }
    if (query.versionId && query.method) {
      await queryGraph()
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
