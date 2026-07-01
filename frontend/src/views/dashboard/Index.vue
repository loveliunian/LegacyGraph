<template>
  <div class="dashboard-container">
    <el-row :gutter="20">
      <!-- 左侧主区域 -->
      <el-col :span="16">
        <!-- 项目总体统计 -->
        <el-card class="stats-card">
          <template #header>
            <div class="card-header">
              <span>项目总体统计</span>
              <el-tag v-if="loadingDashboard" type="info" size="small">加载中...</el-tag>
            </div>
          </template>
          <el-row :gutter="10">
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number" style="color: #409eff;">{{ stats.totalNodes }}</div>
                <div class="stat-label">图谱节点</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number" style="color: #67c23a;">{{ stats.confirmedNodes }}</div>
                <div class="stat-label">已确认</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number" style="color: #e6a23c;">{{ stats.totalEdges }}</div>
                <div class="stat-label">关系连接</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number" style="color: #67c23a;">{{ stats.confirmedEdges }}</div>
                <div class="stat-label">已确认</div>
              </div>
            </el-col>
          </el-row>
          <el-row :gutter="10" class="mt-4">
            <el-col :span="8">
              <div class="stat-item-secondary">
                <div class="stat-label">平均置信度</div>
                <div class="stat-value secondary">
                  <el-progress
                    :percentage="Math.round(stats.avgConfidence * 100)"
                    :color="getConfidenceColor(stats.avgConfidence)"
                    :stroke-width="8"
                  />
                  <span class="percent-text">{{ (stats.avgConfidence * 100).toFixed(1) }}%</span>
                </div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-item-secondary">
                <div class="stat-label">节点确认率</div>
                <div class="stat-value secondary">
                  <el-progress
                    :percentage="stats.nodeConfirmationRate"
                    :stroke-width="8"
                    :status="stats.nodeConfirmationRate >= 80 ? 'success' : stats.nodeConfirmationRate >= 40 ? 'warning' : 'exception'"
                  />
                  <span class="percent-text">{{ stats.nodeConfirmationRate.toFixed(1) }}%</span>
                </div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-item-secondary">
                <div class="stat-label">关系确认率</div>
                <div class="stat-value secondary">
                  <el-progress
                    :percentage="stats.edgeConfirmationRate"
                    :stroke-width="8"
                    :status="stats.edgeConfirmationRate >= 80 ? 'success' : stats.edgeConfirmationRate >= 40 ? 'warning' : 'exception'"
                  />
                  <span class="percent-text">{{ stats.edgeConfirmationRate.toFixed(1) }}%</span>
                </div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 节点类型分布 -->
        <el-card class="type-card mt-4">
          <template #header>
            <div class="card-header">
              <span>节点类型分布</span>
            </div>
          </template>
          <div v-if="nodeTypeStats.length > 0" class="type-distribution">
            <div v-for="stat in nodeTypeStats" :key="stat.type" class="type-row">
              <div class="type-icon" :style="{ backgroundColor: stat.color + '20', color: stat.color }">
                <el-icon :size="16"><Folder /></el-icon>
              </div>
              <div class="type-name">{{ stat.displayName || stat.nodeType }}</div>
              <div class="type-progress">
                <el-progress
                  :percentage="stat.total > 0 ? Math.round(stat.confirmed / stat.total * 100) : 0"
                  :stroke-width="12"
                  :color="stat.color"
                />
              </div>
              <div class="type-stats">
                <span class="type-count">{{ stat.confirmed }}/{{ stat.total }}</span>
                <span class="type-conf" v-if="stat.averageConfidence">
                  置信度 {{ (stat.averageConfidence * 100).toFixed(0) }}%
                </span>
              </div>
            </div>
          </div>
          <div v-else class="empty-chart">暂无节点类型分布数据</div>
        </el-card>

        <!-- 置信度分布 + 数据源状态 -->
        <el-row :gutter="20" class="mt-4">
          <el-col :span="14">
            <el-card class="trend-card">
              <template #header>
                <div class="card-header">
                  <span>置信度分布</span>
                </div>
              </template>
              <div v-if="confidenceDistribution.some(b => b.count > 0)" class="chart-container">
                <div
                  v-for="(bin, index) in confidenceDistribution"
                  :key="index"
                  class="distribution-bar-item"
                >
                  <div class="bar-label">{{ bin.count }}</div>
                  <div class="bar-wrapper">
                    <div
                      class="bar"
                      :style="{ height: getBarHeight(bin.count), backgroundColor: getBarColor(bin.lower) }"
                    />
                  </div>
                  <div class="label">{{ (bin.lower * 100).toFixed(0) }}-{{ (bin.upper * 100).toFixed(0) }}</div>
                </div>
              </div>
              <div v-else class="empty-chart">暂无置信度分布数据</div>
            </el-card>
          </el-col>
          <el-col :span="10">
            <el-card class="source-card">
              <template #header>
                <div class="card-header">
                  <span>数据源状态</span>
                </div>
              </template>
              <div v-if="sourceStats.repos > 0 || sourceStats.databases > 0 || sourceStats.documents > 0" class="source-grid">
                <div class="source-item">
                  <div class="source-icon code">
                    <el-icon><FolderOpened /></el-icon>
                  </div>
                  <div class="source-info">
                    <span class="source-count">{{ sourceStats.repos }}</span>
                    <span class="source-label">代码仓库</span>
                  </div>
                </div>
                <div class="source-item">
                  <div class="source-icon db">
                    <el-icon><Coin /></el-icon>
                  </div>
                  <div class="source-info">
                    <span class="source-count">{{ sourceStats.databases }}</span>
                    <span class="source-label">数据库连接</span>
                  </div>
                </div>
                <div class="source-item">
                  <div class="source-icon doc">
                    <el-icon><Document /></el-icon>
                  </div>
                  <div class="source-info">
                    <span class="source-count">{{ sourceStats.documents }}</span>
                    <span class="source-label">文档资料</span>
                  </div>
                </div>
              </div>
              <div v-else class="empty-chart">暂无数据源</div>
            </el-card>
          </el-col>
        </el-row>
      </el-col>

      <!-- 右侧面板 -->
      <el-col :span="8">
        <!-- 当前项目信息 -->
        <el-card class="project-card">
          <template #header>
            <div class="card-header">
              <span>当前项目</span>
            </div>
          </template>
          <div v-if="currentProject" class="project-info">
            <p><strong>名称：</strong>{{ currentProject.projectName || '-' }}</p>
            <p><strong>编码：</strong>{{ currentProject.projectCode || '-' }}</p>
            <p><strong>负责人：</strong>{{ currentProject.owner || '-' }}</p>
            <p><strong>创建时间：</strong>{{ formatDate(currentProject.createdAt) }}</p>
            <el-progress
              class="mt-2"
              :percentage="getMigrationScore"
              :stroke-width="10"
              :status="getMigrationScore >= 80 ? 'success' : getMigrationScore >= 40 ? 'warning' : 'exception'"
            >
              <span>迁移就绪度</span>
            </el-progress>
          </div>
          <div v-else class="empty-state">
            <el-empty description="请先选择项目" :image-size="80" />
          </div>
          <div class="mt-4" v-if="currentProject">
            <el-button type="primary" @click="goToProjectDetail" class="full-width">进入项目详情</el-button>
          </div>
        </el-card>

        <!-- 待审核 -->
        <el-card class="todo-card mt-4">
          <template #header>
            <div class="card-header">
              <span>待审核</span>
              <el-tag v-if="pendingItems.length > 0" type="warning" size="small">{{ pendingItems.length }}</el-tag>
            </div>
          </template>
          <div v-if="pendingItems.length > 0" class="pending-list">
            <div v-for="item in pendingItems.slice(0, 5)" :key="item.id" class="pending-item">
              <el-icon :size="10" :color="getConfidenceColor(item.confidence || 0)"><CircleCheck /></el-icon>
              <span class="node-name">{{ item.nodeName || item.affectedNodeName || '未知节点' }}</span>
              <span class="confidence" :class="getConfidenceClass(item.confidence || 0)">
                {{ ((item.confidence || 0) * 100).toFixed(0) }}%
              </span>
            </div>
            <div class="more" v-if="pendingItems.length > 5">
              还有 {{ pendingItems.length - 5 }} 项待审核...
            </div>
          </div>
          <div v-else class="empty-state">
            暂无待审核项
          </div>
          <el-button v-if="pendingItems.length > 0" type="primary" link class="mt-2" @click="goToReview">
            去审核 →
          </el-button>
        </el-card>

        <!-- 快速操作 -->
        <el-card class="actions-card mt-4">
          <template #header>
            <div class="card-header">
              <span>快速操作</span>
            </div>
          </template>
          <div class="actions-grid">
            <el-button type="primary" plain @click="quickStartScan" :disabled="!currentProject">
              <el-icon><Search /></el-icon> 新扫描
            </el-button>
            <el-button type="primary" plain @click="quickGenerateReport" :disabled="!currentProject">
              <el-icon><Download /></el-icon> 迁移报告
            </el-button>
            <el-button type="primary" plain @click="quickFindMergeCandidates" :disabled="!currentProject">
              <el-icon><Link /></el-icon> 合并节点
            </el-button>
            <el-button type="primary" plain @click="quickRunTests" :disabled="!currentProject">
              <el-icon><CircleCheck /></el-icon> 执行测试
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 迁移就绪度评估 -->
    <el-row :gutter="20" class="mt-4" v-if="migrationReport">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>迁移就绪度评估</span>
              <el-button size="small" @click="refreshMigrationReport">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>
          <div class="migration-ready">
            <div class="overall-score">
              <div class="score-circle" :class="getScoreClass(migrationReport.overallScore)">
                <span class="score-value">{{ migrationReport.overallScore.toFixed(1) }}</span>
                <span class="score-label">/ 100</span>
              </div>
              <div class="score-desc">
                <p><strong>{{ getReadyDescription(migrationReport.overallScore) }}</strong></p>
              </div>
            </div>
            <div class="score-breakdown">
              <div class="breakdown-item">
                <span class="breakdown-label">架构理解</span>
                <el-progress
                  :percentage="Math.round(migrationReport.architectureUnderstandingScore || 0)"
                  :stroke-width="12"
                  :status="(migrationReport.architectureUnderstandingScore || 0) >= 80 ? 'success' : (migrationReport.architectureUnderstandingScore || 0) >= 40 ? 'warning' : 'exception'"
                />
              </div>
              <div class="breakdown-item">
                <span class="breakdown-label">业务知识覆盖</span>
                <el-progress
                  :percentage="Math.round(migrationReport.businessKnowledgeScore || 0)"
                  :stroke-width="12"
                  :status="(migrationReport.businessKnowledgeScore || 0) >= 80 ? 'success' : (migrationReport.businessKnowledgeScore || 0) >= 40 ? 'warning' : 'exception'"
                />
              </div>
              <div class="breakdown-item">
                <span class="breakdown-label">整体置信度</span>
                <el-progress
                  :percentage="Math.round(migrationReport.confidenceLevel || 0)"
                  :stroke-width="12"
                  :status="(migrationReport.confidenceLevel || 0) >= 80 ? 'success' : (migrationReport.confidenceLevel || 0) >= 40 ? 'warning' : 'exception'"
                />
              </div>
            </div>
            <div class="risk-section" v-if="migrationReport.riskItems && migrationReport.riskItems.length > 0">
              <div class="risk-header">
                <el-icon color="#f56c6c"><WarningFilled /></el-icon>
                <span>发现 {{ migrationReport.riskItems.length }} 个风险项</span>
              </div>
              <div class="risk-tags">
                <el-tag
                  v-for="risk in migrationReport.riskItems.slice(0, 5)"
                  :key="risk.affectedNodeId"
                  type="danger"
                  size="small"
                  class="risk-tag"
                >
                  {{ risk.riskType === 'LOW_CONFIDENCE' ? '低置信度' : risk.riskType === 'DISCONNECTED' ? '孤立节点' : risk.riskType }}: {{ risk.affectedNodeName }}
                </el-tag>
                <div v-if="migrationReport.riskItems.length > 5" class="mt-2 risk-more">
                  ... 还有 {{ migrationReport.riskItems.length - 5 }} 个风险
                </div>
              </div>
            </div>
          </div>
          <el-divider v-if="migrationReport.recommendations && migrationReport.recommendations.length > 0" />
          <div v-if="migrationReport.recommendations && migrationReport.recommendations.length > 0" class="recommendations">
            <h4><el-icon><Opportunity /></el-icon> 建议</h4>
            <ul>
              <li v-for="(rec, index) in migrationReport.recommendations" :key="index">{{ rec }}</li>
            </ul>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
