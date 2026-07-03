<template>
  <el-form
    :model="form"
    label-width="100px">
    <el-form-item
      label="用例编码"
      required>
      <el-input
        v-model="form.caseCode"
        placeholder="例如: user-login-api-test" />
    </el-form-item>

    <el-form-item
      label="用例名称"
      required>
      <el-input
        v-model="form.caseName"
        placeholder="例如: 用户登录接口测试" />
    </el-form-item>

    <el-form-item label="用例类型">
      <el-select
        v-model="form.caseType"
        placeholder="选择测试类型">
        <el-option
          label="API测试"
          value="API" />
        <el-option
          label="DB断言"
          value="DB_ASSERTION" />
        <el-option
          label="权限测试"
          value="PERMISSION" />
        <el-option
          label="E2E测试"
          value="E2E" />
      </el-select>
    </el-form-item>

    <el-form-item label="目标节点">
      <el-select
        v-model="form.targetNodeId"
        placeholder="选择关联的图谱节点"
        filterable
        :options="nodeOptions"
      >
        <el-option
          v-for="option in nodeOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </el-form-item>

    <el-divider content-position="left">前置条件</el-divider>
    <div class="preconditions">
      <div
        v-for="(pre, index) in form.preconditions"
        :key="index"
        class="precondition-item">
        <el-select
          v-model="pre.type"
          placeholder="前置条件类型"
          size="small">
          <el-option
            label="登录获取Token"
            value="LOGIN" />
          <el-option
            label="环境变量"
            value="ENV" />
        </el-select>
        <el-input
          v-model="pre.value"
          placeholder="值"
          size="small"
          class="pre-value" />
        <el-button
          type="danger"
          link
          size="small"
          @click="removePrecondition(index)">
          删除
        </el-button>
        <el-button
          type="primary"
          link
          size="small"
          @click="addPrecondition">
          <el-icon><plus /></el-icon>
        </el-button>
      </div>
      <el-button
        v-if="form.preconditions.length === 0"
        type="primary"
        plain
        size="small"
        @click="addPrecondition"
      >
        <el-icon><plus /></el-icon>
        添加前置条件
      </el-button>
    </div>

    <el-divider content-position="left">API调用</el-divider>
    <el-row :gutter="20">
      <el-col :span="6">
        <el-form-item label="Method">
          <el-select v-model="apiStep.method">
            <el-option
              label="GET"
              value="GET" />
            <el-option
              label="POST"
              value="POST" />
            <el-option
              label="PUT"
              value="PUT" />
            <el-option
              label="DELETE"
              value="DELETE" />
            <el-option
              label="PATCH"
              value="PATCH" />
          </el-select>
        </el-form-item>
      </el-col>
      <el-col :span="18">
        <el-form-item label="Path">
          <el-input
            v-model="apiStep.path"
            placeholder="/api/path" />
        </el-form-item>
      </el-col>
    </el-row>

    <el-form-item label="Headers">
      <div class="key-value-editor">
        <div
          v-for="(header, index) in apiStep.headers"
          :key="index"
          class="kv-item">
          <el-input
            v-model="header.key"
            placeholder="Key"
            size="small"
            style="width: 150px" />
          <el-input
            v-model="header.value"
            placeholder="Value"
            size="small"
            class="flex-fill" />
          <el-button
            type="danger"
            link
            size="small"
            @click="removeHeader(index)">
            <el-icon><delete /></el-icon>
          </el-button>
          <el-button
            type="primary"
            link
            size="small"
            @click="addHeader">
            <el-icon><plus /></el-icon>
          </el-button>
        </div>
        <el-button
          v-if="Object.keys(apiStep.headers).length === 0"
          type="primary"
          plain
          size="small"
          @click="addHeader"
        >
          <el-icon><plus /></el-icon>
          添加Header
        </el-button>
      </div>
    </el-form-item>

    <el-form-item label="Body">
      <el-input
        v-model="apiStep.bodyText"
        type="textarea"
        :rows="6"
        placeholder="JSON 请求体"
      />
    </el-form-item>

    <el-divider content-position="left">断言</el-divider>
    <assertion-editor v-model="assertions" />
  </el-form>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { Plus, Delete } from '@element-plus/icons-vue'
