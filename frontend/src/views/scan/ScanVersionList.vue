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
      <!-- 版本号 + 版本名称合并为一列 -->
      <el-table-column label="版本信息" min-width="200">
        <template #default="{ row }">
          <div class="version-info">
            <span class="version-no" :title="row.versionNumber">{{ truncateText(row.versionNumber, 20) }}</span>
            <span class="version-name" :title="row.versionName">{{ truncateText(row.versionName, 24) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="扫描类型" width="160">
        <template #default="{ row }">
          <div class="scan-types">
            <el-tag
              v-for="t in parseScanTypes(row.scanType)"
              :key="t"
              size="small"
              type="primary"
              class="scan-type-tag"
            >{{ dictLabel('scan_type', t) }}</el-tag>
            <span v-if="parseScanTypes(row.scanType).length === 0" class="text-gray">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="170">
        <template #default="{ row }">
          <div class="progress-wrapper">
            <el-progress
              :percentage="row.progress || 0"
              :status="row.status === 'FAILED' ? 'exception' : (row.progress === 100 ? 'success' : undefined)"
              :stroke-width="12"
            />
            <span v-if="row.taskCount > 0" class="progress-text">
              {{ row.completedTaskCount }}/{{ row.taskCount }} 任务
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="当前阶段" width="120">
        <template #default="{ row }">
          <span v-if="row.stage && row.stage !== '-'" class="stage-text">
            <el-icon class="is-loading" v-if="row.status === 'RUNNING'"><Loading /></el-icon>
            <el-tag size="small" :type="getStageTagType(row.stage)">{{ getStageText(row.stage) }}</el-tag>
          </span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="图谱统计" width="200">
        <template #default="{ row }">
          <div class="graph-stats">
            <span class="stat-item" v-if="row.nodeCount">
              <span class="stat-val">{{ row.nodeCount }}</span>
              <span class="stat-lbl">节点</span>
            </span>
            <span class="stat-item" v-if="row.edgeCount">
              <span class="stat-val">{{ row.edgeCount }}</span>
              <span class="stat-lbl">关系</span>
            </span>
            <span class="stat-item" v-if="row.factCount">
              <span class="stat-val">{{ row.factCount }}</span>
              <span class="stat-lbl">事实</span>
            </span>
            <span v-if="!row.nodeCount && !row.edgeCount && !row.factCount" class="text-gray">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.duration" class="duration-text">{{ formatDuration(row.duration) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">
          <span v-if="row.createdAt" class="time-text">{{ formatTime(row.createdAt) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
          <el-button type="primary" link size="small" @click="compareWithPrevious(row)">对比</el-button>
          <el-button v-if="row.status === 'RUNNING'" type="warning" link size="small" @click="pauseScan(row)">暂停</el-button>
          <el-button v-if="row.status === 'PAUSED'" type="success" link size="small" @click="resumeScan(row)">恢复</el-button>
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
        <el-descriptions-item label="扫描类型">
          <el-tag
            v-for="t in parseScanTypes(currentVersion.scanType)"
            :key="t"
            size="small"
            type="primary"
            style="margin-right: 4px;"
          >{{ dictLabel('scan_type', t) }}</el-tag>
          <span v-if="parseScanTypes(currentVersion.scanType).length === 0">-</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="getStatusType(currentVersion.status)">{{ getStatusText(currentVersion.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前阶段">
          <el-tag v-if="currentVersion.stage && currentVersion.stage !== '-'" size="small" :type="getStageTagType(currentVersion.stage)">
            {{ getStageText(currentVersion.stage) }}
          </el-tag>
          <span v-else>-</span>
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
import { preloadDicts, dictLabel } from '@/utils/dict'

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

let pollTimer: ReturnType<typeof setInterval> | null = null
const POLL_INTERVAL = 3000

const hasRunningTasks = computed(() =>
  versionList.value.some(v => v.status === 'RUNNING')
)

// 文本截断，超出长度加 ...
const truncateText = (text: string, maxLen: number): string => {
  if (!text) return '-'
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

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

// 解析 scanType JSON，返回扫描类型数组
const parseScanTypes = (scanType: string): string[] => {
  if (!scanType) return []
  try {
    const parsed = JSON.parse(scanType)
    if (parsed.scanTypes && Array.isArray(parsed.scanTypes)) {
      return parsed.scanTypes
    }
  } catch {}
  // 非 JSON 格式：可能是逗号分隔或单个值
  if (scanType.includes(',')) {
    return scanType.split(',').map(t => t.trim()).filter(Boolean)
  }
  // 单个值（如 CODE_SCAN、FULL_SCAN）
  if (scanType && scanType.length < 30) {
    return [scanType]
  }
  return []
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

const getStatusText = (status: string): string => dictLabel('scan_status', status)

const getStageText = (stage: string): string => dictLabel('scan_stage', stage)

// 当前阶段的 tag 类型
const getStageTagType = (stage: string): string => {
  if (stage === 'COMPLETED') return 'success'
  return '' // 默认色
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

const pauseScan = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定暂停「${row.versionName}」吗？`, '提示', {
      confirmButtonText: '确定暂停',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await post(`/lg/projects/${projectId}/scan-versions/${row.id}/pause`)
    ElMessage.success('扫描已暂停')
    await loadVersionList()
  } catch {
    // cancelled
  }
}

const resumeScan = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定恢复「${row.versionName}」吗？`, '提示', {
      confirmButtonText: '确定恢复',
      cancelButtonText: '取消',
      type: 'info'
    })
    await post(`/lg/projects/${projectId}/scan-versions/${row.id}/resume`)
    ElMessage.success('扫描已恢复')
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
  preloadDicts(['scan_type', 'scan_status', 'scan_stage'])
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

/* 版本信息：号 + 名上下排列 */
.version-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.version-no {
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
}

.version-name {
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 扫描类型标签组 */
.scan-types {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.scan-type-tag {
  margin: 0;
}

/* 进度条 */
.progress-wrapper {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.progress-wrapper .progress-text {
  font-size: 11px;
  color: #909399;
  text-align: right;
}

/* 当前阶段 */
.stage-text {
  display: flex;
  align-items: center;
  gap: 5px;
}

/* 图谱统计：内联紧凑排列 */
.graph-stats {
  display: flex;
  gap: 12px;
  align-items: center;
}

.stat-item {
  display: flex;
  align-items: baseline;
  gap: 3px;
}

.stat-val {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.stat-lbl {
  font-size: 11px;
  color: #909399;
}

.duration-text {
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  color: #606266;
}

.time-text {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
