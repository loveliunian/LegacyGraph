<template>
  <div class="settings-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>系统配置</span>
        </div>
      </template>

      <SearchForm @search="loadData" @reset="resetFilter">
        <el-form-item label="配置键">
          <el-input v-model="filterParams.configKey" placeholder="请输入配置键" clearable style="width: 250px" />
        </el-form-item>
      </SearchForm>

      <el-table :data="list" v-loading="loading" border style="width: 100%">
        <el-table-column prop="id" label="ID" width="100" />
        <el-table-column prop="configKey" label="配置键" width="200" />
        <el-table-column prop="configName" label="配置名称" width="180" />
        <el-table-column prop="configValue" label="配置值" min-width="250">
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
        <el-table-column prop="configDesc" label="描述" min-width="200" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="editingId !== row.id"
              link
              size="small"
              @click="startEdit(row)"
            >
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { systemApi } from '@/api'
import SearchForm from '@/components/common/SearchForm.vue'
import type { SystemConfig } from '@/types'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const list = ref<SystemConfig[]>([])
const editingId = ref<string | null>(null)

const filterParams = ref({
  configKey: '',
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
