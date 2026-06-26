<template>
  <div class="unified-graph">
    <div class="page-header">
      <h3>统一图谱</h3>
      <div class="header-actions">
        <el-select v-model="currentVersion" placeholder="选择版本" size="small" style="width: 200px;">
          <el-option
            v-for="v in versions"
            :key="v.id"
            :label="`${v.createdAt} - ${v.nodeCount}节点 ${v.edgeCount}关系`"
            :value="v.id"
          />
        </el-select>
        <el-button type="primary" size="small" @click="refreshGraph">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 左侧过滤器 -->
      <el-col :span="4">
        <el-card class="filter-card">
          <template #header>
            <div class="card-header">
              <span>节点类型筛选</span>
              <el-button type="text" size="small" @click="resetFilters">重置</el-button>
            </div>
          </template>
          <div class="filter-section">
            <el-checkbox-group v-model="selectedNodeTypes">
              <div class="filter-item" v-for="type in nodeTypes" :key="type.value">
                <el-checkbox :label="type.value">
                  <span :style="{ color: type.color }">{{ type.label }}</span>
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>

          <div class="filter-divider"></div>

          <div class="filter-section">
            <h4>置信度筛选</h4>
            <el-slider v-model="minConfidence" :min="0" :max="1" :step="0.05" />
          </div>

          <div class="filter-divider"></div>

          <div class="filter-section">
            <h4>审核状态</h4>
            <el-checkbox-group v-model="selectedReviewStatus">
              <div class="filter-item">
                <el-checkbox label="APPROVED">已通过</el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox label="PENDING">待审核</el-checkbox>
              </div>
              <div class="filter-item">
                <el-checkbox label="REJECTED">已拒绝</el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </el-card>
      </el-col>

      <!-- 中间图谱区域 -->
      <el-col :span="14">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <el-button-group>
              <el-button size="small" @click="zoomIn">
                <el-icon><ZoomIn /></el-icon>
              </el-button>
              <el-button size="small" @click="zoomOut">
                <el-icon><ZoomOut /></el-icon>
              </el-button>
              <el-button size="small" @click="fitView">
                <el-icon><FullScreen /></el-icon>
              </el-button>
            </el-button-group>
            <el-button-group>
              <el-button size="small" @click="changeLayout('dagre')">分层布局</el-button>
              <el-button size="small" @click="changeLayout('circular')">环形布局</el-button>
              <el-button size="small" @click="changeLayout('force')">力导布局</el-button>
            </el-button-group>
            <el-button type="primary" size="small" @click="analyzePath">
              <el-icon><Connection /></el-icon>
              路径分析
            </el-button>
          </div>
          <div class="graph-container" ref="graphContainer">
            <el-empty v-if="loading" description="图谱加载中..." />
            <div v-else class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Connection /></el-icon>
                <p>图谱可视化区域</p>
                <p class="placeholder-tip">使用 Vue Flow 渲染知识图谱</p>
                <div class="stats">
                  <el-tag type="info">共 {{ graphData.nodes.length }} 个节点</el-tag>
                  <el-tag type="success">共 {{ graphData.edges.length }} 条关系</el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧详情面板 -->
      <el-col :span="6">
        <el-card class="detail-card" v-if="selectedNode">
          <template #header>
            <div class="card-header">
              <span>节点详情</span>
              <el-button type="text" size="small" @click="selectedNode = null">关闭</el-button>
            </div>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="节点ID">{{ selectedNode.id }}</el-descriptions-item>
            <el-descriptions-item label="节点类型">
              <el-tag size="small">{{ selectedNode.type }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="节点名称">{{ selectedNode.label }}</el-descriptions-item>
            <el-descriptions-item label="置信度">
              <el-tag :type="selectedNode.confidence >= 0.8 ? 'success' : selectedNode.confidence >= 0.6 ? 'warning' : 'danger'" size="small">
                {{ (selectedNode.confidence * 100).toFixed(0) }}%
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="审核状态">
              <el-tag type="warning" size="small">待审核</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="证据数量">{{ selectedNode.evidenceCount || 0 }}</el-descriptions-item>
          </el-descriptions>

          <div class="action-section">
            <h4>快捷操作</h4>
            <el-space wrap>
              <el-button type="success" size="small" @click="approveNode">确认正确</el-button>
              <el-button type="danger" size="small" @click="rejectNode">标记错误</el-button>
              <el-button type="primary" size="small" @click="viewEvidence">查看证据</el-button>
              <el-button size="small" @click="findNeighbors">查询邻居</el-button>
            </el-space>
          </div>

          <div class="related-section">
            <h4>关联关系</h4>
            <el-table :data="nodeEdges" size="small" border>
              <el-table-column prop="type" label="关系类型" width="100" />
              <el-table-column prop="target" label="目标节点" show-overflow-tooltip />
            </el-table>
          </div>
        </el-card>

        <el-card class="detail-card" v-else>
          <template #header>
            <span>图谱统计</span>
          </template>
          <div class="stat-grid">
            <div class="stat-item">
              <div class="stat-value">{{ graphData.nodes.length }}</div>
              <div class="stat-label">总节点数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ graphData.edges.length }}</div>
              <div class="stat-label">总关系数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value success">{{ approvedCount }}</div>
              <div class="stat-label">已验证</div>
            </div>
            <div class="stat-item">
              <div class="stat-value warning">{{ pendingCount }}</div>
              <div class="stat-label">待审核</div>
            </div>
          </div>

          <div class="legend-section">
            <h4>图例</h4>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, ZoomIn, ZoomOut, FullScreen, Connection } from '@element-plus/icons-vue'

