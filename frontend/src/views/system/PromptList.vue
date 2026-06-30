<template>
  <div class="prompt-mgr">
    <div class="page-header">
      <h3>提示词模板管理</h3>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新增模板
        </el-button>
        <el-button @click="refreshCache">
          <el-icon><Refresh /></el-icon> 刷新缓存
        </el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索模板编码"
        clearable
        style="width: 200px"
        @clear="loadData"
        @keyup.enter="loadData"
      />
      <el-select v-model="filterScene" placeholder="场景" clearable style="width: 150px" @change="loadData">
        <el-option label="全部场景" value="" />
        <el-option label="code" value="code" />
        <el-option label="doc" value="doc" />
        <el-option label="merge" value="merge" />
        <el-option label="test" value="test" />
        <el-option label="review" value="review" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 120px" @change="loadData">
        <el-option label="全部" value="" />
        <el-option label="启用" value="active" />
        <el-option label="停用" value="inactive" />
      </el-select>
      <el-button type="primary" @click="loadData">查询</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="tableData" v-loading="loading" stripe>
      <el-table-column prop="templateCode" label="模板编码" min-width="160" />
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="scene" label="场景" width="80">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.scene || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容预览" min-width="300">
        <template #default="{ row }">
          <span class="preview-text">{{ getPreview(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
            {{ row.isActive ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.isActive ? 'warning' : 'success'" size="small" @click="toggleActive(row)">
            {{ row.isActive ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" size="small" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pageNum"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @size-change="loadData"
      @current-change="loadData"
      style="margin-top: 16px; justify-content: flex-end"
    />

    <!-- 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑提示词模板' : '新增提示词模板'"
      width="800px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="模板编码" prop="templateCode">
              <el-input v-model="form.templateCode" :disabled="isEditing" placeholder="如 sql-advisor" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="场景" prop="scene">
              <el-select v-model="form.scene" placeholder="选择场景" clearable>
                <el-option label="code" value="code" />
                <el-option label="doc" value="doc" />
                <el-option label="merge" value="merge" />
                <el-option label="test" value="test" />
                <el-option label="review" value="review" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="启用">
              <el-switch v-model="form.isActive" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="系统角色提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="定义 LLM 的系统角色和背景" />
        </el-form-item>
        <el-form-item label="领域知识提示词">
          <el-input v-model="form.domainPrompt" type="textarea" :rows="4" placeholder="注入领域知识、术语解释等" />
        </el-form-item>
        <el-form-item label="任务指令提示词">
          <el-input v-model="form.taskPrompt" type="textarea" :rows="6" placeholder="具体的任务指令和约束条件" />
        </el-form-item>
        <el-form-item label="输出格式 Schema">
          <el-input v-model="form.outputSchema" type="textarea" :rows="4" placeholder="JSON Schema，指导 LLM 输出格式" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave" :loading="saving">
          {{ isEditing ? '保存为新版本' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { promptApi, type PromptTemplate } from '@/api/prompt.api'

const loading = ref(false)
const saving = ref(false)
const tableData = ref<PromptTemplate[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const searchKeyword = ref('')
const filterScene = ref('')
const filterStatus = ref('')
const dialogVisible = ref(false)
const isEditing = ref(false)
const formRef = ref()
const form = reactive<PromptTemplate>({
  templateCode: '',
  scene: '',
  systemPrompt: '',
  domainPrompt: '',
  taskPrompt: '',
  outputSchema: '',
  isActive: true,
})
const rules = {
  templateCode: [{ required: true, message: '模板编码不能为空', trigger: 'blur' }],
}

// 加载数据
async function loadData() {
  loading.value = true
  try {
    const res: any = await promptApi.list({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: searchKeyword.value || undefined,
      scene: filterScene.value || undefined,
      status: filterStatus.value || undefined,
    })
    if (res?.list) {
      tableData.value = res.list
      total.value = res.total || res.list.length
    } else {
      tableData.value = []
      total.value = 0
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

// 预览文本
function getPreview(row: PromptTemplate) {
  return (row.taskPrompt || row.systemPrompt || '').substring(0, 80)
}

// 新增
function openCreate() {
  isEditing.value = false
  form.templateCode = ''
  form.scene = ''
  form.systemPrompt = ''
  form.domainPrompt = ''
  form.taskPrompt = ''
  form.outputSchema = ''
  form.isActive = true
  dialogVisible.value = true
}

// 编辑
function openEdit(row: PromptTemplate) {
  isEditing.value = true
  form.templateCode = row.templateCode
  form.scene = row.scene || ''
  form.systemPrompt = row.systemPrompt || ''
  form.domainPrompt = row.domainPrompt || ''
  form.taskPrompt = row.taskPrompt || ''
  form.outputSchema = row.outputSchema || ''
  form.isActive = row.isActive ?? true
  dialogVisible.value = true
}

// 保存
async function doSave() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (isEditing.value) {
      // 通过 templateCode 找到对应 DB 记录的 id
      const active = tableData.value.find(t => t.templateCode === form.templateCode && t.isActive)
      if (active?.id) {
        await promptApi.update(active.id, form)
        ElMessage.success('已创建新版本')
      } else {
        ElMessage.warning('未找到可更新的记录')
      }
    } else {
      await promptApi.create(form)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadData()
  } catch (e: any) {
    console.error(e)
    if (e?.response?.data?.message) {
      ElMessage.error(e.response.data.message)
    }
  } finally {
    saving.value = false
  }
}

// 切换激活状态
async function toggleActive(row: PromptTemplate) {
  if (!row.id) return
  try {
    await promptApi.toggleActive(row.id)
    ElMessage.success(row.isActive ? '已停用' : '已启用')
    await loadData()
  } catch (e) {
    console.error(e)
  }
}

// 删除
async function doDelete(row: PromptTemplate) {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确定要删除「${row.templateCode}」吗？`, '删除提示', { type: 'warning' })
    await promptApi.delete(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (e) {
    // 取消不处理
  }
}

// 刷新缓存
async function refreshCache() {
  try {
    await promptApi.refreshCache()
    ElMessage.success('缓存已刷新')
  } catch (e) {
    console.error(e)
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.prompt-mgr {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h3 { margin: 0; }
}

.header-actions {
  display: flex;
  gap: 8px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
}

.preview-text {
  color: #909399;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
