<template>
  <div class="change-task-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>变更任务</span>
          <el-button type="primary" size="small" @click="showCreateDialog">
            <el-icon><Plus /></el-icon>
            新建任务
          </el-button>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" border style="width: 100%" empty-text="暂无变更任务">
        <el-table-column prop="id" label="任务ID" width="120" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column prop="taskType" label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="taskTypeTag(row.taskType)">{{ taskTypeLabel(row.taskType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="影响子图" width="100" align="center">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              type="primary"
              @click="handleRefreshImpact(row)"
              :loading="impactLoading.has(row.id)"
            >
              刷新
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="补丁" width="100" align="center">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              type="success"
              @click="handleGeneratePatch(row)"
              :loading="patchLoading.has(row.id)"
            >
              生成
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="handleDetail(row)">详情</el-button>
            <el-button
              link
              size="small"
              type="warning"
              @click="handleRunValidation(row)"
              :loading="validationLoading.has(row.id)"
            >
              验证
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建任务对话框 -->
    <el-dialog v-model="dialogVisible" title="新建变更任务" width="550px" destroy-on-close>
      <el-form :model="formData" label-width="80px">
        <el-form-item label="任务标题" required>
          <el-input v-model="formData.title" placeholder="简要描述变更内容" />
        </el-form-item>
        <el-form-item label="任务类型" required>
          <el-radio-group v-model="formData.taskType">
            <el-radio label="BUGFIX">Bug修复</el-radio>
            <el-radio label="REFACTOR">重构</el-radio>
            <el-radio label="UPGRADE">升级</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="扫描版本" required>
          <el-select v-model="formData.versionId" placeholder="选择扫描版本" style="width: 100%">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNumber || v.versionNo} - ${v.versionName || ''}`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="问题描述">
          <el-input
            v-model="formData.inputIssue"
            type="textarea"
            :rows="4"
            placeholder="描述需要处理的问题或变更内容"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="submitting">创建任务</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" title="任务详情" width="700px" destroy-on-close>
      <el-descriptions v-if="currentTask" :column="2" border>
        <el-descriptions-item label="任务ID">{{ currentTask.id }}</el-descriptions-item>
        <el-descriptions-item label="标题">{{ currentTask.title }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag size="small" :type="taskTypeTag(currentTask.taskType)">{{ taskTypeLabel(currentTask.taskType) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusTag(currentTask.status)">{{ currentTask.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="项目ID">{{ currentTask.projectId }}</el-descriptions-item>
        <el-descriptions-item label="版本ID">{{ currentTask.versionId }}</el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">{{ formatTime(currentTask.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="问题描述" :span="2">{{ currentTask.inputIssue || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="currentTask.impactedNodes" label="影响节点数" :span="1">
          {{ Array.isArray(currentTask.impactedNodes) ? currentTask.impactedNodes.length : 0 }}
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { changeTaskApi } from '@/api'
import { get } from '@/utils/request'
import dayjs from 'dayjs'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])
const versions = ref<any[]>([])
const dialogVisible = ref(false)
const detailVisible = ref(false)
const currentTask = ref<any>(null)
const impactLoading = ref(new Set<string>())
const patchLoading = ref(new Set<string>())
const validationLoading = ref(new Set<string>())

const formData = reactive({
  title: '',
  taskType: 'BUGFIX' as string,
  versionId: '',
  inputIssue: '',
})

const formatTime = (time: string) => {
  if (!time) return '-'
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const taskTypeLabel = (type: string) => {
  const map: Record<string, string> = { BUGFIX: 'Bug修复', REFACTOR: '重构', UPGRADE: '升级' }
  return map[type] || type || '-'
}

const taskTypeTag = (type: string) => {
  const map: Record<string, string> = { BUGFIX: 'danger', REFACTOR: 'warning', UPGRADE: 'primary' }
  return map[type] || 'info'
}

const statusTag = (status: string) => {
  const map: Record<string, string> = {
    OPEN: 'info',
    IMPACT_READY: 'warning',
    PATCH_DRAFTED: 'primary',
    REVIEW_PENDING: 'warning',
    VALIDATING: 'primary',
    VALIDATION_PASSED: 'success',
    VALIDATION_FAILED: 'danger',
    PR_READY: 'success',
    PR_CREATED: 'success'
  }
  return map[status] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    list.value = await changeTaskApi.list(projectId) as any[]
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  try {
    const res = await get(`/lg/projects/${projectId}/scan-versions`)
    versions.value = (res as any)?.list || []
  } catch {
    // 静默
  }
}

async function showCreateDialog() {
  await loadVersions()
  formData.title = ''
  formData.taskType = 'BUGFIX'
  formData.versionId = versions.value[0]?.id || ''
  formData.inputIssue = ''
  dialogVisible.value = true
}

async function handleCreate() {
  if (!formData.title.trim()) { ElMessage.warning('请填写任务标题'); return }
  if (!formData.versionId) { ElMessage.warning('请选择扫描版本'); return }
  submitting.value = true
  try {
    await changeTaskApi.create({
      projectId,
      versionId: formData.versionId,
      taskType: formData.taskType,
      title: formData.title,
      inputIssue: formData.inputIssue,
    }) as any
    ElMessage.success('任务已创建')
    dialogVisible.value = false
    await loadList()
  } catch {
    ElMessage.error('创建失败')
  } finally {
    submitting.value = false
  }
}

async function handleDetail(row: any) {
  try {
    currentTask.value = await changeTaskApi.get(row.id)
    detailVisible.value = true
  } catch {
    ElMessage.error('获取详情失败')
  }
}

async function handleRefreshImpact(row: any) {
  impactLoading.value.add(row.id)
  try {
    const { value } = await ElMessageBox.prompt('请输入本次变更目标节点 ID', '刷新影响子图', {
      confirmButtonText: '刷新',
      cancelButtonText: '取消',
      inputValue: row.targetNodeId || '',
      inputPattern: /\S+/,
      inputErrorMessage: '目标节点 ID 不能为空'
    })
    await changeTaskApi.refreshImpact(row.id, value)
    ElMessage.success('影响子图已刷新')
    await loadList()
  } catch {
    ElMessage.error('刷新失败')
  } finally {
    impactLoading.value.delete(row.id)
  }
}

async function handleGeneratePatch(row: any) {
  patchLoading.value.add(row.id)
  try {
    await changeTaskApi.generatePatch(row.id, {})
    ElMessage.success('补丁已生成')
    await loadList()
  } catch {
    ElMessage.error('生成失败')
  } finally {
    patchLoading.value.delete(row.id)
  }
}

async function handleRunValidation(row: any) {
  validationLoading.value.add(row.id)
  try {
    await changeTaskApi.runValidation(row.id, { gateTypes: ['STATIC'], environment: 'test' })
    ElMessage.success('验证已启动')
    await loadList()
  } catch {
    ElMessage.error('验证失败')
  } finally {
    validationLoading.value.delete(row.id)
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.change-task-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
