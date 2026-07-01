<template>
  <div class="assertion-editor">
    <div class="assertion-header">
      <span>断言列表</span>
      <el-button type="primary" link size="small" @click="addAssertion">
        <el-icon><plus /></el-icon>
        添加断言
      </el-button>
    </div>

    <el-empty v-if="assertions.length === 0" description="暂无断言" />

    <div v-else class="assertion-list">
      <div v-for="(assertion, index) in assertions" :key="index" class="assertion-item">
        <el-card size="small">
          <div class="assertion-row">
            <span class="label">类型：</span>
            <el-select v-model="assertion.type" placeholder="选择断言类型" size="small" @change="handleTypeChange(index)">
              <el-option label="HTTP状态码" value="HTTP_STATUS" />
              <el-option label="JSON路径存在" value="JSON_PATH_NOT_NULL" />
              <el-option label="JSON路径等于" value="JSON_PATH" />
              <el-option label="数据库存在" value="DB_EXISTS" />
              <el-option label="数据库不存在" value="DB_NOT_EXISTS" />
              <el-option label="数据库行数" value="DB_COUNT" />
              <el-option label="数据库字段值" value="DB_FIELD_VALUE" />
            </el-select>
            <el-button
              type="danger"
              link
              size="small"
              @click="removeAssertion(index)"
            >
              删除
            </el-button>
          </div>

          <div class="assertion-row">
            <span class="label">表达式：</span>
            <el-input
              v-model="assertion.expression"
              :placeholder="getExpressionPlaceholder(assertion.type)"
              size="small"
              class="expression-input"
            />
          </div>

          <div class="assertion-row" v-if="needExpected(assertion.type)">
            <span class="label">预期值：</span>
            <el-input
              v-model="assertion.expected"
              placeholder="输入预期值"
              size="small"
              class="expression-input"
            />
          </div>

          <div class="assertion-row" v-if="needColumn(assertion.type)">
            <span class="label">列名：</span>
            <el-input
              v-model="assertion.column"
              placeholder="列名"
              size="small"
              class="expression-input"
            />
          </div>

          <div class="assertion-row" v-if="needTolerance(assertion.type)">
            <span class="label">容差：</span>
            <el-input-number
              v-model="assertion.tolerance"
              :min="0"
              :max="1"
              :step="0.01"
              size="small"
            />
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'

interface Assertion {
  type: string
  expression: string
  expected: any
  column?: string
  tolerance?: number
}

const props = defineProps<{
  modelValue: any[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: any[]): void
}>()

const assertions = ref<Assertion[]>(props.modelValue || [])

watch(assertions, () => {
  emit('update:modelValue', assertions.value)
}, { deep: true })

function addAssertion() {
  assertions.value.push({
    type: 'HTTP_STATUS',
    expression: '',
    expected: 200,
    tolerance: 0
  })
}

function removeAssertion(index: number) {
  assertions.value.splice(index, 1)
}

function handleTypeChange(index: number) {
  // 重置默认值
  const assertion = assertions.value[index]
  if (assertion.type === 'HTTP_STATUS') {
    assertion.expected = 200
  }
}

function getExpressionPlaceholder(type: string): string {
  switch (type) {
    case 'HTTP_STATUS':
      return '不需要'
    case 'JSON_PATH':
    case 'JSON_PATH_NOT_NULL':
      return '例如: $.data.id'
    case 'DB_EXISTS':
    case 'DB_NOT_EXISTS':
    case 'DB_COUNT':
      return 'SELECT * FROM table WHERE condition'
    case 'DB_FIELD_VALUE':
      return 'SELECT column FROM table WHERE condition'
    default:
      return '输入表达式'
  }
}

function needExpected(type: string): boolean {
  return ['HTTP_STATUS', 'JSON_PATH', 'DB_COUNT', 'DB_FIELD_VALUE'].includes(type)
}

function needColumn(type: string): boolean {
  return type === 'DB_FIELD_VALUE'
}

function needTolerance(type: string): boolean {
  return type === 'DB_COUNT'
}
</script>

<style scoped>
.assertion-editor {
  margin-bottom: 16px;
}

.assertion-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  font-weight: 600;
}

.assertion-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.assertion-item {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}

.assertion-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.assertion-row:last-child {
  margin-bottom: 0;
}

.label {
  min-width: 70px;
  font-size: 14px;
  color: #606266;
}

.expression-input {
  flex: 1;
}
</style>