const loading = ref(false)
const currentVersion = ref('')
const minConfidence = ref(0.5)
const selectedNodeTypes = ref<string[]>([])
const selectedReviewStatus = ref<string[]>(['APPROVED', 'PENDING'])
const selectedNode = ref<any>(null)

const versions = ref([
  { id: 'v1', createdAt: '2024-01-15 10:30', nodeCount: 1256, edgeCount: 3428 }
])

const nodeTypes = [
  { value: 'BUSINESS_DOMAIN', label: '业务领域', color: '#67c23a' },
  { value: 'BUSINESS_PROCESS', label: '业务流程', color: '#85ce61' },
  { value: 'FEATURE_MODULE', label: '功能模块', color: '#409eff' },
  { value: 'FEATURE', label: '功能点', color: '#66b1ff' },
  { value: 'API_ENDPOINT', label: 'API接口', color: '#e6a23c' },
  { value: 'CONTROLLER', label: 'Controller', color: '#ebb563' },
  { value: 'SERVICE', label: 'Service', color: '#f5d76e' },
  { value: 'MAPPER', label: 'Mapper', color: '#d3d3d3' },
  { value: 'SQL_STATEMENT', label: 'SQL语句', color: '#909399' },
  { value: 'TABLE', label: '数据库表', color: '#f56c6c' },
  { value: 'COLUMN', label: '字段', color: '#f78989' }
]

const graphData = ref({
  nodes: [
    { id: '1', type: 'BUSINESS_DOMAIN', label: '订单管理', confidence: 0.95, evidenceCount: 8 },
    { id: '2', type: 'FEATURE_MODULE', label: '订单服务', confidence: 0.92, evidenceCount: 15 },
    { id: '3', type: 'CONTROLLER', label: 'OrderController', confidence: 0.88, evidenceCount: 5 },
    { id: '4', type: 'SERVICE', label: 'OrderService', confidence: 0.85, evidenceCount: 12 },
    { id: '5', type: 'MAPPER', label: 'OrderMapper', confidence: 0.82, evidenceCount: 6 },
    { id: '6', type: 'TABLE', label: 't_order', confidence: 0.90, evidenceCount: 10 }
  ],
  edges: [
    { id: 'e1', source: '1', target: '2', type: 'CONTAINS', confidence: 0.92 },
    { id: 'e2', source: '2', target: '3', type: 'IMPLEMENTED_BY', confidence: 0.88 },
    { id: 'e3', source: '3', target: '4', type: 'CALLS', confidence: 0.85 },
    { id: 'e4', source: '4', target: '5', type: 'CALLS', confidence: 0.82 },
    { id: 'e5', source: '5', target: '6', type: 'EXECUTES', confidence: 0.90 }
  ]
})

const approvedCount = computed(() => 
  graphData.value.nodes.filter(n => n.confidence >= 0.8).length
)

const pendingCount = computed(() => 
  graphData.value.nodes.filter(n => n.confidence < 0.8).length
)

const nodeEdges = computed(() => {
  if (!selectedNode.value) return []
  return graphData.value.edges.filter(
    e => e.source === selectedNode.value.id || e.target === selectedNode.value.id
  )
})

const resetFilters = () => {
  selectedNodeTypes.value = []
  minConfidence.value = 0.5
  selectedReviewStatus.value = ['APPROVED', 'PENDING']
}

const refreshGraph = () => {
  loading.value = true
  setTimeout(() => {
    loading.value = false
    ElMessage.success('图谱已刷新')
  }, 1000)
}

const zoomIn = () => ElMessage.info('放大')
const zoomOut = () => ElMessage.info('缩小')
const fitView = () => ElMessage.info('适应视图')
const changeLayout = (type: string) => ElMessage.info(`切换到${type}布局`)
const analyzePath = () => ElMessage.info('路径分析功能')
const approveNode = () => ElMessage.success('已确认')
const rejectNode = () => ElMessage.success('已标记错误')
const viewEvidence = () => ElMessage.info('查看证据面板')
const findNeighbors = () => ElMessage.info('查询邻居节点')

onMounted(() => {
  currentVersion.value = 'v1'
})
</script>

<style scoped>
.unified-graph {
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

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-card,
.detail-card {
  height: 100%;
}

.filter-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.filter-item {
  margin-bottom: 8px;
}

.filter-divider {
  height: 1px;
  background: #ebeef5;
  margin: 16px 0;
}

.graph-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.graph-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.graph-container {
  flex: 1;
  min-height: 600px;
  background: #fafafa;
  border-radius: 4px;
}

.graph-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-content {
  text-align: center;
  color: #909399;
}

.placeholder-content p {
  margin: 12px 0 4px 0;
  font-size: 16px;
}

.placeholder-tip {
  font-size: 12px !important;
}

.stats {
  margin-top: 20px;
  display: flex;
  gap: 12px;
  justify-content: center;
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
  color: #303133;
  margin-bottom: 4px;
}

.stat-value.success {
  color: #67c23a;
}

.stat-value.warning {
  color: #e6a23c;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.legend-section {
  margin-top: 24px;
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
}

.legend-color {
  width: 16px;
  height: 16px;
  border-radius: 2px;
}

.legend-label {
  font-size: 12px;
  color: #606266;
}
</style>
