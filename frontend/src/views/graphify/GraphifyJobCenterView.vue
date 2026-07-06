<template>
  <div class="graphify-job-center">
    <div class="page-header">
      <h3>
        <el-icon><Operation /></el-icon>
        Graphify 作业中心
      </h3>
      <p class="header-desc">管理图谱导入作业，支持重试和回滚操作</p>
    </div>

    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>导入作业列表</span>
          <el-button
            type="primary"
            size="small"
            :loading="loading"
            @click="loadJobs">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="jobs"
        border
        stripe
        empty-text="暂无导入作业">
        <el-table-column prop="id" label="作业ID" width="200" show-overflow-tooltip />
        <el-table-column prop="versionId" label="版本ID" width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="progress" label="进度" width="160">
          <template #default="{ row }">
            <el-progress
              v-if="row.status === 'RUNNING'"
              :percentage="row.progress || 0"
              :stroke-width="14"
            />
            <span v-else-if="row.status === 'SUCCESS'" class="text-success">100%</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="nodeCount" label="节点数" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.nodeCount">{{ row.nodeCount }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="edgeCount" label="边数" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.edgeCount">{{ row.edgeCount }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip>
          <template #default="{ row }">
            <span
              v-if="row.errorMessage"
              class="text-danger">
              {{ row.errorMessage }}
            </span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="170">
          <template #default="{ row }">
            <span v-if="row.startedAt">{{ formatTime(row.startedAt) }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="finishedAt" label="完成时间" width="170">
          <template #default="{ row }">
            <span v-if="row.finishedAt">{{ formatTime(row.finishedAt) }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED'"
              type="warning"
              link
              size="small"
              :loading="row._retryLoading"
              @click="handleRetry(row)">
              重试
            </el-button>
            <el-button
              v-if="row.status === 'SUCCESS'"
              type="danger"
              link
              size="small"
              :loading="row._rollbackLoading"
              @click="handleRollback(row)">
              回滚
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Operation, Refresh } from '@element-plus/icons-vue'
import { graphifyApi } from '@/api/graphify.api'
import type { GraphifyJob } from '@/api/graphify.api'
import dayjs from 'dayjs'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const jobs = ref<(GraphifyJob & { _retryLoading?: boolean; _rollbackLoading?: boolean })[]>([])

function formatTime(time: string): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    PENDING: 'info',
    RUNNING: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger',
    ROLLED_BACK: 'info',
  }
  return map[status] || 'info'
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待执行',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    ROLLED_BACK: '已回滚',
  }
  return map[status] || status
}

async function loadJobs() {
  loading.value = true
  try {
    const res: any = await graphifyApi.getJobs(projectId)
    jobs.value = (res?.list || res?.data?.list || (Array.isArray(res) ? res : [])).map((j: GraphifyJob) => ({
      ...j,
      _retryLoading: false,
      _rollbackLoading: false,
    }))
  } catch (err) {
    console.error('加载作业列表失败:', err)
    ElMessage.error('加载作业列表失败')
  } finally {
    loading.value = false
  }
}

async function handleRetry(job: any) {
  try {
    await ElMessageBox.confirm(`确定重试作业 ${job.id} 吗？`, '确认重试', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    job._retryLoading = true
    await graphifyApi.retryJob(projectId, job.id)
    ElMessage.success('作业已重新提交')
    await loadJobs()
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('重试失败: ' + (err.message || '未知错误'))
    }
  } finally {
    job._retryLoading = false
  }
}

async function handleRollback(job: any) {
  try {
    await ElMessageBox.confirm(
      `确定回滚作业 ${job.id} 吗？回滚将移除该次导入的所有节点和边。`,
      '确认回滚',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    job._rollbackLoading = true
    await graphifyApi.rollbackJob(projectId, job.id)
    ElMessage.success('回滚成功')
    await loadJobs()
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('回滚失败: ' + (err.message || '未知错误'))
    }
  } finally {
    job._rollbackLoading = false
  }
}

onMounted(() => {
  loadJobs()
})
</script>

<style scoped>
.graphify-job-center {
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
}

.text-gray {
  color: #c0c4cc;
}
</style>
