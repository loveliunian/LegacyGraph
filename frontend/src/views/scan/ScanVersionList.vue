<template>
  <div class="scan-version-list">
    <div class="page-header">
      <h3>{{ t('menu.scanVersions') }}</h3>
      <el-button
        type="primary"
        @click="goToCreate">
        <el-icon><Plus /></el-icon>
        新建扫描
      </el-button>
    </div>

    <el-table
      v-loading="loading && !hasLoadedOnce"
      :data="versionList"
      border
      stripe>
      <!-- 版本号 + 版本名称合并为一列 -->
      <el-table-column
        label="版本信息"
        min-width="200">
        <template #default="{ row }">
          <div class="version-info">
            <span
              class="version-no"
              :title="row.versionNumber">{{ truncateText(row.versionNumber, 20) }}</span>
            <span
              class="version-name"
              :title="row.versionName">{{ truncateText(row.versionName, 24) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="扫描类型"
        width="130">
        <template #default="{ row }">
          <div class="scan-types">
            <el-tag
              v-for="t in parseScanTypes(row.scanType)"
              :key="t"
              size="small"
              type="primary"
              class="scan-type-tag"
            >
              {{ dictLabel('scan_type', t) }}
            </el-tag>
            <span
              v-if="parseScanTypes(row.scanType).length === 0"
              class="text-gray">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="80"
        align="center">
        <template #default="{ row }">
          <span
            class="status-dot"
            :class="'status-' + row.status?.toLowerCase()" />
          <span class="status-text">{{ getStatusText(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="进度"
        width="170">
        <template #default="{ row }">
          <div class="progress-wrapper">
            <el-progress
              :percentage="row.progress || 0"
              :status="row.status === 'FAILED' ? 'exception' : (row.progress === 100 ? 'success' : undefined)"
              :stroke-width="12"
            />
            <span
              v-if="row.taskCount > 0"
              class="progress-text">
              {{ row.completedTaskCount }}/{{ row.taskCount }} 任务
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="当前阶段"
        width="110">
        <template #default="{ row }">
          <div
            v-if="row.stage && row.stage !== '-'"
            class="stage-cell">
            <span
              class="stage-indicator"
              :class="{ 'is-active': row.status === 'RUNNING' }" />
            <span class="stage-label">{{ getStageText(row.stage) }}</span>
          </div>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="图谱统计"
        width="110">
        <template #default="{ row }">
          <div
            v-if="row.nodeCount || row.edgeCount || row.factCount"
            class="graph-stats">
            <span
              v-if="row.nodeCount"
              class="stat-line">
              <em class="stat-num">{{ row.nodeCount }}</em> 节点
            </span>
            <span
              v-if="row.edgeCount"
              class="stat-line">
              <em class="stat-num">{{ row.edgeCount }}</em> 关系
            </span>
            <span
              v-if="row.factCount"
              class="stat-line">
              <em class="stat-num">{{ row.factCount }}</em> 事实
            </span>
          </div>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <!-- L-13: QA Gate 列 -->
      <el-table-column
        label="QA Gate"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.qaGateStatus === 'PASSED'"
            type="success"
            size="small">PASSED</el-tag>
          <el-tag
            v-else-if="row.qaGateStatus === 'FAILED'"
            type="danger"
            size="small">FAILED</el-tag>
          <el-tag
            v-else
            type="info"
            size="small">NOT_RUN</el-tag>
        </template>
      </el-table-column>
      <!-- L-14: 项目维度累积统计（应用累积节点/边/事实，不被本次扫描删节点影响） -->
      <el-table-column
        label="项目累积"
        width="150">
        <template #default="{ row }">
          <div
            v-if="row.cumulativeNodeCount || row.cumulativeEdgeCount || row.cumulativeFactCount"
            class="cumulative-stats"
            :title="row.cumulativeUpdatedAt ? `更新于 ${formatTime(row.cumulativeUpdatedAt)}` : ''">
            <span class="stat-line">
              <em class="stat-num">{{ formatNumber(row.cumulativeNodeCount) }}</em> 节点
            </span>
            <span class="stat-line">
              <em class="stat-num">{{ formatNumber(row.cumulativeEdgeCount) }}</em> 关系
            </span>
            <span class="stat-line">
              <em class="stat-num">{{ formatNumber(row.cumulativeFactCount) }}</em> 事实
            </span>
          </div>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="耗时"
        width="90"
        align="center">
        <template #default="{ row }">
          <span
            v-if="row.duration"
            class="duration-text">{{ formatDuration(row.duration) }}</span>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        width="160">
        <template #default="{ row }">
          <span
            v-if="row.createdAt"
            class="time-text">{{ formatTime(row.createdAt) }}</span>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="240"
        fixed="right">
        <template #default="{ row }">
          <div class="action-buttons">
            <el-button
              type="primary"
              link
              size="small"
              @click="viewDetail(row)">
              详情
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="compareWithPrevious(row)">
              对比
            </el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              type="warning"
              link
              size="small"
              @click="pauseScan(row)">
              暂停
            </el-button>
            <el-button
              v-if="row.status === 'PAUSED'"
              type="success"
              link
              size="small"
              @click="resumeScan(row)">
              恢复
            </el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              type="danger"
              link
              size="small"
              @click="stopScan(row)">
              停止
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="deleteVersion(row)">
              删除
            </el-button>
          </div>
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
        @size-change="() => loadVersionList(1)"
      />
    </div>

    <el-empty
      v-if="versionList.length === 0 && !loading"
      description="暂无扫描版本" />

    <!-- 新建扫描对话框 -->
    <el-dialog
      v-model="createDialogVisible"
      title="新建扫描任务"
      width="850px"
      append-to-body
      :close-on-click-modal="false"
      @closed="resetCreateForm">
      <CreateScan
        v-if="createDialogVisible"
        :project-id="projectId"
        @success="onCreated" />
    </el-dialog>

    <!-- 版本详情对话框 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="版本详情"
      width="720px"
      append-to-body
      @closed="stopDetailPolling">
      <template v-if="currentVersion">
        <!-- 基本信息 -->
        <el-descriptions
          :column="2"
          border
          size="small"
          style="margin-bottom: 16px;">
          <el-descriptions-item label="版本号">{{ currentVersion.versionNumber }}</el-descriptions-item>
          <el-descriptions-item label="版本名称">{{ currentVersion.versionName }}</el-descriptions-item>
          <el-descriptions-item label="扫描类型">
            <el-tag
              v-for="t in parseScanTypes(currentVersion.scanType)"
              :key="t"
              size="small"
              type="primary"
              style="margin-right: 4px;">
              {{ dictLabel('scan_type', t) }}
            </el-tag>
            <span v-if="parseScanTypes(currentVersion.scanType).length === 0">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag
              size="small"
              :type="getStatusType(currentVersion.status)">
              {{ getStatusText(currentVersion.status) }}
            </el-tag>
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
            <span
              v-if="detailProgress"
              class="phases-eta">
              整体进度 {{ detailProgress.progress || 0 }}%
              <template v-if="detailProgress.estimatedSecondsRemaining && detailProgress.estimatedSecondsRemaining > 0">
                · 预计剩余 {{ formatDuration(detailProgress.estimatedSecondsRemaining) }}
              </template>
            </span>
          </div>
          <div class="phase-list" v-loading="detailLoading && !detailProgress">
            <template v-if="detailProgress">
            <div
              v-for="(phase, idx) in allPhases"
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
                <el-icon
                  v-if="phase.status === 'SUCCESS'"
                  class="icon-success">
                  <CircleCheckFilled />
                </el-icon>
                <el-icon
                  v-else-if="phase.status === 'WARNING'"
                  class="icon-warning">
                  <WarningFilled />
                </el-icon>
                <el-icon
                  v-else-if="phase.status === 'FAILED'"
                  class="icon-fail">
                  <CircleCloseFilled />
                </el-icon>
                <el-icon
                  v-else-if="phase.status === 'RUNNING'"
                  class="icon-running">
                  <Loading />
                </el-icon>
                <span
                  v-else
                  class="phase-num">{{ idx + 1 }}</span>
              </div>
              <!-- 阶段信息 -->
              <div class="phase-body">
                <div class="phase-top">
                  <span class="phase-name">{{ phase.phaseName || phase.taskType }}</span>
                  <span class="phase-status-text">{{ dictLabel('scan_status', phase.status) }}</span>
                </div>
                <!-- 进度条（RUNNING/SUCCESS/WARNING 阶段显示） -->
                <div
                  v-if="phase.totalItems && phase.totalItems > 0 && (phase.status === 'RUNNING' || phase.status === 'SUCCESS' || phase.status === 'WARNING')"
                  class="phase-progress-row">
                  <el-progress
                    :percentage="Math.min(100, phase.totalItems > 0 ? Math.round((Math.min(phase.processedItems || 0, phase.totalItems)) * 100 / phase.totalItems) : 0)"
                    :stroke-width="6"
                    :status="phase.status === 'FAILED' ? 'exception' : undefined"
                    :color="phase.status === 'SUCCESS' ? '#67c23a' : '#409eff'"
                  />
                  <span class="phase-counts">{{ Math.min(phase.processedItems || 0, phase.totalItems) }} / {{ phase.totalItems }} 项</span>
                </div>
                <!-- 当前处理项名称 -->
                <div
                  v-if="phase.currentItem && phase.status === 'RUNNING'"
                  class="phase-current-item">
                  <el-icon><Document /></el-icon>
                  {{ phase.currentItem }}
                </div>
                <div
                  v-if="phase.startedAt || phase.finishedAt"
                  class="phase-time-row">
                  <span>开始 {{ formatTime(phase.startedAt) }}</span>
                  <span>结束 {{ formatTime(phase.finishedAt) }}</span>
                  <span>耗时 {{ formatPhaseDuration(phase.startedAt, phase.finishedAt) }}</span>
                </div>
                <!-- 预估剩余时间 -->
                <div
                  v-if="phase.estimatedSecondsRemaining && phase.estimatedSecondsRemaining > 0 && phase.status === 'RUNNING'"
                  class="phase-eta">
                  预计剩余 {{ formatDuration(phase.estimatedSecondsRemaining) }}
                </div>
                <!-- AI 子环节展开 -->
                <div
                  v-if="phase.taskType === 'AI_ORCHESTRATION' && phase.subPhases && phase.subPhases.length"
                  class="sub-phase-list">
                  <div
                    v-for="(sub, subIdx) in phase.subPhases"
                    :key="sub.taskType"
                    class="sub-phase-item"
                    :class="{
                      'sub-phase-running': sub.status === 'RUNNING',
                      'sub-phase-success': sub.status === 'SUCCESS',
                      'sub-phase-warning': sub.status === 'WARNING',
                      'sub-phase-failed': sub.status === 'FAILED',
                      'sub-phase-pending': sub.status === 'PENDING',
                      'sub-phase-skipped': sub.status === 'SKIPPED'
                    }"
                  >
                    <div class="sub-phase-icon">
                      <el-icon v-if="sub.status === 'SUCCESS'" class="icon-success"><CircleCheckFilled /></el-icon>
                      <el-icon v-else-if="sub.status === 'WARNING'" class="icon-warning"><WarningFilled /></el-icon>
                      <el-icon v-else-if="sub.status === 'FAILED'" class="icon-fail"><CircleCloseFilled /></el-icon>
                      <el-icon v-else-if="sub.status === 'RUNNING'" class="icon-running"><Loading /></el-icon>
                      <span v-else class="sub-phase-num">{{ subIdx + 1 }}</span>
                    </div>
                    <div class="sub-phase-body">
                      <div class="sub-phase-top">
                        <span class="sub-phase-name">{{ sub.phaseName || sub.taskType }}</span>
                        <span class="sub-phase-status-text">{{ dictLabel('scan_status', sub.status) }}</span>
                      </div>
                      <div
                        v-if="sub.totalItems && sub.totalItems > 0 && (sub.status === 'RUNNING' || sub.status === 'SUCCESS' || sub.status === 'WARNING')"
                        class="sub-phase-progress-row">
                        <el-progress
                          :percentage="Math.min(100, sub.totalItems > 0 ? Math.round((Math.min(sub.processedItems || 0, sub.totalItems)) * 100 / sub.totalItems) : 0)"
                          :stroke-width="4"
                          :status="sub.status === 'FAILED' ? 'exception' : undefined"
                          :color="sub.status === 'SUCCESS' ? '#67c23a' : '#409eff'"
                        />
                        <span class="sub-phase-counts">{{ Math.min(sub.processedItems || 0, sub.totalItems) }} / {{ sub.totalItems }} 项</span>
                      </div>
                      <div
                        v-if="sub.startedAt || sub.finishedAt"
                        class="sub-phase-time-row">
                        <span>耗时 {{ formatPhaseDuration(sub.startedAt, sub.finishedAt) }}</span>
                      </div>
                      <div
                        v-if="sub.estimatedSecondsRemaining && sub.estimatedSecondsRemaining > 0 && sub.status === 'RUNNING'"
                        class="sub-phase-eta">
                        预计剩余 {{ formatDuration(sub.estimatedSecondsRemaining) }}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            </template>
            <div v-else-if="!detailLoading" class="phase-empty">
              暂无进度数据
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          @click="goToGraph(currentVersion)">
          查看图谱
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Loading, CircleCheckFilled, CircleCloseFilled, WarningFilled, Document } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { t } from '@/locales'
import { scanApi } from '@/api'
import { preloadDicts, dictLabel } from '@/utils/dict'
import { clearVersionsCache } from '@/utils/versionsCache'
import CreateScan from '@/views/scan/CreateScan.vue'

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
const createDialogVisible = ref(false)
const currentVersion = ref<any>(null)
const detailProgress = shallowRef<any>(null)
const detailLoading = ref(false)
const DETAIL_POLL_INTERVAL = 10000 // 详情轮询 10 秒
let detailPollTimer: ReturnType<typeof setTimeout> | null = null
let detailInFlight = false // 防止请求堆积：前一次未返回时跳过下一次

let pollTimer: ReturnType<typeof setInterval> | null = null
const POLL_INTERVAL = 20000 // 列表轮询 20 秒（仅运行中）

const hasRunningTasks = computed(() =>
  versionList.value.some(v => v.status === 'RUNNING')
)

// 所有环节定义（兜底，API 未返回时也能展示全部 PENDING 状态）
const ALL_PHASES = [
  { taskType: 'DB_DISCOVERY', phaseName: '数据库连接发现' },
  { taskType: 'PATH_DISCOVERY', phaseName: '前后端路径检测' },
  { taskType: 'DOC_DISCOVERY', phaseName: '文档自动发现' },
  { taskType: 'ADAPTER_SCAN', phaseName: '代码结构抽取' },
  { taskType: 'DATABASE_SCAN', phaseName: '数据库元数据扫描' },
  { taskType: 'GRAPH_BUILD', phaseName: '知识图谱构建' },
  { taskType: 'AI_ORCHESTRATION', phaseName: 'AI 智能分析' }
]

/** 合并 API 返回的 tasks 与全量环节定义，确保所有环节始终展示 */
const allPhases = computed(() => {
  const apiTasks = detailProgress.value?.tasks || []
  const taskMap = new Map<string, any>()
  for (const t of apiTasks) {
    taskMap.set(t.taskType, t)
  }
  return ALL_PHASES.map(p => taskMap.get(p.taskType) || { ...p, status: 'PENDING' })
})

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
  if (seconds < 1) return '<1s'
  const s = Math.round(seconds * 10) / 10
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m ${Math.round((s % 60) * 10) / 10}s`
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
}

const formatPhaseDuration = (startedAt?: string, finishedAt?: string) => {
  if (!startedAt || !finishedAt) return '-'
  const seconds = dayjs(finishedAt).diff(dayjs(startedAt), 'second', true)
  return formatDuration(seconds)
}

// L-14: 数字千分位格式化（用于累积统计列展示）
const formatNumber = (n?: number | null): string => {
  if (n === null || n === undefined || Number.isNaN(n)) return '0'
  return n.toLocaleString('en-US')
}

// 解析 scanType JSON，返回扫描类型数组
const parseScanTypes = (scanType: string): string[] => {
  if (!scanType) return []
  try {
    const parsed = JSON.parse(scanType)
    if (parsed.scanTypes && Array.isArray(parsed.scanTypes)) {
      return parsed.scanTypes
    }
  } catch {
    // ignore non-JSON scanType and parse it below
  }
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
  createDialogVisible.value = true
}

const onCreated = () => {
  createDialogVisible.value = false
  // L-19: 新建扫描版本后清除缓存，避免新版本被 TTL 窗口期内的旧缓存遮挡
  clearVersionsCache(projectId)
  loadVersionList()
}

const resetCreateForm = () => {
  // 关闭弹窗时 CreateScan 组件会通过 v-if 销毁重建，无需手动重置
}

const fetchDetailProgress = async () => {
  if (!currentVersion.value || detailInFlight) return
  detailInFlight = true
  try {
    const result = await scanApi.progress(projectId, currentVersion.value.id) as any
    // 浅 diff：关键字段未变时跳过赋值，避免不必要重渲染
    const old = detailProgress.value
    if (old && old.progress === result.progress && old.status === result.status
        && old.estimatedSecondsRemaining === result.estimatedSecondsRemaining
        && JSON.stringify(old.tasks) === JSON.stringify(result.tasks)) {
      // 数据未变，跳过赋值
    } else {
      detailProgress.value = result
    }
    // 终态版本停止轮询
    if (result.status === 'SUCCESS' || result.status === 'FAILED' || result.status === 'CANCELLED') {
      stopDetailPolling(false)
      return
    }
  } catch (err) {
    console.error('获取扫描进度失败:', err)
  } finally {
    detailInFlight = false
  }
  // 递归调度下一次（请求完成后才排下一次，避免堆积）
  if (detailDialogVisible.value) {
    detailPollTimer = setTimeout(fetchDetailProgress, DETAIL_POLL_INTERVAL)
  }
}

const viewDetail = (row: any) => {
  currentVersion.value = row
  detailDialogVisible.value = true
  detailProgress.value = null
  detailLoading.value = true
  // 立即获取一次进度
  fetchDetailProgress().finally(() => { detailLoading.value = false })
}

const stopDetailPolling = (clearProgress = true) => {
  if (detailPollTimer) {
    clearTimeout(detailPollTimer)
    detailPollTimer = null
  }
  if (clearProgress) {
    detailProgress.value = null
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
    // L-19: 删除成功后主动清除版本缓存，避免依赖 TTL 过期导致残留数据
    clearVersionsCache(projectId)
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
    // 无运行中任务时自动停轮询
    if (!hasRunningTasks.value) {
      stopPolling()
      return
    }
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
  stopDetailPolling()
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
  font-family: var(--font-mono);
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

/* L-14: 项目累积统计，与图谱统计列同款紧凑布局，但用辅助色区分 */
.cumulative-stats {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
  color: #909399;
}

.cumulative-stats .stat-num {
  color: #606266;
  font-weight: 500;
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
  font-family: var(--font-mono);
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

/* AI 子环节展开样式 */
.sub-phase-list {
  margin-top: 8px;
  padding-left: 12px;
  border-left: 2px solid #dcdfe6;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sub-phase-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 5px 6px;
  border-radius: 3px;
}

.sub-phase-item.sub-phase-running {
  background: rgba(64, 158, 255, 0.06);
}

.sub-phase-item.sub-phase-failed {
  background: rgba(245, 108, 108, 0.06);
}

.sub-phase-item.sub-phase-skipped,
.sub-phase-item.sub-phase-pending {
  opacity: 0.55;
}

.sub-phase-icon {
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.sub-phase-icon .el-icon {
  font-size: 14px;
}

.sub-phase-num {
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  font-size: 10px;
  color: #909399;
  background: #e4e7ed;
}

.sub-phase-body {
  flex: 1;
  min-width: 0;
}

.sub-phase-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sub-phase-name {
  font-size: 12px;
  font-weight: 500;
  color: #606266;
}

.sub-phase-item.sub-phase-running .sub-phase-name {
  color: #409eff;
}

.sub-phase-item.sub-phase-failed .sub-phase-name {
  color: #f56c6c;
}

.sub-phase-status-text {
  font-size: 11px;
  color: #909399;
}

.sub-phase-progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.sub-phase-progress-row .el-progress {
  flex: 1;
}

.sub-phase-counts {
  font-size: 10px;
  color: #909399;
  white-space: nowrap;
  min-width: 40px;
  text-align: right;
}

.sub-phase-time-row {
  margin-top: 2px;
  font-size: 10px;
  color: #909399;
}

.sub-phase-eta {
  font-size: 10px;
  color: #e6a23c;
  margin-top: 2px;
}

.phase-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--el-text-color-placeholder, #909399);
  font-size: 13px;
}
</style>
