<template>
  <div class="project-overview">
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon code">
              <el-icon><FolderOpened /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview?.sourceStatus?.repos?.configured || 0 }}</div>
              <div class="stat-label">代码仓库</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon db">
        <el-icon><Coin /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview?.sourceStatus?.databases?.configured || 0 }}</div>
              <div class="stat-label">数据库连接</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon doc">
              <el-icon><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ overview?.sourceStatus?.documents?.uploaded || 0 }}</div>
              <div class="stat-label">文档资料</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon graph">
              <el-icon><Connection /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ graphStats?.totalNodes || 0 }}</div>
              <div class="stat-label">图谱节点</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="content-row">
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <span>资料接入状态</span>
              <el-button type="primary" link size="small" @click="goToSources">查看全部</el-button>
            </div>
          </template>
          <div class="source-status">
            <div class="status-item">
              <div class="status-icon success">
                <el-icon v-if="overview?.sourceStatus?.repos?.configured"><Check /></el-icon>
                <el-icon v-else><Close /></el-icon>
              </div>
              <div class="status-info">
                <div class="status-label">代码仓库</div>
                <div class="status-desc">
                  {{ overview?.sourceStatus?.repos?.configured || 0 }} 个已配置
                </div>
              </div>
            </div>
            <div class="status-item">
              <div class="status-icon success">
                <el-icon v-if="overview?.sourceStatus?.databases?.configured"><Check /></el-icon>
                <el-icon v-else><Close /></el-icon>
              </div>
              <div class="status-info">
                <div class="status-label">数据库连接</div>
                <div class="status-desc">
                  {{ overview?.sourceStatus?.databases?.configured || 0 }} 个已配置
                </div>
              </div>
            </div>
            <div class="status-item">
              <div class="status-icon" :class="overview?.sourceStatus?.documents?.uploaded ? 'success' : 'warning'">
                <el-icon v-if="overview?.sourceStatus?.documents?.uploaded"><Check /></el-icon>
                <el-icon v-else><Warning /></el-icon>
              </div>
              <div class="status-info">
                <div class="status-label">文档资料</div>
                <div class="status-desc">
                  {{ overview?.sourceStatus?.documents?.uploaded || 0 }} 个已上传
                </div>
              </div>
            </div>
            <div class="status-item">
              <div class="status-icon" :class="overview?.sourceStatus?.testEnv?.configured ? 'success' : 'info'">
                <el-icon v-if="overview?.sourceStatus?.testEnv?.configured"><Check /></el-icon>
                <el-icon v-else><QuestionFilled /></el-icon>
              </div>
              <div class="status-info">
                <div class="status-label">测试环境</div>
                <div class="status-desc">
                  {{ overview?.sourceStatus?.testEnv?.configured ? '已配置' : '未配置' }}
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <span>图谱构建状态</span>
              <el-button type="primary" link size="small" @click="goToGraphs">查看图谱</el-button>
            </div>
          </template>
          <div v-if="graphStats" class="graph-status">
            <div class="graph-stat-item">
              <span class="stat-label">总节点数</span>
              <span class="stat-value">{{ graphStats.totalNodes }}</span>
            </div>
            <div class="graph-stat-item">
              <span class="stat-label">总关系数</span>
              <span class="stat-value">{{ graphStats.totalEdges }}</span>
            </div>
            <div class="graph-stat-item">
              <span class="stat-label">平均置信度</span>
              <span class="stat-value">{{ (graphStats.avgConfidence * 100).toFixed(1) }}%</span>
            </div>
            <div class="graph-stat-item">
              <span class="stat-label">已验证节点</span>
              <span class="stat-value success">{{ graphStats.approvedCount }}</span>
            </div>
            <div class="graph-stat-item">
              <span class="stat-label">待审核节点</span>
              <span class="stat-value warning">{{ graphStats.pendingCount }}</span>
            </div>
            <div class="graph-stat-item">
              <span class="stat-label">有证据节点</span>
              <span class="stat-value">{{ graphStats.withEvidenceCount }}</span>
            </div>
          </div>
          <el-empty v-else description="暂无图谱数据" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="content-row">
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <span>最近扫描任务</span>
              <el-button type="primary" link size="small" @click="goToScans">查看全部</el-button>
            </div>
          </template>
          <el-timeline v-if="recentScans && recentScans.length > 0">
            <el-timeline-item
              v-for="scan in recentScans"
              :key="scan.id"
              :timestamp="formatTime(scan.createdAt)"
              :type="getScanStatusType(scan.status)"
            >
              <div class="scan-item">
                <div class="scan-name">{{ scan.taskName }}</div>
                <div class="scan-type">{{ scan.taskType }}</div>
                <el-tag size="small" :type="getScanStatusType(scan.status)">
                  {{ scan.status }}
                </el-tag>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无扫描任务" />
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <span>最近审核记录</span>
              <el-button type="primary" link size="small" @click="goToReviews">查看全部</el-button>
            </div>
          </template>
          <el-timeline v-if="recentReviews && recentReviews.length > 0">
            <el-timeline-item
              v-for="review in recentReviews"
              :key="review.id"
              :timestamp="formatTime(review.reviewedAt || review.createdAt)"
              :type="getReviewStatusType(review.status)"
            >
              <div class="review-item">
                <div class="review-target">{{ review.targetName }}</div>
                <div class="review-type">{{ review.targetType }}</div>
                <el-tag size="small" :type="getReviewStatusType(review.status)">
                  {{ review.status }}
                </el-tag>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无审核记录" />
        </el-card>
      </el-col>
    </el-row>

    <div class="action-bar">
      <el-button type="primary" size="large" @click="startNewScan">
        <el-icon><VideoPlay /></el-icon>
        开始扫描
      </el-button>
      <el-button size="large" @click="goToGraphs">
        <el-icon><Connection /></el-icon>
        查看图谱
      </el-button>
      <el-button size="large" @click="generateTestCases">
        <el-icon><DocumentChecked /></el-icon>
        生成测试用例
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  FolderOpened,
  Coin,
  Document,
  Connection,
  Check,
  Close,
  Warning,
  QuestionFilled,
  VideoPlay,
  DocumentChecked
} from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const router = useRouter()
const route = useRoute()

