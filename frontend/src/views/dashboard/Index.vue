<template>
  <div class="dashboard-container">
    <el-row :gutter="20">
      <!-- 左侧统计卡片 -->
      <el-col :span="16">
        <el-card class="stats-card">
          <template #header>
            <div class="card-header">
              <span>项目总体统计</span>
            </div>
          </template>
          <el-row :gutter="10">
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number">{{ stats.totalNodes }}</div>
                <div class="stat-label">图谱节点</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number">{{ stats.confirmedNodes }}</div>
                <div class="stat-label">已确认</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number">{{ stats.totalEdges }}</div>
                <div class="stat-label">关系连接</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-number">{{ stats.confirmedEdges }}</div>
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
                  />
                  <span class="percent-text">{{ stats.edgeConfirmationRate.toFixed(1) }}%</span>
                </div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 置信度趋势 -->
        <el-card class="trend-card mt-4">
          <template #header>
            <div class="card-header">
              <span>置信度分布</span>
            </div>
          </template>
          <div class="chart-container">
            <div
              v-for="(bin, index) in confidenceDistribution"
              :key="index"
              class="distribution-bar-item"
            >
              <div class="bar-wrapper">
                <div
                  class="bar"
                  :style="{ height: getBarHeight(bin.count), backgroundColor: getBarColor(bin.lower) }"
                />
              </div>
              <div class="label">{{ (bin.lower * 100).toFixed(0) }}-{{ (bin.upper * 100).toFixed(0) }}</div>
              <div class="count">{{ bin.count }}</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧信息和操作 -->
      <el-col :span="8">
        <!-- 当前项目信息 -->
        <el-card class="project-card">
          <template #header>
            <div class="card-header">
              <span>当前项目</span>
            </div>
          </template>
          <div v-if="currentProject" class="project-info">
            <p><strong>名称：</strong>{{ currentProject.projectName }}</p>
            <p><strong>编码：</strong>{{ currentProject.projectCode }}</p>
            <p><strong>负责人：</strong>{{ currentProject.owner || '-' }}</p>
            <p><strong>创建时间：</strong>{{ formatDate(currentProject.createdAt) }}</p>
          </div>
          <div v-else class="empty-state">
            请先选择项目
          </div>
          <div class="mt-4" v-if="currentProject">
            <el-button type="primary" @click="goToProjectDetail" block>进入项目详情</el-button>
          </div>
        </el-card>

        <!-- 待办事项 -->
        <el-card class="todo-card mt-4">
          <template #header>
            <div class="card-header">
              <span>待审核</span>
            </div>
          </template>
          <div v-if="pendingItems.length > 0" class="pending-list">
            <div v-for="item in pendingItems.slice(0, 5)" :key="item.id" class="pending-item">
              <span class="node-name">{{ item.nodeName }}</span>
              <span class="confidence" :class="getConfidenceClass(item.confidence)">
                {{ (item.confidence * 100).toFixed(0) }}%
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
              开始新扫描
            </el-button>
            <el-button type="primary" plain @click="quickGenerateReport" :disabled="!currentProject">
              生成迁移报告
            </el-button>
            <el-button type="primary" plain @click="quickFindMergeCandidates" :disabled="!currentProject">
              查找重复节点
            </el-button>
            <el-button type="primary" plain @click="quickRunTests" :disabled="!currentProject">
              执行测试
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 迁移就绪度卡片 -->
    <el-row :gutter="20" class="mt-4" v-if="migrationReport">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>迁移就绪度评估</span>
            </div>
          </template>
          <div class="migration-ready">
            <div class="overall-score">
              <div class="score-circle" :class="getScoreClass(migrationReport.overallScore)">
                <span class="score-value">{{ migrationReport.overallScore.toFixed(1) }}</span>
                <span class="score-label">/ 100</span>
              </div>
              <div class="score-desc">
                <p><strong>整体就绪度：{{ getReadyDescription(migrationReport.overallScore) }}</strong></p>
              </div>
            </div>
            <div class="score-breakdown">
              <div class="breakdown-item">
                <span class="breakdown-label">架构理解</span>
                <el-progress :percentage="migrationReport.architectureUnderstandingScore" :stroke-width="10" />
              </div>
              <div class="breakdown-item">
                <span class="breakdown-label">业务知识覆盖</span>
                <el-progress :percentage="migrationReport.businessKnowledgeScore" :stroke-width="10" />
              </div>
              <div class="breakdown-item">
                <span class="breakdown-label">整体置信度</span>
                <el-progress :percentage="migrationReport.confidenceLevel" :stroke-width="10" />
              </div>
            </div>
            <div v-if="migrationReport.riskItems && migrationReport.riskItems.length > 0" class="risks">
              <h4>发现 {{ migrationReport.riskItems.length }} 个风险项</h4>
              <el-tag
                v-for="risk in migrationReport.riskItems.slice(0, 5)"
                :key="risk.affectedNodeId"
                type="danger"
                class="mr-2 mb-2"
              >
                {{ risk.description }} ({{ risk.affectedNodeName }})
              </el-tag>
              <div v-if="migrationReport.riskItems.length > 5" class="mt-2 text-gray">
                ... 还有 {{ migrationReport.riskItems.length - 5 }} 个风险
              </div>
            </div>
            <div v-if="migrationReport.recommendations && migrationReport.recommendations.length > 0" class="recommendations">
              <h4>建议</h4>
              <ul>
                <li v-for="(rec, index) in migrationReport.recommendations" :key="index">
                  {{ rec }}
                </li>
              </ul>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import { projectApi } from '@/api'
