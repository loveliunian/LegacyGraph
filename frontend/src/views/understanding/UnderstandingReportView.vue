<template>
  <div class="understanding-report-view">
    <!-- 页面标题 -->
    <el-card class="header-card">
      <div class="header-row">
        <h2>代码理解报告</h2>
        <el-button type="primary" @click="showCreateDialog = true">
          <el-icon><Plus /></el-icon>
          新建报告
        </el-button>
      </div>
    </el-card>

    <!-- 工具健康状态 -->
    <el-card class="tool-health-card" v-loading="loadingHealth">
      <template #header>
        <span>工具健康状态</span>
        <el-button size="small" @click="refreshHealth" style="float: right">
          刷新
        </el-button>
      </template>
      <el-table :data="healthTools" stripe>
        <el-table-column prop="toolName" label="工具名称" width="180" />
        <el-table-column prop="toolKind" label="类型" width="100" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="能力" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="cap in row.capabilities" :key="cap" size="small" style="margin: 2px">
              {{ cap }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="indexFreshness" label="索引新鲜度" width="120" />
        <el-table-column prop="message" label="消息" min-width="200" />
      </el-table>
    </el-card>

    <!-- 新建报告对话框 -->
    <el-dialog v-model="showCreateDialog" title="新建代码理解报告" width="600px">
      <el-form :model="reportForm" label-width="100px">
        <el-form-item label="问题">
          <el-input
            v-model="reportForm.question"
            type="textarea"
            :rows="3"
            placeholder="例如：订单创建功能涉及哪些接口、服务、SQL、表和业务规则？"
          />
        </el-form-item>
        <el-form-item label="分析范围(路径)">
          <el-select
            v-model="reportForm.scopePaths"
            multiple
            filterable
            allow-create
            placeholder="输入文件路径"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="分析符号">
          <el-select
            v-model="reportForm.scopeSymbols"
            multiple
            filterable
            allow-create
            placeholder="输入类名/方法名"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="功能键">
          <el-select
            v-model="reportForm.scopeFeatureKeys"
            multiple
            filterable
            allow-create
            placeholder="输入功能键"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="reportForm.versionId" placeholder="可选，留空使用最新版本" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createReport">
          生成报告
        </el-button>
      </template>
    </el-dialog>

    <!-- 任务结果 -->
    <el-card v-if="taskResult" class="task-result-card">
      <template #header>
        <span>任务结果</span>
      </template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="任务 ID">{{ taskResult.taskId }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="taskResult.status === 'SUCCESS' ? 'success' : 'warning'">
            {{ taskResult.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="工具调用次数">{{ taskResult.toolRuns }}</el-descriptions-item>
        <el-descriptions-item label="证据数量">{{ taskResult.evidenceCount }}</el-descriptions-item>
        <el-descriptions-item label="Claim 数量">{{ taskResult.claimCount }}</el-descriptions-item>
        <el-descriptions-item label="待确认数量">
          <el-tag v-if="taskResult.pendingConfirmCount > 0" type="warning">
            {{ taskResult.pendingConfirmCount }}
          </el-tag>
          <span v-else>0</span>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 16px">
        <el-button type="success" @click="downloadReport">
          <el-icon><Download /></el-icon>
          下载 Markdown 报告
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Download } from '@element-plus/icons-vue'
import { understandingApi, type ToolHealthDto, type CodeUnderstandingTaskResult } from '@/api/understanding.api'
import { useRoute } from 'vue-router'

const route = useRoute()
const projectId = route.params.projectId as string

// 工具健康状态
const loadingHealth = ref(false)
const healthTools = ref<ToolHealthDto[]>([])

// 报告表单
const showCreateDialog = ref(false)
const creating = ref(false)
const reportForm = ref({
  question: '',
  scopePaths: [] as string[],
  scopeSymbols: [] as string[],
  scopeFeatureKeys: [] as string[],
  versionId: ''
})

// 任务结果
const taskResult = ref<CodeUnderstandingTaskResult | null>(null)

const statusTagType = (status: string) => {
  switch (status) {
    case 'READY': return 'success'
    case 'STALE': return 'warning'
    case 'UNAVAILABLE': return 'danger'
    case 'NOT_INSTALLED': return 'info'
    default: return 'info'
  }
}

const refreshHealth = async () => {
  loadingHealth.value = true
  try {
    const res = await understandingApi.getToolHealth(projectId)
    healthTools.value = res.tools
  } catch (e: any) {
    ElMessage.warning('获取工具健康状态失败: ' + (e?.message || '未知错误'))
  } finally {
    loadingHealth.value = false
  }
}

const createReport = async () => {
  if (!reportForm.value.question.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  creating.value = true
  try {
    const res = await understandingApi.createReport(projectId, {
      question: reportForm.value.question,
      scope: {
        paths: reportForm.value.scopePaths.length > 0 ? reportForm.value.scopePaths : undefined,
        symbols: reportForm.value.scopeSymbols.length > 0 ? reportForm.value.scopeSymbols : undefined,
        featureKeys: reportForm.value.scopeFeatureKeys.length > 0 ? reportForm.value.scopeFeatureKeys : undefined
      },
      versionId: reportForm.value.versionId || undefined,
      toolPolicy: {
        enabledToolKinds: ['MCP', 'CLI', 'LOCAL'],
        maxToolRuns: 30,
        maxSeconds: 180
      }
    })
    taskResult.value = {
      taskId: res.taskId,
      status: res.status,
      reportId: res.reportId,
      toolRuns: res.toolRuns,
      evidenceCount: res.evidenceCount,
      claimCount: res.claimCount,
      pendingConfirmCount: res.pendingConfirmCount,
      downloadUrl: res.downloadUrl
    }
    showCreateDialog.value = false
    ElMessage.success('报告生成任务已提交')
  } catch (e: any) {
    ElMessage.error('创建报告失败: ' + (e?.message || '未知错误'))
  } finally {
    creating.value = false
  }
}

const downloadReport = async () => {
  if (!taskResult.value) return
  try {
    const res = await understandingApi.downloadReport(projectId, taskResult.value.taskId)
    const blob = res as unknown as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'code-understanding-report.md'
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('报告已下载')
  } catch (e: any) {
    ElMessage.error('下载失败: ' + (e?.message || '未知错误'))
  }
}

onMounted(() => {
  refreshHealth()
})
</script>

<style scoped>
.understanding-report-view {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.header-card {
  margin-bottom: 20px;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-row h2 {
  margin: 0;
}

.tool-health-card {
  margin-bottom: 20px;
}

.task-result-card {
  margin-top: 20px;
}
</style>
