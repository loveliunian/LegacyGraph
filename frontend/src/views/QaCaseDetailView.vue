<template>
  <div class="qa-case-detail-view">
    <div class="page-header">
      <button @click="goBack">返回</button>
      <h2>用例详情</h2>
    </div>

    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="testCase" class="case-detail">
      <div class="detail-row">
        <label>用例 ID</label>
        <span>{{ testCase.id }}</span>
      </div>
      <div class="detail-row">
        <label>问题</label>
        <span class="question">{{ testCase.question }}</span>
      </div>
      <div class="detail-row">
        <label>意图</label>
        <span>{{ testCase.intent || '-' }}</span>
      </div>
      <div class="detail-row">
        <label>状态</label>
        <span class="status-tag" :class="testCase.status">{{ testCase.status }}</span>
      </div>
      <div class="detail-row">
        <label>应拒答</label>
        <span>{{ testCase.shouldAbstain ? '是' : '否' }}</span>
      </div>
      <div class="detail-row">
        <label>期望实体</label>
        <div class="tag-list">
          <span v-for="e in (testCase.expectedEntities || [])" :key="e" class="tag">{{ e }}</span>
          <span v-if="!(testCase.expectedEntities || []).length" class="muted">无</span>
        </div>
      </div>
      <div class="detail-row">
        <label>期望关键词</label>
        <div class="tag-list">
          <span v-for="k in (testCase.expectedKeywords || [])" :key="k" class="tag">{{ k }}</span>
          <span v-if="!(testCase.expectedKeywords || []).length" class="muted">无</span>
        </div>
      </div>
      <div class="detail-row">
        <label>创建时间</label>
        <span>{{ testCase.createdAt || '-' }}</span>
      </div>
    </div>

    <div v-else class="empty">用例不存在</div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get } from '@/utils/request'

interface QaTestCase {
  id: string
  question: string
  intent?: string
  status: string
  shouldAbstain?: boolean
  expectedEntities?: string[]
  expectedKeywords?: string[]
  createdAt?: string
}

const route = useRoute()
const router = useRouter()
const projectId = ref<string>(String(route.params.projectId || ''))
const caseId = ref<string>(String(route.params.caseId || ''))

const testCase = ref<QaTestCase | null>(null)
const loading = ref(true)

const loadDetail = async () => {
  loading.value = true
  try {
    const res = await get(`/lg/projects/${projectId.value}/qa/cases/${caseId.value}`)
    testCase.value = res || null
  } finally {
    loading.value = false
  }
}

const goBack = () => router.back()

onMounted(loadDetail)
</script>

<style scoped>
.qa-case-detail-view { padding: 16px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
.case-detail { background: #fff; border: 1px solid #e0e0e0; border-radius: 4px; padding: 16px; }
.detail-row { display: flex; padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
.detail-row label { width: 100px; color: #666; flex-shrink: 0; }
.detail-row .question { font-weight: 500; }
.status-tag { padding: 2px 6px; border-radius: 3px; font-size: 12px; }
.status-tag.SMOKE { background: #e6f7ff; color: #1890ff; }
.status-tag.GOLDEN { background: #fff7e6; color: #fa8c16; }
.tag-list { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { background: #f5f5f5; padding: 2px 8px; border-radius: 3px; font-size: 12px; }
.muted { color: #999; }
.loading, .empty { padding: 32px; text-align: center; color: #999; }
button { cursor: pointer; }
</style>
