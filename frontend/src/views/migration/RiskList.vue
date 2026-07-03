<template>
  <div class="migration-risk-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>迁移风险检测</span>
          <el-button
            type="primary"
            @click="refreshDetection">
            <el-icon><Refresh /></el-icon>
            重新检测
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="overallScore !== null"
        :title="'整体迁移就绪度得分: ' + overallScore.toFixed(1) + '/100'"
        :type="getOverallScoreType(overallScore)"
        show-icon
        class="overall-alert"
      />

      <!-- 统计信息 -->
      <el-row
        :gutter="20"
        class="stats-row">
        <el-col :span="6">
          <el-card
            shadow="hover"
            class="stat-card">
            <div class="stat-value">{{ stats.totalNodes }}</div>
            <div class="stat-label">总节点数</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card
            shadow="hover"
            class="stat-card">
            <div class="stat-value">{{ stats.confirmedNodes }}</div>
            <div class="stat-label">已确认节点</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card
            shadow="hover"
            class="stat-card">
            <div class="stat-value">{{ stats.pendingNodes }}</div>
            <div class="stat-label">待确认节点</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card
            shadow="hover"
            class="stat-card">
            <div class="stat-value">{{ stats.risks }}</div>
            <div class="stat-label">风险项数量</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 风险列表 -->
      <div class="table-header">
        <span class="table-title">风险列表</span>
        <el-button
          v-if="overallScore !== null"
          type="primary"
          size="small"
          @click="exportReport">
          <el-icon><Download /></el-icon>
          导出报告
        </el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="pagedRisks"
        border
        style="width: 100%"
      >
        <el-table-column
          prop="riskType"
          label="风险类型"
          width="150">
          <template #default="{ row }">
            <el-tag :type="getRiskTypeTag(row.riskType)">
              {{ getRiskTypeName(row.riskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="affectedNodeName"
          label="影响节点"
          min-width="200" />
        <el-table-column
          prop="description"
          label="描述"
          min-width="250" />
        <el-table-column
          prop="riskLevel"
          label="风险等级"
          width="100"
          align="center">
          <template #default="{ row }">
            <el-progress
              :percentage="row.riskLevel * 100"
              :color="getRiskLevelColor(row.riskLevel)"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              @click="goToNode(row.affectedNodeId)">
              查看节点
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="risks.length > pageSize"
        v-model:current-page="currentPage"
        class="pagination"
        :page-size="pageSize"
        :total="risks.length"
        layout="total, prev, pager, next"
        background
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { reportApi } from '@/api'
import { Download, Refresh } from '@element-plus/icons-vue'
import type { MigrationReadinessReport, RiskItem } from '@/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const projectStore = useProjectStore()

const loading = ref(false)
const report = ref<MigrationReadinessReport | null>(null)
const overallScore = ref<number | null>(null)
const risks = ref<RiskItem[]>([])

const currentPage = ref(1)
const pageSize = ref(10)

const stats = computed(() => ({
  totalNodes: report.value?.totalNodes || 0,
  confirmedNodes: report.value?.confirmedNodes || 0,
  pendingNodes: report.value?.pendingNodes || 0,
  risks: report.value?.riskItems?.length || 0,
}))

const pagedRisks = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return risks.value.slice(start, start + pageSize.value)
})

function getRiskTypeTag(riskType: string) {
  switch (riskType) {
    case 'LOW_CONFIDENCE': return 'warning'
    case 'DISCONNECTED': return 'danger'
    default: return 'info'
  }
}

function getRiskTypeName(riskType: string) {
  switch (riskType) {
    case 'LOW_CONFIDENCE': return '低置信度'
    case 'DISCONNECTED': return '孤立节点'
    default: return riskType
  }
}

function getRiskLevelColor(riskLevel: number) {
  if (riskLevel >= 0.7) return '#f56c6c'
  if (riskLevel >= 0.4) return '#e6a23c'
  return '#67c23a'
}

function getOverallScoreType(score: number) {
  if (score >= 80) return 'success'
  if (score >= 60) return 'warning'
  return 'error'
}

async function refreshDetection() {
  const projectId = projectStore.currentProjectId as string
  loading.value = true
  try {
    const data = await reportApi.generateMigrationReport(projectId)
    report.value = data
    overallScore.value = data.overallScore
    risks.value = data.riskItems || []
    currentPage.value = 1
    ElMessage.success('风险检测完成')
  } catch (error) {
    console.error(error)
    ElMessage.error('检测失败')
  } finally {
    loading.value = false
  }
}

function goToNode(nodeId: string) {
  const pid = projectStore.currentProjectId as string
  router.push(`/projects/${pid}/graph/code?nodeId=${nodeId}`)
}

function exportReport() {
  const projectId = projectStore.currentProjectId as string
  reportApi.generateMigrationReport(projectId)
    .then(data => {
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `migration-report-${projectId}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      ElMessage.success('报告导出成功')
    })
    .catch(error => {
      console.error(error)
      ElMessage.error('导出失败')
    })
}

onMounted(() => {
  refreshDetection()
})
</script>

<style scoped>
.migration-risk-list {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.overall-alert {
  margin-bottom: 20px;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
  line-height: 1.2;
  margin-bottom: 8px;
}

.stat-label {
  color: #909399;
  font-size: 14px;
}

.stat-card:hover {
  transform: translateY(-5px);
  transition: transform 0.3s;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.table-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
