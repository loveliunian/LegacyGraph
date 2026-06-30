<template>
  <div class="drift-queue">
    <div class="filter-bar">
      <el-radio-group v-model="driftFilter" size="small" @change="loadDrift">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="static_only">仅静态</el-radio-button>
        <el-radio-button value="dynamic_only">仅运行时</el-radio-button>
        <el-radio-button value="doc_only">仅文档</el-radio-button>
        <el-radio-button value="low_confidence">低置信度</el-radio-button>
        <el-radio-button value="test_failed">测试失败</el-radio-button>
      </el-radio-group>
    </div>

    <el-table :data="driftItems" v-loading="loading" stripe size="small" empty-text="暂无漂移项 — 图谱与实际一致">
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag :type="driftTypeTag(row.driftType)" size="small">{{ driftTypeLabel(row.driftType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="elementName" label="元素" min-width="200" />
      <el-table-column label="描述" min-width="200">
        <template #default="{ row }">{{ row.description || row.elementName }}</template>
      </el-table-column>
      <el-table-column label="严重度" width="90">
        <template #default="{ row }">
          <el-tag :type="severityTag(row.severity)" size="small">{{ row.severity || 'MEDIUM' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="createReview(row)">创建审核</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 统计摘要 -->
    <div class="drift-summary" v-if="driftSummary">
      <el-alert type="info" :closable="false">
        <template #title>
          漂移统计：仅静态 {{ driftSummary.staticOnly || 0 }} ·
          仅运行时 {{ driftSummary.dynamicOnly || 0 }} ·
          仅文档 {{ driftSummary.docOnly || 0 }} ·
          低置信度 {{ driftSummary.lowConfidence || 0 }}
        </template>
      </el-alert>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { graphApi } from '@/api'
import { post } from '@/utils/request'

const props = defineProps<{ projectId: string; versionId: string }>()

const loading = ref(false)
const driftFilter = ref('all')
const driftItems = ref<any[]>([])
const driftSummary = ref<any>(null)

function driftTypeTag(t: string) {
  const m: Record<string, string> = {
    static_only: 'warning', dynamic_only: 'info', doc_only: '',
    low_confidence: 'danger', test_failed: 'danger'
  }
  return m[t] || 'info'
}
function driftTypeLabel(t: string) {
  const m: Record<string, string> = {
    static_only: '仅静态', dynamic_only: '仅运行时', doc_only: '仅文档',
    low_confidence: '低置信度', test_failed: '测试失败'
  }
  return m[t] || t
}
function severityTag(s: string) {
  if (s === 'HIGH') return 'danger'
  if (s === 'MEDIUM') return 'warning'
  return ''
}
function formatTime(t: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

async function loadDrift() {
  if (!props.projectId) return
  loading.value = true
  try {
    const type = driftFilter.value === 'all' ? undefined : driftFilter.value
    const res: any = await graphApi.getDriftQueue(props.projectId, type)
    driftItems.value = Array.isArray(res) ? res : (res?.items || [])
    driftSummary.value = res?.summary || null
  } catch {
    driftItems.value = []
  } finally { loading.value = false }
}

async function createReview(row: any) {
  try {
    await post(`/lg/projects/${props.projectId}/reviews`, {
      targetType: row.targetType || 'NODE',
      targetId: row.elementId || row.id,
      targetName: row.elementName,
      graphType: 'DRIFT',
      confidence: row.confidence ?? 0.5,
      priority: row.severity === 'HIGH' ? 'HIGH' : 'MEDIUM',
      comment: `漂移检测: ${driftTypeLabel(row.driftType)} — ${row.description || ''}`
    })
    ElMessage.success('审核任务已创建')
  } catch {
    ElMessage.error('创建审核失败')
  }
}

onMounted(() => { loadDrift() })
</script>

<style scoped>
.drift-queue { display: flex; flex-direction: column; gap: 12px; }
.filter-bar { display: flex; gap: 8px; flex-wrap: wrap; }
.drift-summary { font-size: 13px; }
</style>