// ⚠️ TODO F-H7: 本组件 937 行，建议拆分为子组件 + composable：
// DashboardStats / ProjectQuickActions / RecentActivity 等
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { projectApi, reportApi, sourceApi } from '@/api'
import { ElMessage } from 'element-plus'
import {
  Folder, FolderOpened, Coin, Document, CircleCheck,
  Search, Download, Link, WarningFilled, Opportunity, Refresh
} from '@element-plus/icons-vue'
import type { Project } from '@/types'

const router = useRouter()
const projectStore = useProjectStore()

const currentProject = ref<Project | null>(null)
const loadingDashboard = ref(false)

const stats = ref({
  totalNodes: 0,
  confirmedNodes: 0,
  totalEdges: 0,
  confirmedEdges: 0,
  avgConfidence: 0,
  nodeConfirmationRate: 0,
  edgeConfirmationRate: 0,
})

const nodeTypeStats = ref<any[]>([])

const confidenceDistribution = ref([
  { lower: 0.0, upper: 0.2, count: 0 },
  { lower: 0.2, upper: 0.4, count: 0 },
  { lower: 0.4, upper: 0.6, count: 0 },
  { lower: 0.6, upper: 0.8, count: 0 },
  { lower: 0.8, upper: 1.0, count: 0 },
])

