<template>
  <div class="business-graph">
    <div class="page-header">
      <h3>业务图谱</h3>
      <el-button type="primary" size="small" @click="toggleAiView">
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
                <el-tag v-if="data.confidence" size="small" :type="data.confidence >= 0.8 ? 'success' : data.confidence >= 0.6 ? 'warning' : 'danger'" style="margin-left: 8px;">
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
            <el-button-group>
              <el-button size="small" @click="zoomIn">放大</el-button>
              <el-button size="small" @click="zoomOut">缩小</el-button>
              <el-button size="small" @click="fitView">适应</el-button>
              <el-button size="small" @click="refreshLayout">刷新</el-button>
            </el-button-group>
          </div>
          <div class="graph-container" ref="graphContainer">
            <!-- G6 画布 will be mounted here -->
          </div>
        </el-card>
      </el-col>

      <el-col :span="5">
        <el-card class="detail-card">
          <template #header>
            <span>节点详情</span>
          </template>
          <div v-if="!selectedNode" class="empty-state">
            <el-empty description="点击节点查看详情" />
          </div>
          <div v-else class="node-detail">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="节点ID">{{ selectedNode.id }}</el-descriptions-item>
              <el-descriptions-item label="名称">{{ selectedNode.label }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ selectedNode.type }}</el-descriptions-item>
              <el-descriptions-item label="置信度">
                <el-tag :type="selectedNode.confidence >= 0.85 ? 'success' : selectedNode.confidence >= 0.7 ? 'warning' : 'danger'">
                  {{ (selectedNode.confidence * 100).toFixed(1) }}%
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="描述" v-if="selectedNode.description">
                {{ selectedNode.description }}
              </el-descriptions-item>
            </el-descriptions>
            <div v-if="selectedNode.evidence && selectedNode.evidence.length > 0" class="evidence-list">
              <div class="evidence-title">证据来源:</div>
              <el-tag v-for="ev in selectedNode.evidence" :key="ev.sourceUri" size="small" class="evidence-tag">
                {{ ev.sourceType }}: {{ ev.sourceUri.split('/').pop() }}
              </el-tag>
            </div>
            <div class="action-buttons" style="margin-top: 16px;">
              <el-button type="primary" size="small" @click="generateTestCases">生成测试用例</el-button>
              <el-button size="small" @click="goToReview">进入审核</el-button>
            </div>
          </div>
        </el-card>

        <el-card class="ai-card" style="margin-top: 16px;">
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
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { View, Share, MagicStick } from '@element-plus/icons-vue'
import { Graph } from '@antv/g6'
import { graphApi, reviewApi } from '@/api'
import type { GraphData, Node } from '@antv/g6'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = computed(() => route.query.version as string)

const showAiView = ref(false)
const graphContainer = ref<HTMLElement | null>(null)
const graphInstance = ref<Graph | null>(null)
const g6Node = ref<Node | null>(null)
// @ts-expect-error - G6: getModel does not exist on new Node type
const selectedNode = computed(() => g6Node.value?.getModel() as any || null)
const loading = ref(false)

const domainTree = ref<any[]>([])

const graphStats = ref({
  autoMergeRate: 0,
  pendingReview: 0,
  avgConfidence: 0,
  testPassRate: 0
})

/** 从统一图谱加载领域树和统计信息 */
async function loadDomainTree() {
  if (!projectId.value || !currentVersion.value) return
  try {
    const data: any = await graphApi.getUnifiedGraph(projectId.value, currentVersion.value, 0)
    if (data?.nodes) {
      const nodes = data.nodes as any[]
      // 按领域分组构建树
      const domainMap = new Map<string, any[]>()
      nodes.forEach((n: any) => {
        const domain = n.type || n.nodeType || 'Other'
        if (!domainMap.has(domain)) domainMap.set(domain, [])
        domainMap.get(domain)!.push(n)
      })
      const colors = ['#1890ff', '#52c41a', '#faad14', '#722ed1', '#eb2f96', '#13c2c2']
      domainTree.value = Array.from(domainMap.entries()).map(([name, items], idx) => ({
        id: name,
        name,
        confidence: items.reduce((s: number, n: any) => s + (n.confidence || 0.5), 0) / items.length,
        children: [],
      }))

      // 统计信息
      const confirmed = nodes.filter(n => n.status === 'CONFIRMED' || n.status === 'approved').length
      graphStats.value = {
        autoMergeRate: nodes.length > 0 ? Math.round(confirmed / nodes.length * 100) : 0,
        pendingReview: nodes.filter(n => n.status === 'PENDING_CONFIRM' || n.status === 'pending').length,
        avgConfidence: nodes.length > 0
          ? nodes.reduce((s, n) => s + (n.confidence || 0.5), 0) / nodes.length
          : 0,
        testPassRate: 0,
      }
    }
  } catch (e) {
    console.error('加载领域树失败', e)
  }
}

const nodeColorMap: Record<string, string> = {
  BusinessDomain: '#1890ff',
  BusinessProcess: '#52c41a',
  FeatureModule: '#faad14',
  Feature: '#722ed1',
  Page: '#722ed1',
  ApiEndpoint: '#eb2f96',
  Controller: '#ebb563',
  Service: '#f5d76e',
  Mapper: '#909399',
  SqlStatement: '#f56c6c',
  Table: '#13c2c2',
  Role: '#fa8c16'
}

