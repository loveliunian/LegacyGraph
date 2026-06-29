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

        <el-card class="test-card" style="margin-top: 16px;" v-if="projectId && currentVersion">
          <template #header>
            <span>测试覆盖率</span>
          </template>
          <div v-if="loading" style="text-align:center;padding:12px;">
            <el-icon class="is-loading"><Refresh /></el-icon>
          </div>
          <template v-else>
            <div class="coverage-stats">
              <div class="coverage-item">
                <span class="coverage-label">整体覆盖率</span>
                <span class="coverage-value" :class="coverageLevel(coverageData.overall)">{{ coverageData.overall }}%</span>
              </div>
              <el-progress :percentage="coverageData.overall" :status="coverageData.overall >= 60 ? 'success' : coverageData.overall >= 30 ? 'warning' : 'exception'" />
            </div>
            <div class="coverage-item">
              <span class="coverage-label">核心功能</span>
              <span class="coverage-value success">{{ coverageData.core }}%</span>
            </div>
            <div class="coverage-item">
              <span class="coverage-label">边缘场景</span>
              <span class="coverage-value" :class="coverageData.edge >= 50 ? 'success' : 'danger'">{{ coverageData.edge }}%</span>
            </div>
            <el-button type="primary" size="small" style="width: 100%; margin-top: 16px;" @click="generateAllTests">
              生成补充测试用例
            </el-button>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Grid, Menu } from '@element-plus/icons-vue'
import { Graph } from '@antv/g6'
import { graphApi, testApi, factApi } from '@/api'
import type { GraphData, Node } from '@antv/g6'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = computed(() => route.query.version as string)

const selectedModule = ref<string | null>(null)
const graphContainer = ref<HTMLElement | null>(null)
const graphInstance = ref<Graph | null>(null)
const g6Node = ref<Node | null>(null)
const selectedNode = computed<any>(() => (g6Node.value as any)?.getModel() || null)
const loading = ref(false)

// 从后端加载的模块列表
const modules = ref<any[]>([])

// 测试覆盖率数据，从后端加载
const coverageData = ref<{
  overall: number
  core: number
  edge: number
}>({ overall: 0, core: 0, edge: 0 })

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
    const data = await graphApi.getFeatureView(projectId.value, currentVersion.value, module) as any

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

// @ts-expect-error - G6: 'data' does not exist on new G6 Graph type
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

/**
 * 从后端加载模块列表
 * 通过 getUnifiedGraph 获取节点后按模块分组
 */
async function loadModules() {
  if (!projectId.value || !currentVersion.value) return
  try {
    const data: any = await graphApi.getUnifiedGraph(projectId.value, currentVersion.value, 0)
    if (data?.nodes) {
      // 按模块分组统计
      const moduleMap = new Map<string, { count: number; types: Set<string> }>()
      const nodeTypes = new Set<string>()

      data.nodes.forEach((n: any) => {
        const module = n.type || 'Other'
        nodeTypes.add(module)
        if (!moduleMap.has(module)) {
          moduleMap.set(module, { count: 0, types: new Set() })
        }
        const entry = moduleMap.get(module)!
        entry.count++
      })

      const colors = ['#67c23a', '#409eff', '#e6a23c', '#f56c6c', '#909399', '#722ed1', '#13c2c2']
      modules.value = Array.from(moduleMap.entries()).map(([name, info], idx) => ({
        id: name,
        name,
        color: colors[idx % colors.length],
        featureCount: info.count,
      }))
    }
  } catch (error) {
    console.error('加载功能模块列表失败', error)
  }
}

/**
 * 从后端加载测试覆盖率数据
 * 通过全局图谱节点统计推算覆盖率
 */
async function loadCoverage() {
  if (!projectId.value || !currentVersion.value) return
  try {
    const data: any = await graphApi.getUnifiedGraph(projectId.value, currentVersion.value, 0)
    if (data?.nodes) {
      const total = data.nodes.length
      if (total === 0) {
        coverageData.value = { overall: 0, core: 0, edge: 0 }
        return
      }
      // 以置信度作为覆盖率的近似指标
      const confirmed = data.nodes.filter((n: any) =>
        n.status === 'CONFIRMED' || n.status === 'approved' || n.confidence >= 0.7
      ).length
      const highConfidence = data.nodes.filter((n: any) =>
        n.confidence >= 0.85
      ).length
      const lowConfidence = data.nodes.filter((n: any) =>
        n.confidence < 0.5 || n.status === 'PENDING'
      ).length

      coverageData.value = {
        overall: Math.round((confirmed / total) * 100),
        core: Math.round((highConfidence / total) * 100),
        edge: Math.round(((total - lowConfidence) / total) * 100),
      }
    }
  } catch (error) {
    console.error('加载覆盖率数据失败', error)
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
    // @ts-expect-error - G6: modes does not exist on GraphOptions in new G6
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
      // @ts-expect-error - G6: parameter 'node' implicitly has 'any' type
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
          background: { fill: '#fff' }
        }
      }
    }
  })

  graphInstance.value.on('node:click', (e: any) => {
    const node = e.item
    if (node) {
      g6Node.value = node as any
      ElMessage.info(`选中: ${selectedNode.value.label}`)
    }
  })

  graphInstance.value.on('canvas:click', () => {
    g6Node.value = null
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

  // 加载模块列表和覆盖率数据
  loadModules()
  loadCoverage()
})

const handleResize = () => {
  if (graphInstance.value && graphContainer.value) {
    const width = graphContainer.value.clientWidth
    const height = graphContainer.value.clientHeight || 600
    // @ts-expect-error - G6: changeSize does not exist on new G6 Graph type
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
  if (selectedModule.value) {
    loadGraph(selectedModule.value)
  }
}

const coverageLevel = (val: number): string => {
  if (val >= 60) return 'success'
  if (val >= 30) return ''
  return 'danger'
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
