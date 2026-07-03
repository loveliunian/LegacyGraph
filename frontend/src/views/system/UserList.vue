<template>
  <div class="user-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>用户管理</span>
          <el-button
            type="primary"
            @click="handleCreate">
            <el-icon><plus /></el-icon>
            新增用户
          </el-button>
        </div>
      </template>

      <SearchForm
        :model="filterParams"
        @search="loadData"
        @reset="resetFilter">
        <el-form-item label="用户名">
          <el-input
            v-model="filterParams.username"
            placeholder="请输入用户名"
            clearable
            style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="filterParams.status"
            placeholder="全部"
            clearable
            style="width: 150px">
            <el-option
              label="活跃"
              value="ACTIVE" />
            <el-option
              label="禁用"
              value="INACTIVE" />
          </el-select>
        </el-form-item>
      </SearchForm>

      <BaseTable
        :data="list"
        :loading="loading"
        :total="total"
        :page-num="pagination.pageNum"
        :page-size="pagination.pageSize"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      >
        <el-table-column
          prop="id"
          label="ID"
          width="180" />
        <el-table-column
          prop="username"
          label="用户名"
          width="150" />
        <el-table-column
          prop="nickname"
          label="昵称"
          width="150" />
        <el-table-column
          prop="email"
          label="邮箱"
          width="200" />
        <el-table-column
          prop="status"
          label="状态"
          width="100"
          align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '活跃' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="createdAt"
          label="创建时间"
          width="180" />
        <el-table-column
          label="操作"
          width="150"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              @click="handleEdit(row)">
              编辑
            </el-button>
            <el-button
              link
              size="small"
              type="danger"
              @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </BaseTable>

      <!-- 创建/编辑对话框 -->
      <el-dialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑用户' : '新增用户'"
        width="500px"
      >
        <el-form
          :model="formData"
          label-width="80px">
          <el-form-item
            label="用户名"
            required>
            <el-input
              v-model="formData.username"
              placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="昵称">
            <el-input
              v-model="formData.nickname"
              placeholder="请输入昵称" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input
              v-model="formData.email"
              placeholder="请输入邮箱" />
          </el-form-item>
          <el-form-item
            label="密码"
            :required="!isEdit">
            <el-input
              v-model="formData.password"
              type="password"
              placeholder="请输入密码" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="formData.status">
              <el-option
                label="活跃"
                value="ACTIVE" />
              <el-option
                label="禁用"
                value="INACTIVE" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="saving"
            @click="save">
            保存
          </el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { systemApi } from '@/api'
import BaseTable from '@/components/common/BaseTable.vue'
import SearchForm from '@/components/common/SearchForm.vue'
import type { User } from '@/types'
import { ElMessage, ElMessageBox } from 'element-plus'


const loading = ref(false)
const list = ref<User[]>([])
const total = ref(0)
const pagination = ref({
  pageNum: 1,
  pageSize: 20,
})

const filterParams = ref({
  username: '',
  status: undefined as string | undefined,
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formData = ref<Partial<User>>({
  username: '',
  nickname: '',
  email: '',
  password: '',
  status: 'ACTIVE',
  permissions: [],
})

async function loadData() {
  loading.value = true
  try {
    const result = await systemApi.listUsers({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: filterParams.value.username,
      status: filterParams.value.status,
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    console.error(error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filterParams.value.username = ''
  filterParams.value.status = undefined
  pagination.value.pageNum = 1
  loadData()
}

function handleSizeChange(size: number) {
  pagination.value.pageSize = size
  pagination.value.pageNum = 1
  loadData()
}

function handleCurrentChange(page: number) {
  pagination.value.pageNum = page
  loadData()
}

function handleCreate() {
  isEdit.value = false
  formData.value = {
    username: '',
    nickname: '',
    email: '',
    password: '',
    status: 'ACTIVE',
    permissions: [],
  }
  dialogVisible.value = true
}

function handleEdit(row: User) {
  isEdit.value = true
  formData.value = { ...row }
  formData.value.password = '' // 密码不回显
  dialogVisible.value = true
}

async function save() {
  if (!formData.value.username) {
    ElMessage.error('请输入用户名')
    return
  }
  if (!isEdit.value && !formData.value.password) {
    ElMessage.error('请输入密码')
    return
  }

  saving.value = true
  try {
    if (isEdit.value && formData.value.id) {
      await systemApi.updateUser(formData.value.id, formData.value)
    } else {
      await systemApi.createUser(formData.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: User) {
  try {
    await ElMessageBox.confirm(`确认删除用户 "${row.username}"？`, '提示')
    await systemApi.deleteUser(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch {
    // cancelled
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.user-list {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
