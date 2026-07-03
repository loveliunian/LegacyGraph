<template>
  <div class="pr-workbench-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>PR 工作台</span>
          <el-button
            type="primary"
            size="small"
            @click="showCreateDialog">
            <el-icon><Plus /></el-icon>
            新建 PR
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="list"
        border
        empty-text="暂无 PR 任务">
        <el-table-column
          prop="id"
          label="ID"
          width="100"
          show-overflow-tooltip />
        <el-table-column
          prop="title"
          label="标题"
          min-width="200" />
        <el-table-column
          prop="status"
          label="状态"
          width="120"
          align="center">
          <template #default="{ row }">
            <el-tag
              :type="statusTag(row.status)"
              size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="PR URL"
          width="200">
          <template #default="{ row }">
            <el-link
              v-if="row.prUrl"
              :href="row.prUrl"
              target="_blank"
              type="primary">
              {{ row.prUrl.substring(0, 30) }}...
            </el-link>
            <span
              v-else
              style="color: #999;">待创建</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="createdAt"
          label="创建时间"
          width="170">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="150"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              type="primary"
              @click="handleCreatePr(row)">
              创建草案
            </el-button>
            <el-button
              link
              size="small"
              @click="handleDetail(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 PR 对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建 PR"
      width="600px"
      destroy-on-close>
      <el-form
        :model="formData"
        label-width="100px">
        <el-form-item
          label="PR 标题"
          required>
          <el-input
            v-model="formData.title"
            placeholder="简要描述变更" />
        </el-form-item>
        <el-form-item label="分支名">
          <el-input
            v-model="formData.branch"
            placeholder="feature/xxx" />
        </el-form-item>
        <el-form-item label="关联 Issue">
          <el-input
            v-model="formData.issue"
            placeholder="#123" />
        </el-form-item>
        <el-form-item label="Diff">
          <el-input
            v-model="formData.diff"
            type="textarea"
            :rows="6"
            placeholder="粘贴 git diff 输出" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="handleCreate">
          创建
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      title="PR 详情"
      width="700px"
      destroy-on-close>
      <template v-if="currentTask">
        <el-descriptions
          :column="2"
          border>
          <el-descriptions-item label="ID">{{ currentTask.id }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ currentTask.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag
              :type="statusTag(currentTask.status)"
              size="small">
              {{ currentTask.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(currentTask.createdAt) }}</el-descriptions-item>
          <el-descriptions-item
            v-if="currentTask.prUrl"
            label="PR URL"
            :span="2">
            <el-link
              :href="currentTask.prUrl"
              target="_blank"
              type="primary">
              {{ currentTask.prUrl }}
            </el-link>
          </el-descriptions-item>
        </el-descriptions>
        <div
          v-if="currentTask.prDescription"
          style="margin-top: 16px;">
          <h4>PR 描述</h4>
          <div
            class="pr-body"
            v-html="renderMarkdown(currentTask.prDescription)" />
        </div>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { agentApi, changeTaskApi } from '@/api'
import dayjs from 'dayjs'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])
const dialogVisible = ref(false)
const detailVisible = ref(false)
const currentTask = ref<any>(null)

const formData = reactive({
  title: '',
  branch: '',
  issue: '',
  diff: '',
})

const formatTime = (time: string) => time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'

const statusTag = (status: string) => {
  const map: Record<string, string> = {
    CREATED: 'info', GENERATING: 'warning', GENERATED: 'success', FAILED: 'danger'
  }
  return map[status] || 'info'
}

function renderMarkdown(text: string) {
  if (!text) return ''
  return text.replace(/\n/g, '<br>').replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
}

async function loadList() {
  loading.value = true
  try {
    const res = await changeTaskApi.list(projectId)
    list.value = res?.list || res || []
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function showCreateDialog() {
  formData.title = ''
  formData.branch = ''
  formData.issue = ''
  formData.diff = ''
  dialogVisible.value = true
}

async function handleCreate() {
  if (!formData.title.trim()) { ElMessage.warning('请填写标题'); return }
  submitting.value = true
  try {
    await agentApi.prDescribe({
      projectId,
      branch: formData.branch,
      issue: formData.issue,
      diff: formData.diff,
    })
    ElMessage.success('PR 描述已生成')
    dialogVisible.value = false
    await loadList()
  } catch {
    ElMessage.error('创建失败')
  } finally {
    submitting.value = false
  }
}

async function handleCreatePr(row: any) {
  try {
    const prTask = await changeTaskApi.createPr(row.id)
    Object.assign(row, {
      prTaskId: prTask?.id,
      prUrl: prTask?.prUrl,
      prStatus: prTask?.prStatus,
      branchName: prTask?.branchName,
      reviewerPolicy: prTask?.reviewerPolicy,
      rollbackPlan: prTask?.rollbackPlan,
    })
    ElMessage.success('PR 草案已创建')
    await loadList()
  } catch {
    ElMessage.error('创建 PR 草案失败')
  }
}

async function handleDetail(row: any) {
  currentTask.value = row
  detailVisible.value = true
}

onMounted(() => { loadList() })
</script>

<style scoped>
.pr-workbench-page { padding: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.pr-body { padding: 12px; background: #f5f7fa; border-radius: 4px; line-height: 1.6; }
</style>
