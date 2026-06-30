<template>
  <div class="scan-version-list">
    <div class="page-header">
      <h3>{{ t('menu.scanVersions') }}</h3>
      <el-button type="primary" @click="goToCreate">
        <el-icon><Plus /></el-icon>
        新建扫描
      </el-button>
    </div>

    <el-table :data="versionList" v-loading="loading" border stripe>
      <el-table-column prop="versionNumber" label="版本号" width="100" />
      <el-table-column prop="versionName" label="版本名称" min-width="180" />
      <el-table-column prop="scanType" label="扫描类型" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ getScanTypeText(row.scanType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="200">
        <template #default="{ row }">
          <div class="progress-wrapper">
            <el-progress
              :percentage="row.progress || 0"
              :status="row.status === 'FAILED' ? 'exception' : (row.progress === 100 ? 'success' : undefined)"
              :stroke-width="14"
            />
            <span v-if="row.taskCount > 0" class="progress-text">
              {{ row.completedTaskCount }}/{{ row.taskCount }} 任务
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="stage" label="当前阶段" width="140">
        <template #default="{ row }">
          <span v-if="row.stage && row.stage !== '-'" class="stage-text">
            <el-icon class="is-loading" v-if="row.status === 'RUNNING'"><Loading /></el-icon>
            {{ getStageText(row.stage) }}
          </span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="nodeCount" label="节点数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.nodeCount" size="small" type="primary">{{ row.nodeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="edgeCount" label="关系数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.edgeCount" size="small" type="warning">{{ row.edgeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="factCount" label="事实数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.factCount" size="small" type="success">{{ row.factCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="duration" label="耗时" width="100">
        <template #default="{ row }">
          <span v-if="row.duration">{{ formatDuration(row.duration) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170">
        <template #default="{ row }">
          <span v-if="row.createdAt">{{ formatTime(row.createdAt) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
          <el-button type="warning" link size="small" @click="compareWithPrevious(row)">对比</el-button>
          <el-button v-if="row.status === 'RUNNING'" type="danger" link size="small" @click="stopScan(row)">停止</el-button>
          <el-button type="danger" link size="small" @click="deleteVersion(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper" v-if="total > 0">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="() => loadVersionList(1)"
      />
    </div>

    <el-empty v-if="versionList.length === 0 && !loading" description="暂无扫描版本" />

    <!-- 版本详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="版本详情" width="700px" append-to-body>
      <el-descriptions :column="2" border v-if="currentVersion">
        <el-descriptions-item label="版本号">{{ currentVersion.versionNumber }}</el-descriptions-item>
        <el-descriptions-item label="版本名称">{{ currentVersion.versionName }}</el-descriptions-item>
        <el-descriptions-item label="扫描类型">{{ getScanTypeText(currentVersion.scanType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="getStatusType(currentVersion.status)">{{ getStatusText(currentVersion.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          <el-progress :percentage="currentVersion.progress || 0" :stroke-width="14" style="width: 200px;" />
        </el-descriptions-item>
        <el-descriptions-item label="任务进度">
          {{ currentVersion.completedTaskCount || 0 }} / {{ currentVersion.taskCount || 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="节点数">{{ currentVersion.nodeCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="关系数">{{ currentVersion.edgeCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="事实数">{{ currentVersion.factCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ currentVersion.duration ? formatDuration(currentVersion.duration) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(currentVersion.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ currentVersion.createdBy || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="goToGraph(currentVersion)">查看图谱</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Loading } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { t } from '@/locales'
import { get, del, post } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const versionList = ref<any[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const detailDialogVisible = ref(false)
const currentVersion = ref<any>(null)

// 轮询定时器
let pollTimer: ReturnType<typeof setInterval> | null = null
const POLL_INTERVAL = 3000 // 3秒轮询一次

// 是否有运行中的任务
const hasRunningTasks = computed(() =>
  versionList.value.some(v => v.status === 'RUNNING')
)

const formatTime = (time: string) => {
  if (!time) return '-'
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const formatDuration = (seconds: number) => {
  if (!seconds || seconds <= 0) return '-'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

const getScanTypeText = (type: string) => {
  if (!type) return '-'
  // type可能是JSON字符串，尝试提取可读信息
  try {
    const parsed = JSON.parse(type)
    if (parsed.scanTypes && Array.isArray(parsed.scanTypes)) {
      const map: Record<string, string> = {
        CODE_SCAN: '代码扫描',
        DB_SCAN: '数据库扫描',
        DOC_PARSE: '文档解析',
        GRAPH_BUILD: '图谱构建',
        TEST_GENERATE: '测试生成'
      }
      return parsed.scanTypes.map((t: string) => map[t] || t).join('+') || '全量扫描'
    }
  } catch {}
  return type.length > 30 ? '全量扫描' : type
}

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    CREATED: 'info',
    PENDING: 'info',
    RUNNING: 'warning',
    PROCESSING: 'warning',
    SUCCESS: 'success',
    COMPLETED: 'success',
    FAILED: 'danger',
    CANCELLED: 'info',
    PAUSED: 'warning'
  }
  return map[status] || 'info'
}

const getStatusText = (status: string): string => {
  const map: Record<string, string> = {
    CREATED: '已创建',
    PENDING: '等待中',
    RUNNING: '运行中',
    PROCESSING: '处理中',
    SUCCESS: '已完成',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    PAUSED: '已暂停'
  }
  return map[status] || status || '-'
}

const getStageText = (stage: string): string => {
  const map: Record<string, string> = {
    CODE_SCAN: '代码扫描中',
    DB_SCAN: '数据库扫描中',
    DOC_PARSE: '文档解析中',
    GRAPH_BUILD: '图谱构建中',
    TEST_GENERATE: '测试生成中',
    COMPLETED: '已完成'
  }
  return map[stage] || stage
}

const goToCreate = () => {
  router.push(`/projects/${projectId}/scan-versions/create`)
}

const viewDetail = (row: any) => {
  currentVersion.value = row
  detailDialogVisible.value = true
}

const compareWithPrevious = (row: any) => {
  detailDialogVisible.value = false
  router.push(`/projects/${projectId}/graph/unified?versionId=${row.id}`)
}

const stopScan = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定停止扫描「${row.versionName}」吗？`, '提示', {
      confirmButtonText: '确定停止',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await post(`/lg/projects/${projectId}/scan-versions/${row.id}/cancel`)
    ElMessage.success('扫描已停止')
    await loadVersionList()
  } catch {
    // cancelled
  }
}

const deleteVersion = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除版本 ${row.versionNumber} - ${row.versionName} 吗？此操作不可恢复。`, '提示', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await del(`/lg/projects/${projectId}/scan-versions/${row.id}`)
    ElMessage.success('版本已删除')
    await loadVersionList()
  } catch {
    // cancelled
  }
}

const goToGraph = (version: any) => {
  detailDialogVisible.value = false
  router.push(`/projects/${projectId}/graph/unified?versionId=${version.id}`)
}

const loadVersionList = async (page?: number) => {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const res = await get(`/lg/projects/${projectId}/scan-versions`, {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    const list = res.list || []
    total.value = res.total || 0
    versionList.value = list.map((v: any) => ({
      id: v.id,
      versionNumber: v.versionNumber || v.versionNo || '-',
      versionName: v.versionName || v.versionNo || '未知版本',
      scanType: v.scanType || v.scanScope || '-',
      status: v.scanStatus || v.status || 'CREATED',
      progress: v.progress != null ? v.progress : (v.scanStatus === 'SUCCESS' || v.scanStatus === 'COMPLETED' ? 100 : 0),
      stage: v.stage || '-',
      taskCount: v.taskCount || 0,
      completedTaskCount: v.completedTaskCount || 0,
      nodeCount: v.nodeCount || 0,
      edgeCount: v.edgeCount || 0,
      factCount: v.factCount || 0,
      duration: v.duration || 0,
      createdAt: v.createdAt,
      createdBy: v.createdBy || '-'
    }))

    // 管理轮询：有运行中任务则启动，否则停止
    managePolling()
  } catch (err) {
    console.error('获取扫描版本列表失败:', err)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadVersionList(page)
}

// 轮询管理
const startPolling = () => {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    loadVersionList()
  }, POLL_INTERVAL)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const managePolling = () => {
  if (hasRunningTasks.value) {
    startPolling()
  } else {
    stopPolling()
  }
}

onMounted(async () => {
  await loadVersionList()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.scan-version-list {
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

.text-gray {
  color: #909399;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
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

.stage-text {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}
</style>
