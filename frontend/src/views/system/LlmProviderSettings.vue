<template>
  <div class="llm-provider-page">
    <el-card class="llm-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">🤖 LLM 提供商管理</span>
          <el-button
            type="primary"
            size="small"
            @click="showAddDialog">
            <el-icon><Plus /></el-icon>
            添加提供商
          </el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="providers"
        border
        stripe>
        <el-table-column
          label="状态"
          width="80"
          align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.isActive ? 'success' : 'info'"
              size="small"
              effect="plain"
            >
              {{ row.isActive ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="默认"
          width="80"
          align="center">
          <template #default="{ row }">
            <el-icon
              v-if="row.isDefault"
              color="var(--el-color-primary)"
              :size="20">
              <Check />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column
          prop="providerCode"
          label="提供商代码"
          width="160" />
        <el-table-column
          prop="modelId"
          label="模型"
          width="160" />
        <el-table-column
          prop="endpoint"
          label="API 端点"
          min-width="200" />
        <el-table-column
          prop="deploymentMode"
          label="部署方式"
          width="100" />
        <el-table-column
          label="操作"
          width="340"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              :loading="testingCode === row.providerCode"
              @click="handleTest(row)"
            >
              测试
            </el-button>
            <el-button
              v-if="!row.isDefault && row.isActive"
              link
              type="primary"
              size="small"
              @click="handleSetDefault(row)"
            >
              设为默认
            </el-button>
            <el-button
              link
              :type="row.isActive ? 'warning' : 'success'"
              size="small"
              @click="handleToggleActive(row)"
            >
              {{ row.isActive ? '禁用' : '启用' }}
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="showEditDialog(row)"
            >
              编辑
            </el-button>
            <el-popconfirm
              title="确定要删除此提供商吗？"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button
                  link
                  type="danger"
                  size="small">
                  删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? '添加 LLM 提供商' : '编辑 LLM 提供商'"
      width="600px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="110px"
        label-position="right"
      >
        <el-form-item
          label="提供商代码"
          prop="providerCode">
          <el-input
            v-model="form.providerCode"
            :disabled="dialogMode === 'edit'"
            placeholder="如 deepseek, openai" />
        </el-form-item>
        <el-form-item
          label="模型ID"
          prop="modelId">
          <el-input
            v-model="form.modelId"
            placeholder="如 deepseek-chat, gpt-4o" />
        </el-form-item>
        <el-form-item
          label="API 端点"
          prop="endpoint">
          <el-input
            v-model="form.endpoint"
            placeholder="如 https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item
          label="部署方式"
          prop="deploymentMode">
          <el-select
            v-model="form.deploymentMode"
            style="width: 100%">
            <el-option
              label="云服务"
              value="cloud" />
            <el-option
              label="私有部署"
              value="private" />
            <el-option
              label="混合部署"
              value="hybrid" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            placeholder="输入 API Key"
          />
        </el-form-item>
        <el-form-item label="Temperature">
          <el-slider
            v-model="form.temperature"
            :min="0"
            :max="2"
            :step="0.1"
            :marks="{ 0: '0', 0.5: '0.5', 1: '1.0', 1.5: '1.5', 2: '2.0' }"
            show-input
          />
        </el-form-item>
        <el-form-item label="Max Tokens">
          <el-input-number
            v-model="form.maxTokens"
            :min="256"
            :max="131072"
            :step="1024"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleSave">
          {{ dialogMode === 'add' ? '添加' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 测试结果对话框 -->
    <el-dialog
      v-model="testResultVisible"
      title="🔌 提供商连通性测试"
      width="520px"
    >
      <div v-if="testResult" class="test-result">
        <el-alert
          :type="testResult.success ? 'success' : 'error'"
          :closable="false"
          show-icon
        >
          <template #title>
            {{ testResult.success ? '✅ 测试成功' : '❌ 测试失败' }}
          </template>
          <div class="test-result-message">{{ testResult.message }}</div>
        </el-alert>

        <el-descriptions :column="2" border class="test-result-desc">
          <el-descriptions-item label="提供商代码">
            {{ testResult.providerCode }}
          </el-descriptions-item>
          <el-descriptions-item label="模型">
            {{ testResult.modelId }}
          </el-descriptions-item>
          <el-descriptions-item label="模型类型">
            <el-tag :type="testResult.type === 'embedding' ? 'warning' : 'primary'" size="small">
              {{ testResult.type === 'embedding' ? '向量模型' : '对话模型' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="响应耗时">
            {{ testResult.latencyMs }} ms
          </el-descriptions-item>
          <el-descriptions-item v-if="testResult.vectorDim !== undefined" label="向量维度">
            {{ testResult.vectorDim }}
          </el-descriptions-item>
          <el-descriptions-item v-if="testResult.responseSnippet" label="模型回复" :span="2">
            {{ testResult.responseSnippet }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="testResultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { llmApi, type LlmProvider, type LlmProviderTestResult } from '@/api/llm.api'
import { ElMessage } from 'element-plus'
import { Plus, Check } from '@element-plus/icons-vue'

const loading = ref(false)
const providers = ref<LlmProvider[]>([])
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const saving = ref(false)
const formRef = ref()

// 测试连通性相关状态
const testingCode = ref<string | null>(null)
const testResultVisible = ref(false)
const testResult = ref<LlmProviderTestResult | null>(null)

const form = reactive({
  providerCode: '',
  modelId: '',
  endpoint: '',
  deploymentMode: 'cloud',
  apiKey: '',
  temperature: 0.1,
  maxTokens: 8192,
  editingCode: ''
})

const rules = {
  providerCode: [{ required: true, message: '请输入提供商代码', trigger: 'blur' }],
  modelId: [{ required: true, message: '请输入模型ID', trigger: 'blur' }],
  endpoint: [{ required: true, message: '请输入API端点', trigger: 'blur' }],
  deploymentMode: [{ required: true, message: '请选择部署方式', trigger: 'change' }]
}

async function loadData() {
  loading.value = true
  try {
    providers.value = await llmApi.listAll()
  } catch (e: any) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.providerCode = ''
  form.modelId = ''
  form.endpoint = ''
  form.deploymentMode = 'cloud'
  form.apiKey = ''
  form.temperature = 0.1
  form.maxTokens = 8192
  form.editingCode = ''
}

function showAddDialog() {
  dialogMode.value = 'add'
  resetForm()
  dialogVisible.value = true
}

function showEditDialog(row: LlmProvider) {
  dialogMode.value = 'edit'
  const cfg = row.apiConfig || {}
  form.providerCode = row.providerCode
  form.modelId = row.modelId
  form.endpoint = row.endpoint
  form.deploymentMode = row.deploymentMode
  form.apiKey = cfg.api_key || ''
  form.temperature = cfg.temperature || 0.1
  form.maxTokens = cfg.max_tokens || 8192
  form.editingCode = row.providerCode
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await llmApi.save({
      providerCode: form.providerCode,
      modelId: form.modelId,
      endpoint: form.endpoint,
      deploymentMode: form.deploymentMode,
      apiConfig: {
        api_key: form.apiKey,
        temperature: form.temperature,
        max_tokens: form.maxTokens
      },
      isActive: true
    })
    ElMessage.success(dialogMode.value === 'add' ? '添加成功' : '更新成功')
    dialogVisible.value = false
    loadData()
  } catch (e: any) {
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function handleSetDefault(row: LlmProvider) {
  try {
    await llmApi.setDefault(row.providerCode)
    ElMessage.success(`已切换默认提供商为: ${row.providerCode}`)
    loadData()
  } catch (e: any) {
    console.error(e)
  }
}

async function handleToggleActive(row: LlmProvider) {
  try {
    await llmApi.toggleActive(row.providerCode, !row.isActive)
    ElMessage.success(row.isActive ? '已禁用' : '已启用')
    loadData()
  } catch (e: any) {
    console.error(e)
  }
}

async function handleDelete(row: LlmProvider) {
  try {
    await llmApi.delete(row.providerCode)
    ElMessage.success('已删除')
    loadData()
  } catch (e: any) {
    console.error(e)
  }
}

async function handleTest(row: LlmProvider) {
  testingCode.value = row.providerCode
  testResult.value = null
  try {
    const result = await llmApi.test(row.providerCode)
    testResult.value = result
    testResultVisible.value = true
    if (result.success) {
      ElMessage.success(`${row.providerCode} 连接正常（${result.latencyMs}ms）`)
    } else {
      ElMessage.warning(`${row.providerCode} 测试失败`)
    }
  } catch (e: any) {
    // 后端返回的业务错误（Result.error）也会走这里
    testResult.value = {
      providerCode: row.providerCode,
      modelId: row.modelId,
      type: row.providerCode.toLowerCase().includes('embedding') ? 'embedding' : 'chat',
      success: false,
      message: e?.message || '请求异常',
      latencyMs: 0
    }
    testResultVisible.value = true
    ElMessage.error(`${row.providerCode} 测试请求失败`)
  } finally {
    testingCode.value = null
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.llm-provider-page {
  padding: 20px;
}

.llm-card {
  max-width: 1200px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
}

.test-result {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.test-result-message {
  margin-top: 4px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-all;
}

.test-result-desc {
  margin-top: 4px;
}
</style>
