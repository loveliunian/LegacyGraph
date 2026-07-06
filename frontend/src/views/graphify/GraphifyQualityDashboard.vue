<template>
  <div class="graphify-quality-dashboard">
    <div class="page-header">
      <h3>
        <el-icon><TrendCharts /></el-icon>
        Graphify 质量仪表盘
      </h3>
      <p class="header-desc">展示 Benchmark 结果和 Release Gate 状态</p>
    </div>

    <!-- 总体评分 -->
    <el-row :gutter="16" class="overview-row">
      <el-col :span="6">
        <div class="stat-card" :class="{ success: quality?.releaseGatePassed, danger: quality && !quality.releaseGatePassed }">
          <div class="stat-label">Release Gate</div>
          <div class="stat-value">
            <el-icon v-if="quality?.releaseGatePassed" class="icon-success"><CircleCheckFilled /></el-icon>
            <el-icon v-else-if="quality" class="icon-danger"><CircleCloseFilled /></el-icon>
            <span v-else>-</span>
          </div>
          <div class="stat-sub">
            {{ quality?.releaseGatePassed ? '通过' : (quality ? '未通过' : '-') }}
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">总体评分</div>
          <div class="stat-value">{{ quality ? quality.overallScore.toFixed(1) : '-' }}</div>
          <div class="stat-sub">/ 100</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">节点覆盖率</div>
          <div class="stat-value">{{ quality ? (quality.nodeCoverage * 100).toFixed(1) : '-' }}%</div>
          <el-progress
            v-if="quality"
            :percentage="quality.nodeCoverage * 100"
            :show-text="false"
            :color="getProgressColor(quality.nodeCoverage)"
          />
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">边覆盖率</div>
          <div class="stat-value">{{ quality ? (quality.edgeCoverage * 100).toFixed(1) : '-' }}%</div>
          <el-progress
            v-if="quality"
            :percentage="quality.edgeCoverage * 100"
            :show-text="false"
            :color="getProgressColor(quality.edgeCoverage)"
          />
        </div>
      </el-col>
    </el-row>

    <!-- Release Gate 失败原因 -->
    <el-alert
      v-if="quality && !quality.releaseGatePassed && quality.releaseGateReason"
      :title="'Release Gate 未通过: ' + quality.releaseGateReason"
      type="error"
      show-icon
      style="margin-top: 16px"
    />

    <!-- Benchmark 详情 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>Benchmark 测试结果</span>
          <el-button
            type="primary"
            size="small"
            :loading="loading"
            @click="loadQuality">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="quality?.benchmarkResults || []"
        border
        stripe
        empty-text="暂无 Benchmark 数据">
        <el-table-column prop="name" label="Benchmark" min-width="200" />
        <el-table-column prop="passed" label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
              {{ row.passed ? '通过' : '未通过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="得分" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ 'text-success': row.passed, 'text-danger': !row.passed }">
              {{ row.score.toFixed(2) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="threshold" label="阈值" width="100" align="center">
          <template #default="{ row }">
            {{ row.threshold.toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min((row.score / row.threshold) * 100, 100)"
              :color="row.passed ? '#67c23a' : '#f56c6c'"
              :stroke-width="12"
            />
          </template>
        </el-table-column>
        <el-table-column prop="details" label="详情" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.details">{{ row.details }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-empty
      v-if="!quality && !loading"
      description="请选择版本查看质量数据" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { TrendCharts, Refresh, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { graphifyApi } from '@/api/graphify.api'
import type { GraphifyQualityResult } from '@/api/graphify.api'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const quality = ref<GraphifyQualityResult | null>(null)

function getProgressColor(ratio: number): string {
  if (ratio >= 0.8) return '#67c23a'
  if (ratio >= 0.6) return '#e6a23c'
  return '#f56c6c'
}

async function loadQuality() {
  loading.value = true
  try {
    const res: any = await graphifyApi.getQuality(projectId)
    quality.value = res
  } catch (err) {
    console.error('加载质量数据失败:', err)
    ElMessage.error('加载质量数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadQuality()
})
</script>

<style scoped>
.graphify-quality-dashboard {
  padding: 16px;
}

.page-header {
  margin-bottom: 16px;
}

.page-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.header-desc {
  margin: 0;
  font-size: 14px;
  color: #909399;
}

.overview-row {
  margin-bottom: 0;
}

.stat-card {
  padding: 20px;
  border-radius: 8px;
  text-align: center;
  border: 1px solid #ebeef5;
  background: #fff;
}

.stat-card.success {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.stat-card.danger {
  border-color: #fab6b6;
  background: #fef0f0;
}

.stat-label {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  color: #303133;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.stat-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.icon-success {
  color: #67c23a;
  font-size: 36px;
}

.icon-danger {
  color: #f56c6c;
  font-size: 36px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.text-success {
  color: #67c23a;
  font-weight: 600;
}

.text-danger {
  color: #f56c6c;
  font-weight: 600;
}

.text-gray {
  color: #c0c4cc;
}
</style>
