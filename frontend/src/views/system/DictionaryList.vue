<template>
  <div class="dictionary-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>字典管理</span>
          <el-button type="primary" @click="handleCreate">
            <el-icon><plus /></el-icon>
            新增字典
          </el-button>
        </div>
      </template>

      <SearchForm :model="filterParams" @search="loadData" @reset="resetFilter">
        <el-form-item label="字典类型">
          <el-input v-model="filterParams.dictType" placeholder="请输入字典类型" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filterParams.status" placeholder="全部" clearable style="width: 150px">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="禁用" value="INACTIVE" />
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
        <el-table-column prop="id" label="ID" width="100" />
        <el-table-column prop="dictType" label="字典类型" width="150" />
        <el-table-column prop="dictName" label="字典名称" width="180" />
        <el-table-column prop="dictValue" label="字典值" width="120" />
        <el-table-column prop="sort" label="排序" width="80" align="center" />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button link size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </BaseTable>

      <!-- 创建/编辑对话框 -->
      <el-dialog
        v-model="dialogVisible"
        :title="isEdit ? '编辑字典' : '新增字典'"
        width="500px"
      >
        <el-form :model="formData" label-width="80px">
          <el-form-item label="字典类型" required>
            <el-input v-model="formData.dictType" placeholder="例如: user_status" />
          </el-form-item>
          <el-form-item label="字典名称" required>
            <el-input v-model="formData.dictName" placeholder="例如: 用户状态" />
          </el-form-item>
          <el-form-item label="字典编码">
            <el-input v-model="formData.dictCode" placeholder="字典编码" />
          </el-form-item>
          <el-form-item label="字典值" required>
            <el-input v-model="formData.dictValue" placeholder="字典值" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="formData.sort" :min="0" :max="1000" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="formData.status">
              <el-option label="启用" value="ACTIVE" />
              <el-option label="禁用" value="INACTIVE" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="save" :loading="saving">保存</el-button>
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
import type { Dictionary } from '@/types'
import { ElMessage, ElMessageBox } from 'element-plus'

const loading = ref(false)
const list = ref<Dictionary[]>([])
const total = ref(0)
const pagination = ref({
  pageNum: 1,
  pageSize: 20,
})

const filterParams = ref({
  dictType: '',
  status: undefined as string | undefined,
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formData = ref<Partial<Dictionary>>({
  dictType: '',
  dictName: '',
  dictCode: '',
  dictValue: '',
  sort: 0,
  status: 'ACTIVE',
})

async function loadData() {
  loading.value = true
  try {
    const result = await systemApi.listDictionaries({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      dictType: filterParams.value.dictType,
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
  filterParams.value.dictType = ''
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
    dictType: '',
    dictName: '',
    dictCode: '',
    dictValue: '',
    sort: 0,
    status: 'ACTIVE',
  }
  dialogVisible.value = true
}

function handleEdit(row: Dictionary) {
  isEdit.value = true
  formData.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  if (!formData.value.dictType || !formData.value.dictName || !formData.value.dictValue) {
    ElMessage.error('请填写必填项')
    return
  }

  saving.value = true
  try {
    if (isEdit.value && formData.value.id) {
      await systemApi.updateDictionary(formData.value.id, formData.value)
    } else {
      await systemApi.createDictionary(formData.value)
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

async function handleDelete(row: Dictionary) {
  try {
    await ElMessageBox.confirm(`确认删除字典 "${row.dictName}"？`, '提示')
    await systemApi.deleteDictionary(row.id)
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
.dictionary-list {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