import AssertionEditor from './AssertionEditor.vue'
import type { TestCase, NodeOption } from '@/types'


interface KeyValue {
  key: string
  value: string
}

interface ApiStep {
  method: string
  path: string
  headers: KeyValue[]
  bodyText: string
}

const props = defineProps<{
  modelValue: Partial<TestCase>
  nodeOptions: NodeOption[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Partial<TestCase>): void
}>()

const form = ref<any>({
  caseCode: '',
  caseName: '',
  caseType: 'API',
  targetNodeId: undefined,
  preconditions: [],
  ...props.modelValue
})

const nodeOptions = computed(() => props.nodeOptions)

const preconditions = computed({
  get: () => form.value.preconditions || [],
  set: (val) => {
    form.value.preconditions = val
    emit('update:modelValue', form.value)
  }
})

const assertions = computed({
  get: () => {
    // 如果有断言，解析成数组，否则返回空
    if (!form.value.expectedResult) return []
    try {
      const expected = JSON.parse(form.value.expectedResult)
      return expected.assertions || []
    } catch {
      return []
    }
  },
  set: (val) => {
    if (form.value.expectedResult) {
      try {
        const expected = JSON.parse(form.value.expectedResult)
        expected.assertions = val
        form.value.expectedResult = JSON.stringify(expected)
      } catch {
        form.value.expectedResult = JSON.stringify({ assertions: val })
      }
    } else {
      form.value.expectedResult = JSON.stringify({ assertions: val })
    }
    emit('update:modelValue', form.value)
  }
})

const apiStep = ref<ApiStep>({
  method: 'GET',
  path: '',
  headers: [],
  bodyText: ''
})

// 同步apiStep到form
function syncApiStep() {
  const steps = form.value.steps ? JSON.parse(form.value.steps || '[]') : []
  // 找到API调用步骤
  const apiStepIndex = steps.findIndex((s: any) => s.action === 'CALL_API')
  if (apiStepIndex >= 0) {
    steps[apiStepIndex] = {
      action: 'CALL_API',
      method: apiStep.value.method,
      path: apiStep.value.path,
      headers: Object.fromEntries(
        apiStep.value.headers.map(h => [h.key, h.value])
      ),
      body: apiStep.value.bodyText ? JSON.parse(apiStep.value.bodyText) : null
    }
  } else {
    steps.push({
      action: 'CALL_API',
      method: apiStep.value.method,
      path: apiStep.value.path,
      headers: Object.fromEntries(
        apiStep.value.headers.map(h => [h.key, h.value])
      ),
      body: apiStep.value.bodyText ? JSON.parse(apiStep.value.bodyText) : null
    })
  }
  form.value.steps = JSON.stringify(steps)
  emit('update:modelValue', form.value)
}

function addPrecondition() {
  preconditions.value.push({
    type: 'LOGIN',
    value: ''
  })
}

function removePrecondition(index: number) {
  preconditions.value.splice(index, 1)
}

function addHeader() {
  apiStep.value.headers.push({
    key: '',
    value: ''
  })
  syncApiStep()
}

function removeHeader(index: number) {
  apiStep.value.headers.splice(index, 1)
  syncApiStep()
}

// 初始化，如果已有步骤，解析出来
if (form.value.steps) {
  try {
    const steps = JSON.parse(form.value.steps)
    const apiStep = steps.find((s: any) => s.action === 'CALL_API')
    if (apiStep) {
      apiStep.value.method = apiStep.method || 'GET'
      apiStep.value.path = apiStep.path || ''
      if (apiStep.headers) {
        apiStep.value.headers = Object.entries(apiStep.headers).map(([k, v]) => ({
          key: k,
          value: v as string
        }))
      }
      if (apiStep.body) {
        apiStep.value.bodyText = JSON.stringify(apiStep.body, null, 2)
      }
    }
  } catch {
    // ignore
  }
}
</script>

<style scoped>
.preconditions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.precondition-item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.pre-value {
  flex: 1;
}

.key-value-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kv-item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.flex-fill {
  flex: 1;
}
</style>
