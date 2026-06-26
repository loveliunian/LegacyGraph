<template>
  <div class="scan-task-list">
    <div class="page-header">
      <h3>扫描任务</h3>
      <el-button type="primary" @click="goToCreate">
        <el-icon><Plus /></el-icon>
        新建扫描
      </el-button>
    </div>

    <el-table :data="taskList" v-loading="loading" border stripe>
      <el-table-column prop="taskName" label="任务名称" width="200" />
      <el-table-column prop="taskType" label="任务类型" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ getTaskTypeText(row.taskType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stage" label="当前阶段" width="150" />
      <el-table-column prop="progress" label="进度" width="150">
        <template #default="{ row }">
          <el-progress :percentage="row.progress" :status="row.status === 'FAILED' ? 'exception' : undefined" />
        </template>
      </el-table-column>
      <el-table-column prop="factCount" label="事实数" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.factCount" size="small" type="success">{{ row.factCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="nodeCount" label="节点数" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.nodeCount" size="small" type="primary">{{ row.nodeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="edgeCount" label="关系数" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.edgeCount" size="small" type="warning">{{ row.edgeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="startTime" label="开始时间" width="180">
        <template #default="{ row }">
          <span v-if="row.startTime">{{ formatTime(row.startTime) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="duration" label="耗时" width="100">
        <template #default="{ row }">
          <span v-if="row.duration">{{ formatDuration(row.duration) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="120" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewLogs(row)">查看日志</el-button>
          <el-button v-if="row.status === 'RUNNING'" type="danger" link size="small" @click="stopTask(row)">停止</el-button>
          <el-button v-if="row.status === 'FAILED'" type="warning" link size="small" @click="retryTask(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="taskList.length === 0" description="暂无扫描任务" />

    <el-dialog v-model="logDialogVisible" title="任务日志" width="900px" append-to-body>
      <div class="log-container">
        <div v-for="(log, index) in logs" :key="index" class="log-item">
          <span class="log-time">{{ log.time }}</span>
          <el-tag :type="log.type === 'ERROR' ? 'danger' : 'info'" size="small">{{ log.type }}</el-tag>
          <span class="log-message">{{ log.message }}</span>
        </div>
        <el-empty v-if="logs.length === 0" description="暂无日志" />
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const taskList = ref<any[]>([])
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

const getTaskTypeText = (type: string) => {
  const map: Record<string, string> = {
    CODE_SCAN: '代码扫描',
    DB_SCAN: '数据库扫描',
    DOC_PARSE: '文档解析',
    GRAPH_BUILD: '图谱构建',
    TEST_GENERATE: '测试生成'
  }
  return map[type] || type
}

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
  router.push(`/projects/${projectId}/scans/create`)
}

const viewLogs = (row: any) => {
  logDialogVisible.value = true
  logs.value = [
    { time: '2024-01-15 10:30:00', type: 'INFO', message: '任务开始执行...' },
    { time: '2024-01-15 10:30:05', type: 'INFO', message: '开始拉取代码仓库...' },
    { time: '2024-01-15 10:30:15', type: 'INFO', message: '代码拉取完成，开始解析 Java 文件...' },
    { time: '2024-01-15 10:30:45', type: 'INFO', message: '解析 Controller 完成，发现 24 个 API 端点' },
    { time: '2024-01-15 10:31:00', type: 'INFO', message: '解析 Service 完成，发现 56 个 Service 方法' },
    { time: '2024-01-15 10:31:30', type: 'INFO', message: '解析 Mapper 和 SQL 完成，发现 89 条 SQL 语句' },
    { time: '2024-01-15 10:32:00', type: 'INFO', message: '开始构建图谱节点和关系...' },
    { time: '2024-01-15 10:32:30', type: 'INFO', message: '图谱构建完成，共生成 168 个节点，324 个关系' },
    { time: '2024-01-15 10:32:35', type: 'INFO', message: '任务执行完成' }
  ]
}

const stopTask = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定停止任务 ${row.taskName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
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
    row.status = 'RUNNING'
    row.progress = 0
    ElMessage.success('任务已重新启动')
  } catch {
    // cancelled
  }
}

onMounted(async () => {
  loading.value = true
  setTimeout(() => {
    taskList.value = [
      {
        id: '1',
        taskName: '全量代码扫描',
        taskType: 'CODE_SCAN',
        status: 'SUCCESS',
        stage: '完成',
        progress: 100,
        factCount: 256,
        nodeCount: 168,
        edgeCount: 324,
        startTime: new Date(Date.now() - 86400000).toISOString(),
        duration: 155,
        createdBy: '张三'
      },
      {
        id: '2',
        taskName: '数据库结构扫描',
        taskType: 'DB_SCAN',
        status: 'SUCCESS',
        stage: '完成',
        progress: 100,
        factCount: 89,
        nodeCount: 56,
        edgeCount: 142,
        startTime: new Date(Date.now() - 86400000 * 2).toISOString(),
        duration: 45,
        createdBy: '李四'
      },
      {
        id: '3',
        taskName: '产品文档解析',
        taskType: 'DOC_PARSE',
        status: 'RUNNING',
        stage: '解析业务规则',
        progress: 65,
        factCount: 45,
        nodeCount: 0,
        edgeCount: 0,
        startTime: new Date(Date.now() - 120000).toISOString(),
        duration: 120,
        createdBy: '王五'
      },
      {
        id: '4',
        taskName: '测试用例生成',
        taskType: 'TEST_GENERATE',
        status: 'PENDING',
        stage: '等待执行',
        progress: 0,
        factCount: 0,
        nodeCount: 0,
        edgeCount: 0,
        startTime: null,
        duration: 0,
        createdBy: '张三'
      }
    ]
    loading.value = false
  }, 500)
})
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
</style>