import type { Project } from '@/types'

const router = useRouter()
const projectStore = useProjectStore()
const userStore = useUserStore()

const currentProject = ref<Project | null>(null)
const stats = ref({
  totalNodes: 0,
  confirmedNodes: 0,
  totalEdges: 0,
  confirmedEdges: 0,
  avgConfidence: 0,
  nodeConfirmationRate: 0,
  edgeConfirmationRate: 0,
})

const confidenceDistribution = ref([
  { lower: 0.0, upper: 0.2, count: 0 },
  { lower: 0.2, upper: 0.4, count: 0 },
  { lower: 0.4, upper: 0.6, count: 0 },
  { lower: 0.6, upper: 0.8, count: 0 },
  { lower: 0.8, upper: 1.0, count: 0 },
])

const pendingItems = ref<any[]>([])
const migrationReport = ref<any>(null)

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
  // TODO: 跳转到图谱合并页面
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/graph/unified`)
  }
}

const quickRunTests = () => {
  if (currentProject.value) {
    router.push(`/projects/${currentProject.value.id}/test-cases`)
  }
}

onMounted(() => {
  const projectId = projectStore.currentProjectId
  if (projectId) {
    projectApi.detail(projectId).then(res => {
      currentProject.value = res
      // TODO: 加载统计数据从后端API
      // 暂时显示占位数据
    })
  }
})
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
  color: #409eff;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.stat-item-secondary {
  padding: 8px;
}

.stat-label {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}

.percent-text {
  font-size: 12px;
  margin-left: 8px;
  color: #606266;
}

.project-info p {
  margin: 8px 0;
  color: #606266;
}

.empty-state {
  text-align: center;
  padding: 20px;
  color: #909399;
}

.pending-list {
  max-height: 200px;
  overflow-y: auto;
}

.pending-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.node-name {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 70%;
}

.confidence {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.confidence.low {
  background: #fef0f0;
  color: #f56c6c;
}

.confidence.medium {
  background: #fdf6ec;
  color: #e6a23c;
}

.confidence.high {
  background: #f0f9eb;
  color: #67c23a;
}

.actions-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.chart-container {
  display: flex;
  align-items: end;
  height: 120px;
  gap: 16px;
  padding: 10px 0;
}

.distribution-bar-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.bar-wrapper {
  width: 100%;
  height: 80px;
  display: flex;
  align-items: end;
  justify-content: center;
}

.bar {
  width: 24px;
  min-height: 4px;
  border-radius: 4px 4px 0 0;
}

.label {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}

.count {
  font-size: 11px;
  color: #606266;
  margin-top: 2px;
}

.migration-ready {
  display: grid;
  grid-template-columns: 300px 1fr;
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
  align-items: center;
  justify-content: center;
  border: 8px solid;
  margin-bottom: 16px;
}

.score-circle.excellent {
  border-color: #67c23a;
  background: #f0f9eb;
}

.score-circle.good {
  border-color: #e6a23c;
  background: #fdf6ec;
}

.score-circle.fair {
  border-color: #409eff;
  background: #ecf5ff;
}

.score-circle.poor {
  border-color: #f56c6c;
  background: #fef0f0;
}

.score-value {
  font-size: 36px;
  font-weight: bold;
  line-height: 1;
}

.score-label {
  font-size: 16px;
  color: #909399;
}

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
}

.breakdown-item .el-progress {
  flex: 1;
}

.risks {
  margin-top: 16px;
}

.recommendations {
  margin-top: 16px;
}

.recommendations ul {
  padding-left: 20px;
}

.recommendations li {
  margin-bottom: 8px;
  color: #606266;
}

.mr-2 {
  margin-right: 8px;
}

.mb-2 {
  margin-bottom: 8px;
}

.mt-2 {
  margin-top: 8px;
}

.mt-4 {
  margin-top: 16px;
}

.text-gray {
  color: #909399;
}
</style>
