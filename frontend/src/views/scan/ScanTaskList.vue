<template>
  <div class="scan-task-list">
    <div class="page-header">
      <h3>扫描任务</h3>
      <el-button
        type="primary"
        @click="goToCreate">
        <el-icon><Plus /></el-icon>
        新建扫描
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="taskList"
      border
      stripe>
      <el-table-column
        prop="taskName"
        label="任务名称"
        width="200" />
      <el-table-column
        prop="taskType"
        label="任务类型"
        width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ getTaskTypeText(row.taskType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="status"
        label="状态"
        width="100">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="stage"
        label="当前阶段"
        width="150" />
      <el-table-column
        prop="progress"
        label="进度"
        width="180">
        <template #default="{ row }">
          <div class="progress-wrapper">
            <el-progress
              :percentage="row.progress"
              :status="row.status === 'FAILED' ? 'exception' : (row.progress === 100 ? 'success' : undefined)"
              :stroke-width="16"
            />
            <span
              v-if="row.taskCount > 0"
              class="progress-text">{{ row.completedTaskCount }}/{{ row.taskCount }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="factCount"
        label="事实数"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.factCount"
            size="small"
            type="success">
            {{ row.factCount }}
          </el-tag>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="nodeCount"
        label="节点数"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.nodeCount"
            size="small"
            type="primary">
            {{ row.nodeCount }}
          </el-tag>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="edgeCount"
        label="关系数"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.edgeCount"
            size="small"
            type="warning">
            {{ row.edgeCount }}
          </el-tag>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="startTime"
        label="开始时间"
        width="180">
        <template #default="{ row }">
          <span v-if="row.startTime">{{ formatTime(row.startTime) }}</span>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="duration"
        label="耗时"
        width="100">
        <template #default="{ row }">
          <span v-if="row.duration">{{ formatDuration(row.duration) }}</span>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="createdBy"
        label="创建人"
        width="120" />
      <el-table-column
        label="操作"
        width="200"
        fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="viewLogs(row)">
            查看日志
          </el-button>
          <el-button
            v-if="row.status === 'RUNNING'"
            type="danger"
            link
            size="small"
            @click="stopTask(row)">
            停止
          </el-button>
          <el-button
            v-if="row.status === 'FAILED'"
            type="warning"
            link
            size="small"
            @click="retryTask(row)">
            重试
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div
      v-if="total > 0"
      class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="() => loadTaskList(1)"
      />
    </div>

    <el-empty
      v-if="taskList.length === 0"
      description="暂无扫描任务" />

    <el-dialog
      v-model="logDialogVisible"
      title="任务日志"
      width="900px"
      append-to-body>
      <div class="log-container">
        <div
          v-for="(log, index) in logs"
          :key="index"
          class="log-item">
          <span class="log-time">{{ log.time }}</span>
          <el-tag
            :type="log.type === 'ERROR' ? 'danger' : 'info'"
            size="small">
            {{ log.type }}
          </el-tag>
          <span class="log-message">{{ log.message }}</span>
        </div>
        <el-empty
          v-if="logs.length === 0"
          description="暂无日志" />
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// ✅ F-H1: get(...logs) → scanApi.getLogs

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { scanApi, graphApi } from '@/api'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const taskList = ref<any[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const logDialogVisible = ref(false)
const logs = ref<any[]>([])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const formatDuration = (seconds: number) => {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

const getTaskTypeText = (type: string) => dictLabel('scan_type', type)

const getStageText = (stage: string) => dictLabel('scan_stage', stage)

const getStatusText = (status: string) => dictLabel('scan_status', status)

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    PENDING: 'info',
    RUNNING: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger',
    CANCELED: 'info'
  }
  return map[status] || 'info'
}

const goToCreate = () => {
  router.push(`/projects/${projectId}/scan-versions/create`)
}

const viewLogs = async (row: any) => {
  logDialogVisible.value = true
  logs.value = []
  try {
    const logData = await scanApi.getLogs(projectId, row.versionId)
    if (Array.isArray(logData)) {
      logs.value = logData
    }
  } catch (err) {
    // 日志为空时静默处理
  }
}

const stopTask = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定停止任务 ${row.taskName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await scanApi.cancel(projectId, row.versionId)
    row.status = 'CANCELED'
    ElMessage.success('任务已停止')
  } catch {
    // cancelled
  }
}

const retryTask = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定重试任务 ${row.taskName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await scanApi.resume(projectId, row.versionId)
    row.status = 'PENDING'
    row.progress = 0
    ElMessage.success('任务已重新提交')
    // 刷新列表
    loadTaskList()
  } catch {
    // cancelled
  }
}

onMounted(() => {
  preloadDicts(['scan_type', 'scan_stage', 'scan_status'])
  loadTaskList()
})

async function loadTaskList(page?: number) {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const res = await graphApi.getScanVersions(projectId)
    const list = Array.isArray(res) ? res : (res.list || [])
    total.value = res.total || list.length
    taskList.value = list.map((v: any) => ({
      id: v.id,
      versionId: v.id,
      taskName: v.versionName || v.versionNo || '未知任务',
      taskType: v.scanType || 'CODE_SCAN',
      status: (v.scanStatus === 'COMPLETED' || v.scanStatus === 'SUCCESS') ? 'SUCCESS' :
              (v.scanStatus === 'FAILED') ? 'FAILED' :
              (v.scanStatus === 'RUNNING') ? 'RUNNING' :
              (v.scanStatus || 'PENDING'),
      stage: getStageText(v.stage || '-'),
      progress: v.progress != null ? v.progress : (v.scanStatus === 'SUCCESS' || v.scanStatus === 'COMPLETED' ? 100 : 0),
      taskCount: v.taskCount || 0,
      completedTaskCount: v.completedTaskCount || 0,
      factCount: v.factCount || 0,
      nodeCount: v.nodeCount || 0,
      edgeCount: v.edgeCount || 0,
      startTime: v.startedAt || v.createdAt,
      duration: v.duration || 0,
      createdBy: v.createdBy || '-'
    }))
  } catch (err) {
    console.error('获取扫描任务列表失败:', err)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadTaskList(page)
}
</script>

<style scoped>
.scan-task-list {
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

.log-container {
  max-height: 500px;
  overflow-y: auto;
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
}

.log-item {
  display: flex;
  gap: 8px;
  padding: 4px 0;
  font-family: monospace;
  font-size: 13px;
  line-height: 1.5;
}

.log-time {
  color: #909399;
  flex-shrink: 0;
}

.log-message {
  color: #303133;
  flex: 1;
}

.text-gray {
  color: #909399;
}

.progress-wrapper {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.progress-wrapper .progress-text {
  font-size: 11px;
  color: #909399;
  text-align: right;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
