<template>
  <div class="scan-version-list">
    <div class="page-header">
      <h3>{{ t('menu.scanVersions') }}</h3>
      <el-button type="primary" @click="goToCreate">
        <el-icon><Plus /></el-icon>
        新建扫描
      </el-button>
    </div>

    <el-table :data="versionList" v-loading="loading && !hasLoadedOnce" border stripe>
      <!-- 版本号 + 版本名称合并为一列 -->
      <el-table-column label="版本信息" min-width="200">
        <template #default="{ row }">
          <div class="version-info">
            <span class="version-no" :title="row.versionNumber">{{ truncateText(row.versionNumber, 20) }}</span>
            <span class="version-name" :title="row.versionName">{{ truncateText(row.versionName, 24) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="扫描类型" width="130">
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
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <span class="status-dot" :class="'status-' + row.status?.toLowerCase()" />
          <span class="status-text">{{ getStatusText(row.status) }}</span>
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
      <el-table-column label="当前阶段" width="110">
        <template #default="{ row }">
          <div class="stage-cell" v-if="row.stage && row.stage !== '-'">
            <span class="stage-indicator" :class="{ 'is-active': row.status === 'RUNNING' }" />
            <span class="stage-label">{{ getStageText(row.stage) }}</span>
          </div>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="图谱统计" width="110">
        <template #default="{ row }">
          <div class="graph-stats" v-if="row.nodeCount || row.edgeCount || row.factCount">
            <span class="stat-line" v-if="row.nodeCount">
              <em class="stat-num">{{ row.nodeCount }}</em> 节点
            </span>
            <span class="stat-line" v-if="row.edgeCount">
              <em class="stat-num">{{ row.edgeCount }}</em> 关系
            </span>
            <span class="stat-line" v-if="row.factCount">
              <em class="stat-num">{{ row.factCount }}</em> 事实
            </span>
          </div>
          <span v-else class="text-gray">-</span>
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
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <div class="action-buttons">
            <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
            <el-button type="primary" link size="small" @click="compareWithPrevious(row)">对比</el-button>
            <el-button v-if="row.status === 'RUNNING'" type="warning" link size="small" @click="pauseScan(row)">暂停</el-button>
            <el-button v-if="row.status === 'PAUSED'" type="success" link size="small" @click="resumeScan(row)">恢复</el-button>
            <el-button v-if="row.status === 'RUNNING'" type="danger" link size="small" @click="stopScan(row)">停止</el-button>
            <el-button type="danger" link size="small" @click="deleteVersion(row)">删除</el-button>
          </div>
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
    <el-dialog v-model="detailDialogVisible" title="版本详情" width="720px" append-to-body @opened="startDetailPolling" @closed="stopDetailPolling">
      <template v-if="currentVersion">
        <!-- 基本信息 -->
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px;">
          <el-descriptions-item label="版本号">{{ currentVersion.versionNumber }}</el-descriptions-item>
          <el-descriptions-item label="版本名称">{{ currentVersion.versionName }}</el-descriptions-item>
          <el-descriptions-item label="扫描类型">
            <el-tag v-for="t in parseScanTypes(currentVersion.scanType)" :key="t" size="small" type="primary" style="margin-right: 4px;">
              {{ dictLabel('scan_type', t) }}
            </el-tag>
            <span v-if="parseScanTypes(currentVersion.scanType).length === 0">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="getStatusType(currentVersion.status)">{{ getStatusText(currentVersion.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="图谱节点">{{ currentVersion.nodeCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="图谱关系">{{ currentVersion.edgeCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="图谱事实">{{ currentVersion.factCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(currentVersion.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 扫描环节进度 -->
        <div class="scan-phases">
          <div class="phases-header">
            <span class="phases-title">扫描环节</span>
            <span v-if="detailProgress" class="phases-eta">
              整体进度 {{ detailProgress.progress || 0 }}%
              <template v-if="detailProgress.estimatedSecondsRemaining && detailProgress.estimatedSecondsRemaining > 0">
                · 预计剩余 {{ formatDuration(detailProgress.estimatedSecondsRemaining) }}
              </template>
            </span>
          </div>
          <div class="phase-list">
            <div
              v-for="(phase, idx) in (detailProgress?.tasks || [])"
              :key="phase.taskType"
              class="phase-item"
              :class="{
                'phase-running': phase.status === 'RUNNING',
                'phase-success': phase.status === 'SUCCESS',
                'phase-warning': phase.status === 'WARNING',
                'phase-failed': phase.status === 'FAILED',
                'phase-pending': phase.status === 'PENDING',
                'phase-skipped': phase.status === 'SKIPPED'
              }"
            >
              <!-- 阶段序号+状态图标 -->
              <div class="phase-icon">
                <el-icon v-if="phase.status === 'SUCCESS'" class="icon-success"><CircleCheckFilled /></el-icon>
                <el-icon v-else-if="phase.status === 'WARNING'" class="icon-warning"><WarningFilled /></el-icon>
                <el-icon v-else-if="phase.status === 'FAILED'" class="icon-fail"><CircleCloseFilled /></el-icon>
                <el-icon v-else-if="phase.status === 'RUNNING'" class="icon-running"><Loading /></el-icon>
                <span v-else class="phase-num">{{ idx + 1 }}</span>
              </div>
              <!-- 阶段信息 -->
              <div class="phase-body">
                <div class="phase-top">
                  <span class="phase-name">{{ phase.phaseName || phase.taskType }}</span>
                  <span class="phase-status-text">{{ dictLabel('scan_status', phase.status) }}</span>
                </div>
                <!-- 进度条（RUNNING/SUCCESS/WARNING 阶段显示） -->
                <div v-if="phase.totalItems && phase.totalItems > 0 && (phase.status === 'RUNNING' || phase.status === 'SUCCESS' || phase.status === 'WARNING')" class="phase-progress-row">
                  <el-progress
                    :percentage="phase.totalItems > 0 ? Math.round((phase.processedItems || 0) * 100 / phase.totalItems) : 0"
                    :stroke-width="6"
                    :status="phase.status === 'FAILED' ? 'exception' : undefined"
                    :color="phase.status === 'SUCCESS' ? '#67c23a' : '#409eff'"
                  />
                  <span class="phase-counts">{{ phase.processedItems || 0 }} / {{ phase.totalItems }} 项</span>
                </div>
                <!-- 当前处理项名称 -->
                <div v-if="phase.currentItem && phase.status === 'RUNNING'" class="phase-current-item">
                  <el-icon><Document /></el-icon>
                  {{ phase.currentItem }}
                </div>
                <div v-if="phase.startedAt || phase.finishedAt" class="phase-time-row">
                  <span>开始 {{ formatTime(phase.startedAt) }}</span>
                  <span>结束 {{ formatTime(phase.finishedAt) }}</span>
                  <span>耗时 {{ formatPhaseDuration(phase.startedAt, phase.finishedAt) }}</span>
                </div>
                <!-- 预估剩余时间 -->
                <div v-if="phase.estimatedSecondsRemaining && phase.estimatedSecondsRemaining > 0 && phase.status === 'RUNNING'" class="phase-eta">
                  预计剩余 {{ formatDuration(phase.estimatedSecondsRemaining) }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
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
import { Plus, Loading, CircleCheckFilled, CircleCloseFilled, WarningFilled, Document } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { t } from '@/locales'
import { scanApi } from '@/api'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const hasLoadedOnce = ref(false)
const versionList = ref<any[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const detailDialogVisible = ref(false)
const currentVersion = ref<any>(null)
const detailProgress = ref<any>(null)  // 增强的进度响应
let detailPollTimer: ReturnType<typeof setInterval> | null = null
const DETAIL_POLL_INTERVAL = 2000  // 详情进度轮询间隔 2s

let pollTimer: ReturnType<typeof setInterval> | null = null
const POLL_INTERVAL = 100000

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

const formatPhaseDuration = (startedAt?: string, finishedAt?: string) => {
  if (!startedAt || !finishedAt) return '-'
  const seconds = dayjs(finishedAt).diff(dayjs(startedAt), 'second')
  return formatDuration(seconds)
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
    WARNING: 'warning',
    SKIPPED: 'info',
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

const goToCreate = () => {
  router.push(`/projects/${projectId}/scan-versions/create`)
}

const viewDetail = (row: any) => {
  currentVersion.value = row
  detailProgress.value = null
  detailDialogVisible.value = true
}

const loadDetailProgress = async () => {
  if (!currentVersion.value) return
  try {
    const res = await scanApi.progress(projectId, currentVersion.value.id)
    detailProgress.value = res
    // 终态时停止轮询
    const termStatuses = ['SUCCESS', 'FAILED', 'CANCELLED', 'COMPLETED']
    if (res.status && termStatuses.includes(res.status)) {
      stopDetailPolling()
    }
  } catch {
    // 静默
  }
}

const startDetailPolling = () => {
  stopDetailPolling()
  if (!currentVersion.value) return
  loadDetailProgress()
  detailPollTimer = setInterval(loadDetailProgress, DETAIL_POLL_INTERVAL)
}

const stopDetailPolling = () => {
  if (detailPollTimer) {
    clearInterval(detailPollTimer)
    detailPollTimer = null
  }
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
    await scanApi.cancel(projectId, row.id)
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
    await scanApi.pause(projectId, row.id)
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
    await scanApi.resume(projectId, row.id)
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
    await scanApi.delete(projectId, row.id)
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

const loadVersionList = async (page?: number, silent = false) => {
  if (page) pageNum.value = page
  if (!silent) loading.value = true
  try {
    const res = await scanApi.list(projectId, {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    const list = res.list || []
    total.value = res.total || 0
    const mapped = list.map((v: any) => ({
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
    if (silent && versionList.value.length > 0) {
      // 轮询刷新：仅原地更新几个核心展示字段，避免整表重渲染 + 遮罩
      const map = new Map<any, any>(mapped.map((m: any) => [m.id, m]))
      versionList.value.forEach((row: any) => {
        const next: any = map.get(row.id)
        if (!next) return
        row.status = next.status
        row.progress = next.progress
        row.stage = next.stage
        row.taskCount = next.taskCount
        row.completedTaskCount = next.completedTaskCount
        row.nodeCount = next.nodeCount
        row.edgeCount = next.edgeCount
        row.factCount = next.factCount
        row.duration = next.duration
      })
    } else {
      versionList.value = mapped
    }
    hasLoadedOnce.value = true
    managePolling()
  } catch (err) {
    console.error('获取扫描版本列表失败:', err)
  } finally {
    if (!silent) loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadVersionList(page)
}

const startPolling = () => {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    loadVersionList(undefined, true)
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

/* 状态指示 */
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
  background-color: #909399;
}

.status-dot.status-running,
.status-dot.status-processing {
  background-color: #409eff;
  animation: pulse 1.5s ease-in-out infinite;
}

.status-dot.status-success,
.status-dot.status-completed {
  background-color: #67c23a;
}

.status-dot.status-failed {
  background-color: #f56c6c;
}

.status-dot.status-paused {
  background-color: #e6a23c;
}

.status-dot.status-created,
.status-dot.status-pending {
  background-color: #909399;
}

.status-dot.status-cancelled {
  background-color: #c0c4cc;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.status-text {
  font-size: 12px;
  color: #303133;
  vertical-align: middle;
}

/* 当前阶段 */
.stage-cell {
  display: flex;
  align-items: center;
  gap: 5px;
}

.stage-indicator {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #409eff;
  flex-shrink: 0;
}

.stage-indicator.is-active {
  background-color: #67c23a;
  animation: pulse 1.2s ease-in-out infinite;
}

.stage-label {
  font-size: 12px;
  color: #303133;
  line-height: 1.4;
}

/* 图谱统计：纵向紧凑排列 */
.graph-stats {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.stat-line {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}

.stat-num {
  font-style: normal;
  font-weight: 600;
  color: #303133;
  min-width: 24px;
  display: inline-block;
  text-align: right;
  margin-right: 2px;
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

/* 操作列按钮组：紧凑排列，不换行 */
.action-buttons {
  display: flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.action-buttons .el-button + .el-button {
  margin-left: 0;
}

.action-buttons .el-button--small.is-link {
  padding: 0 2px;
  height: auto;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ====== 扫描环节进度（版本详情） ====== */
.scan-phases {
  background: #fafbfc;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 12px 16px;
}

.phases-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.phases-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.phases-eta {
  font-size: 12px;
  color: #909399;
}

.phase-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.phase-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 4px;
  transition: background 0.2s;
}

.phase-item + .phase-item {
  border-top: 1px solid #ebeef5;
}

.phase-item.phase-running {
  background: #ecf5ff;
}

.phase-item.phase-success {
  background: transparent;
}

.phase-item.phase-failed {
  background: #fef0f0;
}

.phase-item.phase-warning {
  background: #fdf6ec;
}

.phase-item.phase-skipped {
  opacity: 0.6;
}

.phase-item.phase-pending {
  opacity: 0.55;
}

.phase-icon {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.phase-num {
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  color: #909399;
  background: #e4e7ed;
  font-weight: 500;
}

.phase-item.phase-running .phase-num {
  display: none;
}

.phase-item.phase-success .phase-num {
  display: none;
}

.icon-success {
  color: #67c23a;
  font-size: 18px;
}

.icon-warning {
  color: #e6a23c;
  font-size: 18px;
}

.icon-fail {
  color: #f56c6c;
  font-size: 18px;
}

.icon-running {
  color: #409eff;
  font-size: 18px;
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.phase-body {
  flex: 1;
  min-width: 0;
}

.phase-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.phase-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.phase-status-text {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.phase-item.phase-running .phase-name {
  color: #409eff;
}

.phase-item.phase-running .phase-status-text {
  color: #409eff;
  font-weight: 500;
}

.phase-item.phase-failed .phase-name {
  color: #f56c6c;
}

.phase-item.phase-failed .phase-status-text {
  color: #f56c6c;
}

.phase-progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.phase-progress-row .el-progress {
  flex: 1;
}

.phase-counts {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  min-width: 50px;
  text-align: right;
}

.phase-current-item {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-time-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 4px;
  font-size: 11px;
  color: #909399;
  line-height: 1.4;
}

.phase-eta {
  font-size: 11px;
  color: #e6a23c;
  margin-top: 3px;
}
</style>
