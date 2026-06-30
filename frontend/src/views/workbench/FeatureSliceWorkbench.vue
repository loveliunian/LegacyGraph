<template>
  <div class="slice-workbench">
    <div class="toolbar">
      <el-select v-model="selectedSlice" placeholder="选择功能切片" clearable style="width: 280px" @change="loadSliceDetail">
        <el-option v-for="s in slices" :key="s.sliceId || s.id" :label="s.name || s.featureName" :value="s.sliceId || s.id" />
      </el-select>
      <el-button :loading="loading" @click="refreshSlices">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <!-- 未选择切片时显示概览 -->
    <div v-if="!selectedSlice" class="overview">
      <el-empty v-if="slices.length === 0" description="暂无功能切片数据" />
      <el-table v-else :data="slices" stripe size="small" @row-click="selectSlice">
        <el-table-column prop="name" label="切片名称" min-width="180" />
        <el-table-column prop="featureName" label="功能" width="120" />
        <el-table-column label="覆盖状态" width="100">
          <template #default="{ row }">
            <el-tag :type="coverageTagType(row.coverageStatus)" size="small">
              {{ coverageLabel(row.coverageStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="80">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">{{ ((row.confidence ?? 0) * 100).toFixed(0) }}%</template>
        </el-table-column>
        <el-table-column label="证据来源" min-width="120">
          <template #default="{ row }">{{ (row.evidenceSources || []).join(', ') }}</template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 选中切片后显示详情 -->
    <div v-if="selectedSlice && sliceDetail" class="detail">
      <el-alert :title="sliceDetail.name" :description="sliceDetail.featureName" type="info" :closable="false" />
      <div class="path-chain">
        <div v-for="(step, idx) in pathSteps" :key="idx" class="path-step">
          <el-card shadow="hover">
            <template #header>
              <span class="step-type">{{ step.label }}</span>
            </template>
            <el-tag v-for="id in step.ids" :key="id" size="small" class="step-tag">{{ id }}</el-tag>
            <span v-if="(step.ids || []).length === 0" class="empty-hint">—</span>
          </el-card>
          <div v-if="idx < pathSteps.length - 1" class="arrow">↓</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { graphApi } from '@/api'

const props = defineProps<{
  projectId: string
  versionId: string
}>()

const loading = ref(false)
const slices = ref<any[]>([])
const selectedSlice = ref<string | null>(null)
const sliceDetail = ref<any>(null)

const pathSteps = computed(() => {
  if (!sliceDetail.value) return []
  const d = sliceDetail.value
  return [
    { label: '用户/功能', ids: [d.featureName].filter(Boolean) },
    { label: '页面(Page)', ids: d.pageIds || [] },
    { label: 'API 接口', ids: d.apiIds || [] },
    { label: '方法(Method)', ids: d.methodIds || [] },
    { label: 'SQL 语句', ids: d.sqlIds || [] },
    { label: '数据库表(Table)', ids: d.tableIds || [] },
    { label: '权限(Permission)', ids: d.permissionIds || [] },
  ]
})

async function refreshSlices() {
  if (!props.projectId || !props.versionId) return
  loading.value = true
  try {
    const res: any = await graphApi.getFeatureSlices(props.projectId, props.versionId)
    slices.value = Array.isArray(res) ? res : (res?.list || [])
  } catch {
    slices.value = []
  } finally {
    loading.value = false
  }
}

function selectSlice(row: any) {
  selectedSlice.value = row.sliceId || row.id
  loadSliceDetail()
}

async function loadSliceDetail() {
  if (!selectedSlice.value || !props.projectId) return
  try {
    sliceDetail.value = await graphApi.getFeatureSliceDetail(props.projectId, selectedSlice.value)
  } catch { sliceDetail.value = null }
}

function coverageTagType(s: string) {
  if (s === 'COVERED') return 'success'
  if (s === 'PARTIAL') return 'warning'
  return 'danger'
}
function coverageLabel(s: string) {
  if (s === 'COVERED') return '已覆盖'
  if (s === 'PARTIAL') return '部分覆盖'
  return '未覆盖'
}
function riskTagType(r: string) {
  if (r === 'HIGH') return 'danger'
  if (r === 'MEDIUM') return 'warning'
  return 'success'
}

onMounted(() => { refreshSlices() })
</script>

<style scoped>
.slice-workbench { display: flex; flex-direction: column; gap: 12px; height: 100%; overflow: auto; }
.toolbar { display: flex; gap: 8px; align-items: center; }
.path-chain { display: flex; flex-direction: column; gap: 4px; margin-top: 12px; }
.path-step { text-align: center; }
.step-type { font-weight: 600; font-size: 14px; }
.step-tag { margin: 2px; }
.arrow { font-size: 18px; color: var(--el-color-primary); }
.empty-hint { color: var(--el-text-color-placeholder); }
</style>
