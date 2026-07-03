<template>
  <div class="settings-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>系统配置</span>
          <el-button
            type="primary"
            size="small"
            @click="showCreateDialog">
            <el-icon><Plus /></el-icon>
            新建配置
          </el-button>
        </div>
      </template>

      <SearchForm
        :model="filterParams"
        @search="loadData"
        @reset="resetFilter">
        <el-form-item label="配置键">
          <el-input
            v-model="filterParams.configKey"
            placeholder="请输入配置键"
            clearable
            style="width: 250px" />
        </el-form-item>
      </SearchForm>

      <el-table
        v-loading="loading"
        :data="list"
        border
        style="width: 100%">
        <el-table-column
          prop="id"
          label="ID"
          width="100" />
        <el-table-column
          prop="configKey"
          label="配置键"
          width="200" />
        <el-table-column
          prop="configName"
          label="配置名称"
          width="180" />
        <el-table-column
          prop="configValue"
          label="配置值"
          min-width="250">
          <template #default="{ row }">
            <el-input
              v-if="editingId === row.id"
              v-model="row.configValue"
              @blur="handleSave(row)"
              @keyup.enter="handleSave(row)"
            />
            <span v-else>{{ row.configValue }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="configDesc"
          label="描述"
          min-width="200" />
        <el-table-column
          label="操作"
          width="150"
          fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="editingId !== row.id"
              link
              size="small"
              @click="startEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              link
              size="small"
              type="danger"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建配置对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建系统配置"
      width="500px"
      destroy-on-close>
      <el-form
        :model="formData"
        label-width="80px">
        <el-form-item
          label="配置键"
          required>
          <el-input
            v-model="formData.configKey"
            placeholder="如：scan.defaultTimeout" />
        </el-form-item>
        <el-form-item
          label="配置值"
          required>
          <el-input
            v-model="formData.configValue"
            placeholder="配置值" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="formData.configDesc"
            placeholder="配置说明"
            type="textarea" />
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { systemApi } from '@/api'
import SearchForm from '@/components/common/SearchForm.vue'
import type { SystemConfig } from '@/types'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

const loading = ref(false)
const list = ref<SystemConfig[]>([])
const editingId = ref<string | null>(null)
const dialogVisible = ref(false)
const submitting = ref(false)

const filterParams = ref({
  configKey: '',
})

const formData = reactive({
  configKey: '',
  configValue: '',
  configDesc: '',
})

async function loadData() {
  loading.value = true
  try {
    const result = await systemApi.listConfigs({
      pageNum: 1,
      pageSize: 100,
      configKey: filterParams.value.configKey,
    })
    list.value = result.list
  } catch (error) {
    console.error(error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filterParams.value.configKey = ''
  loadData()
}

function startEdit(row: SystemConfig) {
  editingId.value = row.id
}

async function handleSave(row: SystemConfig) {
  try {
    await systemApi.updateConfig(row.id, row)
    ElMessage.success('保存成功')
    editingId.value = null
    loadData()
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败')
  }
}

async function handleDelete(row: SystemConfig) {
  try {
    await ElMessageBox.confirm(`确定删除配置「${row.configKey}」吗？`, '确认删除', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await systemApi.deleteConfig(row.id)
    ElMessage.success('已删除')
    loadData()
  } catch {
    // cancelled
  }
}

function showCreateDialog() {
  formData.configKey = ''
  formData.configValue = ''
  formData.configDesc = ''
  dialogVisible.value = true
}

async function handleCreate() {
  if (!formData.configKey.trim()) {
    ElMessage.warning('请填写配置键')
    return
  }
  if (!formData.configValue.trim()) {
    ElMessage.warning('请填写配置值')
    return
  }
  submitting.value = true
  try {
    await systemApi.createConfig({
      configKey: formData.configKey,
      configValue: formData.configValue,
      configDesc: formData.configDesc,
    })
    ElMessage.success('创建成功')
    dialogVisible.value = false
    loadData()
  } catch {
    ElMessage.error('创建失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.settings-page {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
