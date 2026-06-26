<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>代码图谱查询</span>
          <el-tag type="info">Controller -> Service -> Mapper -> SQL -> Table</el-tag>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="demo-form-inline">
        <el-form-item label="版本ID">
          <el-input v-model="query.versionId" placeholder="扫描版本ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="方法">
          <el-input v-model="query.method" placeholder="例如: TicketController.dispatch" style="width: 300px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="queryGraph">查询</el-button>
          <el-button @click="loadExample">加载示例</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="graphData" class="mt-4">
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

    <el-card v-if="resultList && resultList.length > 0" class="mt-4">
      <template #header>
        <span>节点列表</span>
      </template>
      <el-table :data="resultList" border>
        <el-table-column prop="labels" label="类型" width="120" />
        <el-table-column prop="properties.nodeName" label="名称" width="180" />
        <el-table-column prop="properties.displayName" label="显示名称" width="180" />
        <el-table-column prop="properties.confidence" label="置信度" width="100">
          <template #default="{row}">
            <el-tag :type="row.properties.confidence >= 0.8 ? 'success' : 'warning'">
              {{ (row.properties.confidence * 100).toFixed(0) }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="properties.status" label="状态" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { graphApi } from '@/api'
import { ElMessage } from 'element-plus'

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

const query = reactive({
  versionId: '',
  method: ''
})

const graphData = ref<any>(null)
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const resultList = ref<any[]>([])

// 加载工单派发示例 (来自详细设计文档)
const loadExample = () => {
  // 示例: TicketController.dispatch -> TicketService.dispatch -> TicketMapper.updateHandler -> SQL UPDATE -> t_ticket
  const exampleNodes: GraphNode[] = [
    {
      id: 'TicketController.dispatch',
      type: 'Controller',
      position: { x: 100, y: 150 },
      data: { label: 'TicketController.dispatch', type: 'Controller', confidence: 0.98 }
    },
    {
      id: 'TicketService.dispatch',
      type: 'Service',
      position: { x: 350, y: 150 },
      data: { label: 'TicketService.dispatch', type: 'Service', confidence: 0.95 }
    },
    {
      id: 'TicketMapper.updateHandler',
      type: 'Mapper',
      position: { x: 600, y: 150 },
      data: { label: 'TicketMapper.updateHandler', type: 'Mapper', confidence: 0.95 }
    },
    {
      id: 'SQL_UPDATE_t_ticket',
      type: 'SQL',
      position: { x: 800, y: 100 },
      data: { label: 'UPDATE t_ticket', type: 'SQL', confidence: 0.92 }
    },
    {
      id: 't_ticket.status',
      type: 'Column',
      position: { x: 950, y: 50 },
      data: { label: 'status', type: 'Column', confidence: 0.98 }
    },
    {
      id: 't_ticket.handler_id',
      type: 'Column',
      position: { x: 950, y: 150 },
      data: { label: 'handler_id', type: 'Column', confidence: 0.98 }
    },
  ]

  const exampleEdges: GraphEdge[] = [
    { id: 'c->s', source: 'TicketController.dispatch', target: 'TicketService.dispatch', label: 'CALLS' },
    { id: 's->m', source: 'TicketService.dispatch', target: 'TicketMapper.updateHandler', label: 'CALLS' },
    { id: 'm->sql', source: 'TicketMapper.updateHandler', target: 'SQL_UPDATE_t_ticket', label: 'EXECUTES' },
    { id: 'sql->status', source: 'SQL_UPDATE_t_ticket', target: 't_ticket.status', label: 'WRITES' },
    { id: 'sql->handler', source: 'SQL_UPDATE_t_ticket', target: 't_ticket.handler_id', label: 'WRITES' },
  ]

  nodes.value = exampleNodes
  edges.value = exampleEdges
  graphData.value = exampleNodes
  ElMessage.success('已加载「工单派发」代码图谱示例')
}

const queryGraph = async () => {
  if (!query.versionId || !query.method) {
    ElMessage.warning('请输入版本ID和方法名')
    return
  }
  try {
    const data = await graphApi.getApiChain(query.versionId, query.method)
    graphData.value = data
    // 解析图谱数据转换为VueFlow格式
    processGraphData(data)
  } catch (e) {
    console.error(e)
  }
}

const processGraphData = (data: any[]) => {
  const processedNodes = new Map<string, GraphNode>()
  const processedEdges: GraphEdge[] = []
  let x = 50
  let y = 50

  data.forEach(path => {
    if (path.nodes) {
      path.nodes.forEach((node: any) => {
        if (!processedNodes.has(node.id)) {
          const labels = node.labels ? node.labels[0] : 'Node'
          processedNodes.set(node.id, {
            id: node.id,
            type: labels,
            position: { x, y },
            data: {
              label: node.properties.nodeName || node.id,
              type: labels,
              confidence: node.properties.confidence || 1
            }
          })
          x += 180
          if (x > 900) {
            x = 50
            y += 100
          }
        }
      })
    }
  })

  // 提取边 (从路径中)
  data.forEach(path => {
    if (path.nodes && path.nodes.length >= 2) {
      for (let i = 0; i < path.nodes.length - 1; i++) {
        const from = path.nodes[i]
        const to = path.nodes[i + 1]
        const edgeId = `${from.id}-${to.id}`
        if (!processedEdges.find(e => e.id === edgeId)) {
          processedEdges.push({
            id: edgeId,
            source: from.id,
            target: to.id,
            label: getEdgeType(from, to)
          })
        }
      }
    }
  })

  nodes.value = Array.from(processedNodes.values())
  edges.value = processedEdges
  resultList.value = data
}

const getEdgeType = (from: any, to: any) => {
  // 从properties中获取关系类型
  return 'RELATED'
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