const sourceStats = ref({ repos: 0, databases: 0, documents: 0 })
const pendingItems = ref<any[]>([])
const migrationReport = ref<any>(null)

const getMigrationScore = computed(() => {
  if (!migrationReport.value) return 0
  return Math.round(migrationReport.value.overallScore || 0)
})

const getConfidenceColor = (confidence: number) => {
  if (confidence < 0.5) return '#f56c6c'
  if (confidence < 0.8) return '#e6a23c'
  return '#67c23a'
}

const getBarHeight = (count: number) => {
  const max = Math.max(...confidenceDistribution.value.map(b => b.count))
  return max === 0 ? '20px' : `${Math.max(20, (count / max) * 80)}px`
}

const getBarColor = (lower: number) => {
  if (lower < 0.5) return '#f56c6c'
  if (lower < 0.8) return '#e6a23c'
  return '#67c23a'
}

const getConfidenceClass = (confidence: number) => {
  if (confidence < 0.5) return 'low'
  if (confidence < 0.8) return 'medium'
  return 'high'
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  return dateStr.split(' ')[0]
}

const getScoreClass = (score: number) => {
  if (score >= 80) return 'excellent'
  if (score >= 60) return 'good'
  if (score >= 40) return 'fair'
  return 'poor'
}

const getReadyDescription = (score: number) => {
  if (score >= 80) return '就绪，可以开始迁移'
  if (score >= 60) return '基本就绪，需要补充审核'
  if (score >= 40) return '部分就绪，需要较多工作'
  return '需要更多理解工作'
}

