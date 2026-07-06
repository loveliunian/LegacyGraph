<template>
  <div class="dictionary-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>字典管理</span>
          <el-button
            type="primary"
            @click="handleCreateType">
            <el-icon><Plus /></el-icon>
            新增字典类型
          </el-button>
        </div>
      </template>

      <SearchForm
        :model="filterParams"
        @search="loadDictTypes"
        @reset="resetFilter">
        <el-form-item label="搜索">
          <el-input
            v-model="filterParams.keyword"
            placeholder="编码或名称"
            clearable
            style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="filterParams.status"
            placeholder="全部"
            clearable
            style="width: 120px">
            <el-option
              label="启用"
              value="ACTIVE" />
            <el-option
              label="禁用"
              value="DISABLED" />
          </el-select>
        </el-form-item>
      </SearchForm>

      <BaseTable
        :data="typeList"
        :loading="loading"
        :total="total"
        :page="pagination.pageNum"
        :page-size="pagination.pageSize"
        @update:page-size="handleSizeChange"
        @update:page="handleCurrentChange"
      >
        <el-table-column
          prop="dictCode"
          label="字典编码"
          width="160" />
        <el-table-column
          prop="dictName"
          label="字典名称"
          width="160" />
        <el-table-column
          prop="description"
          label="描述"
          min-width="180"
          show-overflow-tooltip />
        <el-table-column
          prop="sortOrder"
          label="排序"
          width="80"
          align="center" />
        <el-table-column
          prop="status"
          label="状态"
          width="90"
          align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.status === 'ACTIVE' ? 'success' : 'info'"
              size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="220"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              @click="handleManageItems(row)">
              管理字典项
            </el-button>
            <el-button
              link
              size="small"
              @click="handleEditType(row)">
              编辑
            </el-button>
            <el-button
              link
              size="small"
              type="danger"
              @click="handleDeleteType(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </BaseTable>
    </el-card>

    <!-- 字典类型 创建/编辑对话框 -->
    <el-dialog
      v-model="typeDialogVisible"
      :title="isTypeEdit ? '编辑字典类型' : '新增字典类型'"
      width="500px"
    >
      <el-form
        :model="typeForm"
        label-width="100px">
        <el-form-item
          label="字典编码"
          required>
          <el-input
            v-model="typeForm.dictCode"
            placeholder="例如: repo_type"
            :disabled="isTypeEdit" />
        </el-form-item>
        <el-form-item
          label="字典名称"
          required>
          <el-input
            v-model="typeForm.dictName"
            placeholder="例如: 仓库类型" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="typeForm.description"
            placeholder="字典用途说明"
            type="textarea"
            :rows="2" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="typeForm.sortOrder"
            :min="0"
            :max="1000" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="typeForm.status">
            <el-option
              label="启用"
              value="ACTIVE" />
            <el-option
              label="禁用"
              value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="saveType">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 字典项管理对话框 -->
    <el-dialog
      v-model="itemsDialogVisible"
      :title="`字典项管理 — ${currentDictType?.dictName || ''}`"
      width="700px"
      destroy-on-close
    >
      <div style="margin-bottom: 12px;">
        <el-button
          type="primary"
          size="small"
          @click="handleCreateItem">
          <el-icon><Plus /></el-icon>
          新增字典项
        </el-button>
      </div>

      <el-table
        v-loading="itemsLoading"
        :data="itemList"
        border
        size="small">
        <el-table-column
          prop="itemValue"
          label="项值"
          width="140" />
        <el-table-column
          prop="itemLabel"
          label="项标签"
          width="140" />
        <el-table-column
          prop="description"
          label="描述"
          min-width="140"
          show-overflow-tooltip />
        <el-table-column
          prop="sortOrder"
          label="排序"
          width="70"
          align="center" />
        <el-table-column
          prop="isDefault"
          label="默认"
          width="70"
          align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.isDefault"
              type="success"
              size="small">
              是
            </el-tag>
            <span
              v-else
              class="text-gray">-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              @click="handleEditItem(row)">
              编辑
            </el-button>
            <el-button
              link
              size="small"
              type="danger"
              @click="handleDeleteItem(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="itemsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 字典项 创建/编辑对话框 -->
    <el-dialog
      v-model="itemDialogVisible"
      :title="isItemEdit ? '编辑字典项' : '新增字典项'"
      width="500px"
    >
      <el-form
        :model="itemForm"
        label-width="100px">
        <el-form-item
          label="项值"
          required>
          <el-input
            v-model="itemForm.itemValue"
            placeholder="例如: FULLSTACK"
            :disabled="isItemEdit" />
        </el-form-item>
        <el-form-item
          label="项标签"
          required>
          <el-input
            v-model="itemForm.itemLabel"
            placeholder="例如: 全栈" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="itemForm.description"
            placeholder="说明" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="itemForm.sortOrder"
            :min="0"
            :max="1000" />
        </el-form-item>
        <el-form-item label="是否默认">
          <el-switch v-model="itemForm.isDefault" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="itemForm.status">
            <el-option
              label="启用"
              value="ACTIVE" />
            <el-option
              label="禁用"
              value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="saveItem">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { systemApi } from '@/api'
