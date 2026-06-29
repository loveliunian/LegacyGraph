<template>
  <div class="feature-graph">
    <div class="page-header">
      <h3>功能图谱</h3>
      <el-button type="primary" size="small" @click="exportReport">
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
              <div class="module-icon" :style="{ backgroundColor: module.color }">
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
            <el-button-group>
              <el-button size="small" @click="zoomIn">放大</el-button>
              <el-button size="small" @click="zoomOut">缩小</el-button>
              <el-button size="small" @click="fitView">适应</el-button>
            </el-button-group>
          </div>
          <div class="graph-container" ref="graphContainer"></div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="detail-card">
          <template #header>
            <span>功能详情</span>
          </template>
          <div v-if="!selectedNode" class="empty-state">
            <el-empty description="点击功能节点查看详情" />
          </div>
          <div v-else class="node-detail">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="功能">{{ selectedNode.label }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ selectedNode.type }}</el-descriptions-item>
              <el-descriptions-item label="页面" v-if="selectedNode.page">{{ selectedNode.page }}</el-descriptions-item>
              <el-descriptions-item label="API" v-if="selectedNode.api">{{ selectedNode.api }}</el-descriptions-item>
              <el-descriptions-item label="置信度">
                <el-tag :type="selectedNode.confidence >= 0.85 ? 'success' : selectedNode.confidence >= 0.7 ? 'warning' : 'danger'">
                  {{ (selectedNode.confidence * 100).toFixed(1) }}%
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <div class="action-buttons" style="margin-top: 16px;">
              <el-button type="primary" size="small" @click="generateTests">生成测试</el-button>
              <el-button size="small" @click="viewEvidence">查看证据</el-button>
            </div>
          </div>
        </el-card>

        <el-card class="test-card" style="margin-top: 16px;">
          <template #header>
            <span>测试覆盖率</span>
          </template>
          <div class="coverage-stats">
            <div class="coverage-item">
              <span class="coverage-label">整体覆盖率</span>
              <span class="coverage-value">68%</span>
            </div>
            <el-progress :percentage="68" status="warning" />
          </div>
          <div class="coverage-item">
            <span class="coverage-label">核心功能</span>
            <span class="coverage-value success">85%</span>
          </div>
          <div class="coverage-item">
            <span class="coverage-label">边缘场景</span>
            <span class="coverage-value danger">32%</span>
          </div>
          <el-button type="primary" size="small" style="width: 100%; margin-top: 16px;" @click="generateAllTests">
            生成补充测试用例
          </el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Grid, Menu } from '@element-plus/icons-vue'
import { Graph } from '@antv/g6'
import { graphApi } from '@/api'
import type { GraphData, Node } from '@antv/g6'

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = computed(() => route.query.version as string)

const selectedModule = ref<string | null>(null)
const graphContainer = ref<HTMLElement | null>(null)
const graphInstance = ref<Graph | null>(null)
const selectedNode = ref<Node | null>(null)
const loading = ref(false)

const modules = ref<any[]>([])

const nodeColorMap: Record<string, string> = {
  Page: '#722ed1',
  Button: '#eb2f96',
  ApiEndpoint: '#52c41a',
  Permission: '#1890ff',
  Feature: '#13c2c2',
  FeatureModule: '#409eff',
}

const getNodeStyle = (node: any) => {
  const color = nodeColorMap[node.type as string] || '#999'
  return {
    fill: color + '20',
    stroke: color,
    lineWidth: 2
  }
}

async function loadGraph(module: string) {
  if (!projectId.value || !currentVersion.value) {
    ElMessage.warning('缺少项目ID或版本ID')
    return
  }
  loading.value = true
  try {
    const data = await graphApi.getFeatureView(projectId.value, currentVersion.value, module)

    if (!data || !data.nodes) {
      ElMessage.warning('该功能模块暂无图谱数据')
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

    graphInstance.value?.data(g6Data)
    graphInstance.value?.render()
    ElMessage.success(`加载完成: ${data.nodeCount || 0} 节点`)
  } catch (error) {
    console.error('加载功能图谱失败', error)
    ElMessage.error('加载功能图谱失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await nextTick()
  if (!graphContainer.value) return

  const container = graphContainer.value
  const width = container.clientWidth
  const height = container.clientHeight || 600

  graphInstance.value = new Graph({
    container: container,
    width,
    height,
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node', 'click-select']
    },
    layout: {
      type: 'force',
      linkDistance: 120,
      nodeStrength: -200,
    },
    defaultNode: {
      size: 35,
      style: (node) => getNodeStyle(node),
      labelCfg: {
        position: 'bottom',
        style: {
          fontSize: 11,
          fill: '#333'
        }
      }
    },
    defaultEdge: {
      type: 'polyline',
      style: {
        radius: 8,
        endArrow: {
          path: Extension.arrow,
          fill: '#aaa'
        }
      },
      labelCfg: {
        autoRotate: true,
        style: {
          fill: '#aaa',
          fontSize: 10,
          background: { fill: '#fff' }
        }
      }
    }
  })

  graphInstance.value.on('node:click', (e) => {
    const node = e.item
    if (node) {
      selectedNode.value = node.getModel()
      ElMessage.info(`选中: ${selectedNode.value.label}`)
    }
  })

  graphInstance.value.on('canvas:click', () => {
    selectedNode.value = null
  })

  // 如果有模块参数，直接加载
  const moduleQuery = route.query.module as string
  if (moduleQuery) {
    selectedModule.value = moduleQuery
    loadGraph(moduleQuery)
  } else {
    graphInstance.value.render()
  }

  window.addEventListener('resize', handleResize)
})

const handleResize = () => {
  if (graphInstance.value && graphContainer.value) {
    const width = graphContainer.value.clientWidth
    const height = graphContainer.value.clientHeight || 600
    graphInstance.value.changeSize(width, height)
  }
}

const getSelectedModuleName = () => {
  if (!selectedModule.value) return '全部'
  const module = modules.value.find(m => m.id === selectedModule.value)
  return module ? module.name : '全部'
}

const selectModule = (id: string) => {
  selectedModule.value = selectedModule.value === id ? null : id
}

const exportReport = () => {
  ElMessage.success('功能清单导出中...')
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

const generateTests = () => {
  if (!selectedNode.value) return
  ElMessage.info(`正在为 ${selectedNode.value.label} 生成测试用例...`)
}

const generateAllTests = () => {
  ElMessage.info('正在批量生成补充测试用例...')
}

const viewEvidence = () => {
  if (!selectedNode.value) return
  ElMessage.info(`查看 ${selectedNode.value.label} 的证据来源`)
}
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