const goToProjectDetail = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}`)
  }
}

const goToReview = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/reviews`)
  }
}

const quickStartScan = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/scan-versions`)
  }
}

const quickGenerateReport = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/reports`)
  }
}

const quickFindMergeCandidates = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/graph/unified`)
  }
}

const quickRunTests = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/test-cases`)
  }
}

const refreshMigrationReport = async () => {
  const pid = projectStore.currentProjectId
  if (!pid) return
  try {
    const report: any = await reportApi.generateMigrationReport(pid)
    migrationReport.value = report
    ElMessage.success('迁移报告已刷新')
  } catch {
    // ignore
  }
}

async function loadDashboard() {
  const pid = projectStore.currentProjectId
  if (!pid) return

  loadingDashboard.value = true
  try {
    const [project, overview] = await Promise.all([
      projectApi.detail(pid).catch(() => null) as any,
      projectApi.overview(pid).catch(() => null),
    ])
    currentProject.value = project as any

    // 统计数据
    if (overview) {
      const gs = overview.graphStats || overview
      stats.value = {
        totalNodes: gs.totalNodes || 0,
        confirmedNodes: gs.confirmedNodes || gs.approvedCount || 0,
        totalEdges: gs.totalEdges || 0,
        confirmedEdges: gs.confirmedEdges || 0,
        avgConfidence: gs.avgConfidence || 0,
        nodeConfirmationRate: gs.nodeConfirmationRate || 0,
        edgeConfirmationRate: gs.edgeConfirmationRate || 0,
      }

      // 节点类型分布
      if (overview.nodeTypeStats || overview.nodeTypeDistribution) {
        const raw = overview.nodeTypeStats || overview.nodeTypeDistribution || []
        const colors = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#722ed1', '#13c2c2', '#eb2f96']
        nodeTypeStats.value = raw.map((s: any, idx: number) => ({
          ...s,
          color: s.color || colors[idx % colors.length],
        }))
      }

      // 置信度分布
      if (overview.confidenceDistribution) {
        confidenceDistribution.value = overview.confidenceDistribution.map((b: any) => ({
          lower: b.lower || b.rangeStart || 0,
          upper: b.upper || b.rangeEnd || 1,
          count: b.count || 0,
        }))
      }

      // 待审核
      if (overview.pendingReviewItems) {
        pendingItems.value = overview.pendingReviewItems
      }
    }

    // 数据源统计
    try {
      const [repos, dbs, docs] = await Promise.all([
        sourceApi.listCodeRepo(pid, { pageNum: 1, pageSize: 1 }),
        sourceApi.listDbConnections(pid, { pageNum: 1, pageSize: 1 }),
        sourceApi.listDocuments(pid, { pageNum: 1, pageSize: 1 }),
      ])
      sourceStats.value = {
        repos: (repos as any)?.total || (repos as any)?.length || 0,
        databases: (dbs as any)?.total || (dbs as any)?.length || 0,
        documents: (docs as any)?.total || (docs as any)?.length || 0,
      }
    } catch {
      // 数据源接口可能不可用
    }

    // 加载迁移报告
    reportApi.generateMigrationReport(pid).then((report: any) => {
      migrationReport.value = report
    }).catch(() => {})
  } catch (err) {
    console.error('获取项目概览失败:', err)
  } finally {
    loadingDashboard.value = false
  }
}

