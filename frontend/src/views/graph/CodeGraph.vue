<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>代码图谱查询</span>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="demo-form-inline">
        <el-form-item label="版本ID">
          <el-input v-model="query.versionId" placeholder="扫描版本ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="API路径">
          <el-input v-model="query.api" placeholder="例如: POST /api/process/start" style="width: 300px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="queryGraph">查询</el-button>
        </el-form-item>
      </el>
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
        <el-table-column prop="properties.nodeName" label="名称" width="150" />
        <el-table-column prop="properties.displayName" label="显示名称" width="150" />
        <el-table-column prop="properties.confidence" label="置信度" width="100">
          <template #default="{row}">
            <el-tag :type="row.properties.confidence >= 0.8 ? 'success' : 'warning'">
              {{ row.properties.confidence }}
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
  api: ''
})

const graphData = ref<any>(null)
const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const resultList = ref<any[]>([])

const queryGraph = async () => {
  if (!query.versionId || !query.api) {
    return
  }
  try {
    const data = await graphApi.getApiChain(query.versionId, query.api)
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
  let nodeIdCounter = 0

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
          x += 150
          if (x > 800) {
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
