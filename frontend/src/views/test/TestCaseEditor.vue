<template>
  <div class="test-case-editor">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ isEdit ? '编辑测试用例' : '新建测试用例' }}</span>
          <div>
            <el-button @click="save" type="primary" :loading="saving">保存</el-button>
            <el-button @click="goBack">取消</el-button>
          </div>
        </div>
      </template>

      <el-form :model="formData" label-width="120px" v-loading="loadingForm">
        <el-form-item label="用例编码" required>
          <el-input v-model="formData.caseCode" placeholder="例如: TC-ORDER-001" />
        </el-form-item>
        <el-form-item label="用例名称" required>
          <el-input v-model="formData.caseName" placeholder="例如: 创建订单-正常流程" />
        </el-form-item>
        <el-form-item label="用例类型">
          <el-select v-model="formData.caseType">
            <el-option label="API接口测试" value="API" />
            <el-option label="数据库断言" value="DB_ASSERTION" />
            <el-option label="权限测试" value="PERMISSION" />
            <el-option label="E2E测试" value="E2E" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联节点">
          <el-select v-model="formData.targetNodeId" filterable clearable placeholder="选择关联的图谱节点">
            <el-option v-for="node in nodeOptions" :key="node.value" :label="node.label" :value="node.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="前置条件">
          <el-input v-model="formData.preconditions" type="textarea" :rows="2" placeholder="可选，测试的前置条件" />
        </el-form-item>
        <el-form-item label="测试步骤">
          <el-input v-model="formData.steps" type="textarea" :rows="4" placeholder="测试步骤描述" />
        </el-form-item>
        <el-form-item label="期望结果">
          <el-input v-model="formData.expectedResult" type="textarea" :rows="3" placeholder="期望的测试结果" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { testApi, graphApi } from '@/api'

const router = useRouter()
const route = useRoute()

const isEdit = computed(() => !!route.params.id)
const projectId = route.params.projectId as string
const loadingForm = ref(false)

const formData = ref<any>({
  caseCode: '',
  caseName: '',
  caseType: 'API',
  targetNodeId: '',
  preconditions: '',
  steps: '',
  expectedResult: '',
})

const nodeOptions = ref<{value: string; label: string}[]>([])
const saving = ref(false)

function goBack() {
  router.push(`/projects/${projectId}/test-cases`)
}

async function loadNodeOptions() {
  if (!projectId) return
  try {
    // 从统一图谱获取节点列表，筛选出 API 端点类节点作为关联目标
    const data: any = await graphApi.getUnifiedGraph(projectId, '', 0.5)
    if (data && data.nodes) {
      const apiNodeTypes = ['ApiEndpoint', 'Feature', 'Table']
      nodeOptions.value = (data.nodes as any[])
        .filter((n: any) => apiNodeTypes.includes(n.type) || apiNodeTypes.includes(n.nodeType))
        .slice(0, 200)
        .map((n: any) => ({
          value: n.id || n.key,
          label: `${n.label || n.name || n.key} (${n.type || n.nodeType})`
        }))
    }
  } catch (e) {
    console.error('加载节点列表失败', e)
    nodeOptions.value = []
  }
}

async function loadTestCaseData() {
  const caseId = route.params.id as string
  if (!projectId || !caseId) return
  loadingForm.value = true
  try {
    const data: any = await testApi.getDetail(projectId, caseId)
    if (data) {
      formData.value = {
        caseCode: data.caseCode || '',
        caseName: data.caseName || '',
        caseType: data.caseType || 'API',
        targetNodeId: data.targetNodeId || '',
        preconditions: data.preconditions || '',
        steps: data.steps || '',
        expectedResult: data.expectedResult || '',
      }
    }
  } catch (e) {
    console.error('加载用例详情失败', e)
    ElMessage.error('加载测试用例详情失败')
  } finally {
    loadingForm.value = false
  }
}

async function save() {
  if (!formData.value.caseCode || !formData.value.caseName) {
    ElMessage.error('请填写用例编码和名称')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      const caseId = route.params.id as string
      await testApi.update(projectId, caseId, formData.value)
      ElMessage.success('更新成功')
    } else {
      await testApi.create(projectId, formData.value)
      ElMessage.success('创建成功')
    }
    goBack()
  } catch (error) {
    console.error(error)
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadNodeOptions()
  if (isEdit.value) {
    loadTestCaseData()
  }
})
</script>

<style scoped>
.test-case-editor {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
