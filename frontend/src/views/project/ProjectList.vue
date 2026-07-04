<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目列表</span>
          <el-button
            type="primary"
            @click="showCreateDialog">
            创建项目
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="pageData?.list"
        border>
        <el-table-column
          prop="projectCode"
          label="项目编码"
          width="180" />
        <el-table-column
          prop="projectName"
          label="项目名称"
          width="200" />
        <el-table-column
          prop="description"
          label="描述" />
        <el-table-column
          prop="owner"
          label="负责人"
          width="120" />
        <el-table-column
          label="创建时间"
          width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="200"
          fixed="right">
          <template #default="{row}">
            <el-button
              type="primary"
              link
              @click="goToDetail(row.id)">
              详情
            </el-button>
            <el-button
              type="danger"
              link
              @click="deleteProject(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="pageData?.total || 0"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>

    <!-- 创建项目对话框 -->
    <el-dialog
      v-model="createDialogVisible"
      title="创建项目"
      width="500px">
      <el-form
        :model="newProject"
        label-width="100px">
        <el-form-item
          label="项目编码"
          required>
          <el-input
            v-model="newProject.projectCode"
            placeholder="例如: legacy-bpm" />
        </el-form-item>
        <el-form-item
          label="项目名称"
          required>
          <el-input
            v-model="newProject.projectName"
            placeholder="例如: 老流程平台" />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input
            v-model="newProject.description"
            type="textarea"
            placeholder="项目用途描述"
          />
        </el-form-item>
        <el-form-item label="Git 地址">
          <el-input
            v-model="newProject.repoUrl"
            placeholder="Git 仓库地址" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input
            v-model="newProject.owner"
            placeholder="项目负责人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          @click="createProject">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { projectApi } from '@/api'
import type { PageResult } from '@/types'
import type { Project } from '@/types'

const router = useRouter()
const loading = ref(false)
const createDialogVisible = ref(false)

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const pageData = ref<PageResult<Project>>({
  list: [],
  total: 0,
  pageNum: 1,
  pageSize: 20,
  totalPages: 0
})

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  keyword: ''
})

const newProject = reactive({
  projectCode: '',
  projectName: '',
  description: '',
  repoUrl: '',
  owner: ''
})

const loadData = async () => {
  loading.value = true
  try {
    const data = await projectApi.list(query) as any
    pageData.value = data
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const showCreateDialog = () => {
  createDialogVisible.value = true
}

const createProject = async () => {
  if (!newProject.projectCode || !newProject.projectName) {
    ElMessage.warning('请填写必填项')
    return
  }
  try {
    await projectApi.create(newProject)
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    // 清空
    Object.assign(newProject, {
      projectCode: '',
      projectName: '',
      description: '',
      repoUrl: '',
      owner: ''
    })
    await loadData()
  } catch (e) {
    console.error(e)
  }
}

const deleteProject = async (id: string) => {
  try {
    // 第一次确认
    await ElMessageBox.confirm('确认删除此项目？', '提示')
    // 第二次确认：需要输入项目名称
    const projectName = pageData.value.list.find(p => p.id === id)?.projectName || ''
    await ElMessageBox.confirm('确认删除此项目？此操作将删除项目及其所有关联数据，包括扫描版本、图谱、文档等，且无法恢复。', '删除项目', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
      inputPattern: new RegExp(`^${projectName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
      inputErrorMessage: `请输入项目名称"${projectName}"以确认删除`,
      showInput: true
    })
    await projectApi.delete(id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (e) {
    // cancelled
  }
}

const goToDetail = (id: string) => {
  router.push(`/projects/${id}`)
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.el-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
