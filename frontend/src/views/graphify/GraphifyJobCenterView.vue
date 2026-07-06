<template>
  <div class="graphify-job-center">
    <div class="page-header">
      <h3>
        <el-icon><Operation /></el-icon>
        Graphify 作业中心
      </h3>
      <p class="header-desc">管理图谱导入作业，支持创建、取消、详情、重试和回滚操作</p>
    </div>

    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>导入作业列表</span>
          <div>
            <el-button type="success" size="small" @click="openCreateDialog">
              <el-icon><Plus /></el-icon>
              新建作业
            </el-button>
            <el-button type="primary" size="small" :loading="loading" @click="loadJobs">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="jobs"
        border
        stripe
        empty-text="暂无导入作业">
        <el-table-column prop="jobId" label="作业ID" width="200" show-overflow-tooltip />
        <el-table-column prop="versionId" label="版本ID" width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="importedNodes" label="节点数" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.importedNodes != null">{{ row.importedNodes }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="importedEdges" label="边数" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.importedEdges != null">{{ row.importedEdges }}</span>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column label="尝试" width="90" align="center">
          <template #default="{ row }">
            <span>{{ row.attempt ?? 0 }} / {{ row.maxAttempts ?? 3 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="text-danger">{{ row.errorMessage }}</span>
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
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'QUEUED' || row.status === 'RUNNING'"
              type="warning"
              link
              size="small"
              :loading="row._cancelLoading"
              @click="handleCancel(row)">
              取消
            </el-button>
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
              v-if="row.status === 'IMPORTED'"
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

    <!-- 新建作业对话框 -->
    <el-dialog v-model="createVisible" title="新建 Graphify 导入作业" width="560px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="版本ID" prop="versionId">
          <el-input v-model="createForm.versionId" placeholder="扫描版本ID" />
        </el-form-item>
        <el-form-item label="项目根目录" prop="projectRoot">
          <el-input v-model="createForm.projectRoot" placeholder="源码根目录绝对路径" />
        </el-form-item>
        <el-form-item label="分支名称">
          <el-input v-model="createForm.branchName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="源码 Commit">
          <el-input v-model="createForm.sourceCommit" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 作业详情对话框 -->
    <el-dialog v-model="detailVisible" title="作业详情" width="640px">
      <el-descriptions v-if="detail" :column="2" border>
        <el-descriptions-item label="作业ID">{{ detail.jobId }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(detail.status)" size="small">{{ getStatusText(detail.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="版本ID">{{ detail.versionId }}</el-descriptions-item>
        <el-descriptions-item label="Graphify 版本">{{ detail.graphifyVersion || '-' }}</el-descriptions-item>
        <el-descriptions-item label="项目根目录" :span="2">{{ detail.projectRoot || '-' }}</el-descriptions-item>
        <el-descriptions-item label="分支">{{ detail.branchName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="源码 Commit">{{ detail.sourceCommit || '-' }}</el-descriptions-item>
        <el-descriptions-item label="尝试">{{ detail.attempt ?? 0 }} / {{ detail.maxAttempts ?? 3 }}</el-descriptions-item>
        <el-descriptions-item label="兼容性报告">{{ detail.compatibilityReportId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="导入节点数">{{ detail.importedNodes ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="导入边数">{{ detail.importedEdges ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="导入证据数">{{ detail.importedEvidence ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.createdAt ? formatTime(detail.createdAt) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ detail.startedAt ? formatTime(detail.startedAt) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ detail.finishedAt ? formatTime(detail.finishedAt) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2">
          <span v-if="detail.errorMessage" class="text-danger">{{ detail.errorMessage }}</span>
          <span v-else class="text-gray">-</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Operation, Refresh, Plus } from '@element-plus/icons-vue'
import { graphifyApi } from '@/api/graphify.api'
import type { GraphifyJob, GraphifyCreateJobRequest } from '@/api/graphify.api'
import dayjs from 'dayjs'

type JobRow = GraphifyJob & {
  _retryLoading?: boolean
  _rollbackLoading?: boolean
  _cancelLoading?: boolean
}

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const jobs = ref<JobRow[]>([])

// 新建作业
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<GraphifyCreateJobRequest>({
  versionId: '',
  projectRoot: '',
  branchName: '',
  sourceCommit: '',
})
const createRules: FormRules = {
  versionId: [{ required: true, message: '请输入版本ID', trigger: 'blur' }],
  projectRoot: [{ required: true, message: '请输入项目根目录', trigger: 'blur' }],
}

// 详情
const detailVisible = ref(false)
const detail = ref<GraphifyJob | null>(null)

function formatTime(time: string): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    QUEUED: 'info',
    RUNNING: 'warning',
    IMPORTED: 'success',
    FAILED: 'danger',
    CANCELLED: 'info',
  }
  return map[status] || 'info'
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    QUEUED: '已排队',
    RUNNING: '运行中',
    IMPORTED: '已导入',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return map[status] || status
}

async function loadJobs() {
  loading.value = true
  try {
    const res: any = await graphifyApi.getJobs(projectId)
    const list: GraphifyJob[] = Array.isArray(res) ? res : (res?.list ?? [])
    jobs.value = list.map((j) => ({
      ...j,
      _retryLoading: false,
      _rollbackLoading: false,
      _cancelLoading: false,
    }))
  } catch (err) {
    console.error('加载作业列表失败:', err)
    ElMessage.error('加载作业列表失败')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.versionId = ''
  createForm.projectRoot = ''
  createForm.branchName = ''
  createForm.sourceCommit = ''
  createVisible.value = true
}

async function handleCreate() {
  if (!createFormRef.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    creating.value = true
    try {
      await graphifyApi.createJob(projectId, { ...createForm })
      ElMessage.success('作业已创建')
      createVisible.value = false
      await loadJobs()
    } catch (err: any) {
      ElMessage.error('创建失败: ' + (err.message || '未知错误'))
    } finally {
      creating.value = false
    }
  })
}

async function handleDetail(job: JobRow) {
  detail.value = job
  detailVisible.value = true
  // 拉取最新详情
  try {
    const res: any = await graphifyApi.getJob(projectId, job.jobId)
    if (res) detail.value = res
  } catch (err) {
    // 退化为行内数据，不提示
  }
}

async function handleCancel(job: JobRow) {
  try {
    await ElMessageBox.confirm(`确定取消作业 ${job.jobId} 吗？`, '确认取消', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    job._cancelLoading = true
    await graphifyApi.cancelJob(projectId, job.jobId)
    ElMessage.success('作业已取消')
    await loadJobs()
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('取消失败: ' + (err.message || '未知错误'))
    }
  } finally {
    job._cancelLoading = false
  }
}

async function handleRetry(job: JobRow) {
  try {
    await ElMessageBox.confirm(`确定重试作业 ${job.jobId} 吗？`, '确认重试', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    job._retryLoading = true
    await graphifyApi.retryJob(projectId, job.jobId)
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

async function handleRollback(job: JobRow) {
  try {
    await ElMessageBox.confirm(
      `确定回滚作业 ${job.jobId} 吗？回滚将移除该次导入的所有节点和边。`,
      '确认回滚',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    job._rollbackLoading = true
    await graphifyApi.rollbackJob(projectId, job.jobId)
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