onMounted(loadDashboard)
</script>

<style scoped>
.dashboard-container {
  padding: 20px;
}

.stats-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.stat-item {
  text-align: center;
  padding: 16px 8px;
  background: #f5f7fa;
  border-radius: 8px;
}

.stat-number {
  font-size: 28px;
  font-weight: bold;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.stat-item-secondary {
  padding: 8px;
}

.percent-text {
  font-size: 12px;
  margin-left: 8px;
  color: #606266;
}

/* 节点类型分布 */
.type-distribution {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.type-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.type-icon {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.type-name {
  width: 80px;
  font-size: 13px;
  color: #303133;
  flex-shrink: 0;
}

.type-progress {
  flex: 1;
}

.type-stats {
  width: 160px;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #606266;
  flex-shrink: 0;
}

.type-count {
  font-weight: 500;
  color: #303133;
}

.type-conf {
  color: #909399;
}

/* 置信度分布 */
.chart-container {
  display: flex;
  align-items: end;
  height: 140px;
  gap: 16px;
  padding: 10px 0;
}

.distribution-bar-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.bar-label {
  font-size: 11px;
  color: #606266;
  margin-bottom: 4px;
}

.bar-wrapper {
  width: 100%;
  height: 100px;
  display: flex;
  align-items: end;
  justify-content: center;
}

.bar {
  width: 28px;
  min-height: 4px;
  border-radius: 4px 4px 0 0;
  transition: height 0.3s;
}

.label {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}

/* 数据源状态 */
.source-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #f5f7fa;
}

.source-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #fff;
}

.source-icon.code { background: linear-gradient(135deg, #667eea, #764ba2); }
.source-icon.db { background: linear-gradient(135deg, #43e97b, #38f9d7); }
.source-icon.doc { background: linear-gradient(135deg, #fa709a, #fee140); }

.source-info {
  display: flex;
  flex-direction: column;
}

.source-count {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.source-label {
  font-size: 12px;
  color: #909399;
}

/* 项目信息 */
.project-info p {
  margin: 8px 0;
  color: #606266;
  font-size: 14px;
}

.empty-state {
  text-align: center;
  padding: 10px;
  color: #909399;
}

/* 待审核 */
.pending-list {
  max-height: 220px;
  overflow-y: auto;
}

.pending-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.node-name {
  flex: 1;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.confidence {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  flex-shrink: 0;
}

.confidence.low { background: #fef0f0; color: #f56c6c; }
.confidence.medium { background: #fdf6ec; color: #e6a23c; }
.confidence.high { background: #f0f9eb; color: #67c23a; }

.more {
  text-align: center;
  padding: 8px;
  font-size: 12px;
  color: #909399;
}

/* 快速操作 */
.actions-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.actions-grid .el-button {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  box-sizing: border-box;
}

/* 迁移报告 */
.migration-ready {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 24px;
}

.overall-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.score-circle {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 8px solid;
  margin-bottom: 16px;
}

.score-circle.excellent { border-color: #67c23a; background: #f0f9eb; }
.score-circle.good { border-color: #e6a23c; background: #fdf6ec; }
.score-circle.fair { border-color: #409eff; background: #ecf5ff; }
.score-circle.poor { border-color: #f56c6c; background: #fef0f0; }

.score-value { font-size: 36px; font-weight: bold; line-height: 1; }
.score-label { font-size: 16px; color: #909399; }

.score-breakdown {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.breakdown-item {
  display: flex;
  align-items: center;
  gap: 16px;
}

.breakdown-label {
  width: 120px;
  font-size: 14px;
  color: #606266;
  flex-shrink: 0;
}

.breakdown-item .el-progress { flex: 1; }

.risk-section {
  margin-top: 16px;
  grid-column: 1 / -1;
}

.risk-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #f56c6c;
  margin-bottom: 8px;
}

.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.risk-more {
  font-size: 12px;
  color: #909399;
}

.recommendations {
  margin-top: 8px;
}

.recommendations h4 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  margin: 0 0 12px 0;
  color: #303133;
}

.recommendations ul {
  padding-left: 20px;
}

.recommendations li {
  margin-bottom: 8px;
  color: #606266;
  line-height: 1.5;
}

.empty-chart {
  text-align: center;
  padding: 30px;
  color: #c0c4cc;
  font-size: 14px;
}

.full-width { width: 100%; }
.mt-2 { margin-top: 8px; }
.mt-4 { margin-top: 16px; }
</style>
