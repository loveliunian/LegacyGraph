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

      <test-case-form
        v-model="formData"
        :node-options="nodeOptions"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { testApi } from '@/api'
import TestCaseForm from '@/components/test/TestCaseForm.vue'
import type { TestCase } from '@/types'
import type { GraphNode } from '@/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()

const isEdit = computed(() => !!route.params.id)
const formData = ref<Partial<TestCase>>({
  caseCode: '',
  caseName: '',
  caseType: 'API',
  targetNodeId: '',
  preconditions: [],
  steps: undefined,
  expectedResult: undefined,
})

const nodeOptions = ref<{value: string; label: string}[]>([])
const saving = ref(false)

function goBack() {
  const projectId = projectStore.currentProjectId
  router.push(`/projects/${projectId}/test-cases`)
}

async function save() {
  if (!formData.value.caseCode || !formData.value.caseName) {
    ElMessage.error('请填写用例编码和名称')
    return
  }

  saving.value = true
  try {
    const projectId = projectStore.currentProjectId
    if (isEdit.value) {
      // TODO: update
      ElMessage.success('更新成功')
    } else {
      // await testApi.create(projectId, formData.value)
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

import { testApi } from '@/api'
import type { GraphNode } from '@/types'

// 加载节点列表用于选择
async function loadNodeOptions() {
  const projectId = projectStore.currentProjectId
  // TODO: 获取项目节点列表接口
  // 临时使用空数组，后续实现节点查询API
  nodeOptions.value = []
}

async function loadTestCaseData() {
  const projectId = projectStore.currentProjectId
  const caseId = route.params.id as string
  // TODO: 获取测试用例详情接口
  // const data = await testApi.getDetail(projectId, caseId)
  // formData.value = data
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