import type { DictType, DictItem } from '@/types'
import BaseTable from '@/components/common/BaseTable.vue'
import SearchForm from '@/components/common/SearchForm.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearDictCache } from '@/utils/dict'

// ==================== 字典类型管理 ====================
const loading = ref(false)
const typeList = ref<DictType[]>([])
const total = ref(0)
const pagination = ref({ pageNum: 1, pageSize: 20 })
const filterParams = ref({ keyword: '', status: undefined as string | undefined })
const saving = ref(false)

const typeDialogVisible = ref(false)
const isTypeEdit = ref(false)
const typeForm = ref<Partial<DictType>>({
  dictCode: '',
  dictName: '',
  description: '',
  sortOrder: 0,
  status: 'ACTIVE'
})

async function loadDictTypes() {
  loading.value = true
  try {
    const result = await systemApi.listDictTypes({
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      keyword: filterParams.value.keyword || undefined,
      status: filterParams.value.status
    })
    typeList.value = result.list
    total.value = result.total
  } catch (error) {
    console.error(error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filterParams.value.keyword = ''
  filterParams.value.status = undefined
  pagination.value.pageNum = 1
  loadDictTypes()
}

function handleSizeChange(size: number) {
  pagination.value.pageSize = size
  pagination.value.pageNum = 1
  loadDictTypes()
}

function handleCurrentChange(page: number) {
  pagination.value.pageNum = page
  loadDictTypes()
}

function handleCreateType() {
  isTypeEdit.value = false
  typeForm.value = { dictCode: '', dictName: '', description: '', sortOrder: 0, status: 'ACTIVE' }
  typeDialogVisible.value = true
}

function handleEditType(row: DictType) {
  isTypeEdit.value = true
  typeForm.value = { ...row }
  typeDialogVisible.value = true
}

async function saveType() {
  if (!typeForm.value.dictCode || !typeForm.value.dictName) {
    ElMessage.error('请填写必填项')
    return
  }
  saving.value = true
  try {
    if (isTypeEdit.value && typeForm.value.id) {
      await systemApi.updateDictType(typeForm.value.id, typeForm.value)
    } else {
      await systemApi.createDictType(typeForm.value)
    }
    ElMessage.success('保存成功')
    typeDialogVisible.value = false
    clearDictCache()
    loadDictTypes()
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDeleteType(row: DictType) {
  try {
    await ElMessageBox.confirm(
      `确认删除字典类型 "${row.dictName}"？将同时删除其下所有字典项。`,
      '提示',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await systemApi.deleteDictType(row.id)
    ElMessage.success('删除成功')
    clearDictCache()
    loadDictTypes()
  } catch {
    // cancelled
  }
}

// ==================== 字典项管理 ====================
const itemsDialogVisible = ref(false)
const itemsLoading = ref(false)
const itemList = ref<DictItem[]>([])
const currentDictType = ref<DictType | null>(null)

const itemDialogVisible = ref(false)
const isItemEdit = ref(false)
const itemForm = ref<Partial<DictItem>>({
  dictId: '',
  itemValue: '',
  itemLabel: '',
  description: '',
  sortOrder: 0,
  isDefault: false,
  status: 'ACTIVE'
})

async function handleManageItems(row: DictType) {
  currentDictType.value = row
  itemsLoading.value = true
  try {
    itemList.value = await systemApi.getDictItems(row.id)
  } catch (error) {
    console.error(error)
    ElMessage.error('加载字典项失败')
    itemList.value = []
  } finally {
    itemsLoading.value = false
  }
  itemsDialogVisible.value = true
}

function handleCreateItem() {
  isItemEdit.value = false
  itemForm.value = {
    dictId: currentDictType.value?.id || '',
    itemValue: '',
    itemLabel: '',
    description: '',
    sortOrder: 0,
    isDefault: false,
    status: 'ACTIVE'
  }
  itemDialogVisible.value = true
}

function handleEditItem(row: DictItem) {
  isItemEdit.value = true
  itemForm.value = { ...row }
  itemDialogVisible.value = true
}

async function saveItem() {
  if (!itemForm.value.itemValue || !itemForm.value.itemLabel) {
    ElMessage.error('请填写项值和项标签')
    return
  }
  saving.value = true
  try {
    if (isItemEdit.value && itemForm.value.id) {
      await systemApi.updateDictItem(itemForm.value.id, itemForm.value)
    } else {
      await systemApi.createDictItem(itemForm.value)
    }
    ElMessage.success('保存成功')
    itemDialogVisible.value = false
    clearDictCache()
    // 刷新项列表
    if (currentDictType.value) {
      itemList.value = await systemApi.getDictItems(currentDictType.value.id)
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDeleteItem(row: DictItem) {
  try {
    await ElMessageBox.confirm(
      `确认删除字典项 "${row.itemLabel}"？`,
      '提示',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await systemApi.deleteDictItem(row.id)
    ElMessage.success('删除成功')
    clearDictCache()
    if (currentDictType.value) {
      itemList.value = await systemApi.getDictItems(currentDictType.value.id)
    }
  } catch {
    // cancelled
  }
}

onMounted(() => {
  loadDictTypes()
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
.text-gray {
  color: #909399;
}
</style>