const projectId = route.params.projectId as string

const overview = ref<any>(null)
const graphStats = ref<any>(null)
const recentScans = ref<any[]>([])
const recentReviews = ref<any[]>([])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm')
}

const getScanStatusType = (status: string): string => {
  const map: Record<string, string> = {
    SUCCESS: 'success',
    FAILED: 'danger',
    RUNNING: 'warning',
    PENDING: 'info',
    CANCELED: 'info'
  }
  return map[status] || 'info'
}

const getReviewStatusType = (status: string): string => {
  const map: Record<string, string> = {
    APPROVED: 'success',
    REJECTED: 'danger',
    PENDING: 'warning',
    CONFIRMED: 'success'
  }
  return map[status] || 'info'
}

const goToSources = () => {
  router.push(`/projects/${projectId}/sources/repos`)
}

const goToGraphs = () => {
  router.push(`/projects/${projectId}/graphs/code`)
}

const goToScans = () => {
  router.push(`/projects/${projectId}/scans`)
}

const goToReviews = () => {
  router.push(`/projects/${projectId}/reviews`)
}

const startNewScan = () => {
  router.push(`/projects/${projectId}/scans/create`)
}

const generateTestCases = () => {
  ElMessage.info('测试用例生成功能开发中')
}

onMounted(async () => {
  overview.value = {
    sourceStatus: {
      repos: { configured: 2, scanned: 2, failed: 0 },
      databases: { configured: 1, scanned: 1, failed: 0 },
      documents: { uploaded: 3, parsed: 3, failed: 0 },
      testEnv: { configured: true, available: true }
    }
  }

  graphStats.value = {
    totalNodes: 1256,
    totalEdges: 3428,
    avgConfidence: 0.87,
    approvedCount: 892,
    pendingCount: 364,
    withEvidenceCount: 1021
  }

  recentScans.value = [
    {
      id: '1',
      taskName: '全量代码扫描',
      taskType: 'CODE_SCAN',
      status: 'SUCCESS',
      createdAt: new Date().toISOString()
    },
    {
      id: '2',
      taskName: '数据库元数据提取',
      taskType: 'DB_SCAN',
      status: 'SUCCESS',
      createdAt: new Date(Date.now() - 86400000).toISOString()
    }
  ]

  recentReviews.value = [
    {
      id: '1',
      targetName: 'UserController.getUser',
      targetType: 'NODE',
      status: 'APPROVED',
      reviewedAt: new Date().toISOString()
    },
    {
      id: '2',
      targetName: 'OrderService.createOrder -> DB:order',
      targetType: 'EDGE',
      status: 'PENDING',
      createdAt: new Date(Date.now() - 3600000).toISOString()
    }
  ]
})
</script>

<style scoped>
.project-overview {
  padding: 0;
}

.stats-row {
  margin-bottom: 24px;
}

.stat-card {
  height: 100%;
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: #fff;
}

.stat-icon.code {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.stat-icon.db {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.stat-icon.doc {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.stat-icon.graph {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.content-row {
  margin-bottom: 24px;
}

.section-card {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.source-status {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
}

.status-icon.success {
  background: #67c23a;
}

.status-icon.warning {
  background: #e6a23c;
}

.status-icon.info {
  background: #909399;
}

.status-info {
  flex: 1;
}

.status-label {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 2px;
}

.status-desc {
  font-size: 12px;
  color: #909399;
}

.graph-status {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.graph-stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.graph-stat-item .stat-label {
  font-size: 13px;
  color: #606266;
}

.graph-stat-item .stat-value {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.graph-stat-item .stat-value.success {
  color: #67c23a;
}

.graph-stat-item .stat-value.warning {
  color: #e6a23c;
}

.scan-item,
.review-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.scan-name,
.review-target {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.scan-type,
.review-type {
  font-size: 12px;
  color: #909399;
}

.action-bar {
  display: flex;
  gap: 12px;
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  justify-content: center;
}
</style>