const getNodeStyle = (node: any) => {
  const color = nodeColorMap[node.type as string] || '#999'
  return {
    fill: color + '20',
    stroke: color,
    lineWidth: 2
  }
}

async function loadGraph(domain: string) {
  if (!projectId.value || !currentVersion.value) return
  loading.value = true
  try {
    const data = await graphApi.getBusinessView(projectId.value, currentVersion.value, domain) as any

    if (!data || !data.nodes) {
      ElMessage.warning('该业务域暂无图谱数据')
      graphInstance.value?.clear()
      return
    }

    // 转换为G6格式
    const g6Data: GraphData = {
      nodes: data.nodes.map((node: any) => ({
        id: node.id,
        label: node.properties.label || node.properties.displayName || node.properties.nodeName,
        type: node.properties.nodeType,
        x: Math.random() * 800,
        y: Math.random() * 600,
      })),
      edges: (data.edges || []).map((edge: any) => ({
        source: edge.startNodeId,
        target: edge.endNodeId,
        label: edge.properties.type,
      }))
    }

// @ts-expect-error - G6: 'data' does not exist on new G6 Graph type
    graphInstance.value?.data(g6Data)
    graphInstance.value?.render()
    ElMessage.success(`加载完成: ${data.nodeCount || 0} 节点`)
  } catch (error) {
    console.error('加载业务图谱失败', error)
    ElMessage.error('加载业务图谱失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await nextTick()
  if (!graphContainer.value) return

  // No icons import needed - relies on Element Plus icons
  loading.value = true
  // Create G6 graph
  const container = graphContainer.value
  const width = container.clientWidth
  const height = container.clientHeight || 600

  graphInstance.value = new Graph({
    container: container,
    width,
    height,
    // @ts-expect-error - G6: modes does not exist on GraphOptions in new G6
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node', 'click-select']
    },
    layout: {
      type: 'force',
      linkDistance: 150,
      nodeStrength: -300,
      edgeStrength: 0.1
    },
    defaultNode: {
      size: 40,
      // @ts-expect-error - G6: parameter 'node' implicitly has 'any' type
      style: (node) => getNodeStyle(node),
      labelCfg: {
        position: 'bottom',
        style: {
          fontSize: 12,
          fill: '#333'
        }
      }
    },
    defaultEdge: {
      type: 'polyline',
      style: {
        radius: 10,
        offset: 15,
        endArrow: {
          // @ts-expect-error - G6: Extension.arrow does not exist (new G6)
          path: Extension.arrow,
          fill: '#aaa'
        }
      },
      labelCfg: {
        autoRotate: true,
        style: {
          fill: '#aaa',
          fontSize: 10,
          background: {
            fill: '#ffffff',
            padding: [2, 2, 2, 2],
            radius: 2
          }
        }
      }
    }
  })

  // 节点点击事件
  graphInstance.value.on('node:click', (e: any) => {
    const node = e.item
    if (node) {
      g6Node.value = node as any
      ElMessage.info(`选中: ${selectedNode.value.label}`)
    }
  })

  // 空白点击
  graphInstance.value.on('canvas:click', () => {
    g6Node.value = null
  })

  // If we have a domain from query, load it
  const domainQuery = route.query.domain as string
  if (domainQuery) {
    loadGraph(domainQuery)
  } else {
    // Show empty state
    graphInstance.value.render()
  }

  // Handle resize
  window.addEventListener('resize', handleResize)

  // 加载领域树和统计信息
  loadDomainTree()
})

const handleResize = () => {
  if (graphInstance.value && graphContainer.value) {
    const width = graphContainer.value.clientWidth
    const height = graphContainer.value.clientHeight || 600
    // @ts-expect-error - G6: changeSize does not exist on new G6 Graph type
    graphInstance.value.changeSize(width, height)
  }
}

const handleDomainClick = (data: any) => {
  if (data.name) {
    loadGraph(data.name)
  }
}

const toggleAiView = () => {
  showAiView.value = !showAiView.value
  ElMessage.success(showAiView.value ? '已切换到AI归纳视图' : '已切换到原始视图')
  // In real implementation, this would filter nodes based on confidence
}

const zoomIn = () => {
  if (graphInstance.value) {
    const zoom = graphInstance.value.getZoom()
    graphInstance.value.zoomTo(zoom * 1.2)
  }
}

const zoomOut = () => {
  if (graphInstance.value) {
    const zoom = graphInstance.value.getZoom()
    graphInstance.value.zoomTo(zoom / 1.2)
  }
}

const fitView = () => {
  if (graphInstance.value) {
    graphInstance.value.fitView()
  }
}

const refreshLayout = () => {
  if (graphInstance.value) {
    graphInstance.value.layout()
  }
}

const generateTestCases = () => {
  if (!selectedNode.value) return
  router.push(`/projects/${projectId.value}/test-cases?nodeId=${selectedNode.value.id}`)
}

const goToReview = () => {
  if (!selectedNode.value) return
  router.push(`/projects/${projectId.value}/reviews?nodeId=${selectedNode.value.id}`)
}
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
