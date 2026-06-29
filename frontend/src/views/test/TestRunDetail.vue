<template>
  <div class="test-run-detail">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>测试执行详情 #{{ run?.id }}</span>
          <el-button type="primary" @click="rerunAll">重跑全部失败用例</el-button>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="环境">{{ run?.environment }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(run?.status || '')">{{ getStatusText(run?.status || '') }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatDate(run?.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatDate(run?.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="总用例数">{{ run?.totalCases }}</el-descriptions-item>
        <el-descriptions-item label="通过数">{{ run?.passedCases }}</el-descriptions-item>
      </el-descriptions>

      <div class="summary">
        <el-progress
          :percentage="getPercentage()"
          :color="getProgressColor()"
          :stroke-width="12"
        />
      </div>

      <el-divider>用例结果</el-divider>

      <el-table :data="caseResults" border style="width: 100%">
        <el-table-column prop="caseName" label="用例名称" min-width="200" />
        <el-table-column prop="caseType" label="类型" width="100" />
        <el-table-column prop="resultStatus" label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.resultStatus === 'PASSED' ? 'success' : 'danger'">
              {{ row.resultStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="showResult(row)">查看结果</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 结果详情对话框 -->
    <el-dialog v-model="resultDialogVisible" title="用例结果" width="70%">
      <el-descriptions border>
        <el-descriptions-item label="请求">
          <pre class="code-block">{{ resultDialogData.requestData }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="响应">
          <pre class="code-block">{{ resultDialogData.responseData }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息" v-if="resultDialogData.errorMessage">
          <pre class="code-block error">{{ resultDialogData.errorMessage }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { testRunApi } from '@/api'
import type { TestRun, TestResult } from '@/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()

const loading = ref(true)
const run = ref<any>(null)
const caseResults = ref<any[]>([])
const resultDialogVisible = ref(false)
const resultDialogData = ref({
  requestData: '',
  responseData: '',
  errorMessage: '',
})

function getStatusType(status: string) {
  switch (status) {
    case 'SCHEDULED': return 'warning'
    case 'RUNNING': return 'primary'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function getStatusText(status: string) {
  switch (status) {
    case 'SCHEDULED': return '等待中'
    case 'RUNNING': return '执行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    default: return status
  }
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return dateStr.replace('T', ' ').split('.')[0]
}

const getPercentage = computed(() => {
  if (!run.value || !run.value.totalCases) return 0
  return Math.round((run.value.passedCases / run.value.totalCases) * 100)
})

const getProgressColor = computed(() => {
  const percentage = getPercentage.value
  if (percentage < 50) return '#f56c6c'
  if (percentage < 80) return '#e6a23c'
  return '#67c23a'
})

function showResult(row: TestResult) {
  resultDialogData.value = {
    requestData: row.requestData || '',
    responseData: row.responseData || '',
    errorMessage: row.errorMessage || '',
  }
  resultDialogVisible.value = true
}

async function rerunAll() {
  const projectId = projectStore.currentProjectId as string
  const runId = route.params.id as string
  try {
    await testRunApi.rerunFailed(projectId, runId)
    ElMessage.success('已重新触发失败用例执行')
    loadData()
  } catch (error) {
    console.error(error)
    ElMessage.error('重跑失败')
  }
}

async function loadData() {
  const projectId = projectStore.currentProjectId as string
  const runId = route.params.id as string
  loading.value = true
  try {
    const runData = await testRunApi.getTestRunDetail(projectId, runId)
    run.value = runData
    const data = await testRunApi.getCaseResults(projectId, runId)
    caseResults.value = data
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.test-run-detail {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.summary {
  margin: 20px 0;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
}

.code-block {
  max-height: 200px;
  overflow: auto;
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  font-family: monospace;
  white-space: pre-wrap;
  word-break: break-all;
}

.code-block.error {
  background: #fef0f0;
  color: #f56c6c;
}
</style>
