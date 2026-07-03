<template>
  <div class="quality-panel">
    <div class="stats-grid">
      <!-- 节点统计 -->
      <el-card shadow="hover">
        <template #header>节点统计</template>
        <div class="stat-row"><span>总节点</span><strong>{{ stats.totalNodes ?? 0 }}</strong></div>
        <div class="stat-row"><span>已确认</span><strong class="green">{{ stats.confirmedNodes ?? 0 }}</strong></div>
        <div class="stat-row"><span>待确认</span><strong class="orange">{{ stats.pendingNodes ?? 0 }}</strong></div>
        <div class="stat-row"><span>平均置信度</span><strong>{{ ((stats.avgConfidence ?? 0) * 100).toFixed(1) }}%</strong></div>
      </el-card>

      <!-- 边统计 -->
      <el-card shadow="hover">
        <template #header>边统计</template>
        <div class="stat-row"><span>总边数</span><strong>{{ stats.totalEdges ?? 0 }}</strong></div>
        <div class="stat-row"><span>已确认</span><strong class="green">{{ stats.confirmedEdges ?? 0 }}</strong></div>
        <div class="stat-row"><span>待确认</span><strong class="orange">{{ stats.pendingEdges ?? 0 }}</strong></div>
      </el-card>

      <!-- 质量问题 -->
      <el-card
        shadow="hover"
        header="质量问题">
        <div class="stat-row">
          <span>无证据节点</span>
          <strong class="red">{{ stats.noEvidenceNodes ?? 0 }}</strong>
          <el-tag
            v-if="(stats.noEvidenceNodes ?? 0) > 0"
            size="small"
            type="danger">
            需关注
          </el-tag>
        </div>
        <div class="stat-row">
          <span>无证据边</span>
          <strong class="red">{{ stats.noEvidenceEdges ?? 0 }}</strong>
        </div>
        <div class="stat-row">
          <span>AI-only 节点</span>
          <strong class="orange">{{ stats.aiOnlyNodes ?? 0 }}</strong>
          <el-tag
            v-if="stats.aiOnlyNodes > 0"
            size="small"
            type="warning">
            待审核
          </el-tag>
        </div>
        <div class="stat-row">
          <span>AI-only 边</span>
          <strong class="orange">{{ stats.aiOnlyEdges ?? 0 }}</strong>
        </div>
        <div class="stat-row">
          <span>Runtime-only 边</span>
          <strong class="blue">{{ stats.runtimeOnlyEdges ?? 0 }}</strong>
        </div>
      </el-card>

      <!-- 覆盖指标 -->
      <el-card
        shadow="hover"
        header="覆盖指标">
        <div class="stat-row">
          <span>有证据节点率</span>
          <strong>{{ evidenceRate }}%</strong>
          <el-progress
            :percentage="evidenceRate"
            :stroke-width="6" />
        </div>
        <div class="stat-row">
          <span>节点确认率</span>
          <strong>{{ confirmationRate }}%</strong>
          <el-progress
            :percentage="confirmationRate"
            :stroke-width="6"
            :color="confirmationColor" />
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { graphApi } from '@/api'

const props = defineProps<{ projectId: string; versionId: string }>()

const stats = ref<Record<string, number>>({})

const evidenceRate = computed(() => {
  const total = stats.value.totalNodes || 1
  const withEv = stats.value.withEvidenceCount || 0
  return Math.round((withEv / total) * 100)
})
const confirmationRate = computed(() => {
  const total = stats.value.totalNodes || 1
  const confirmed = stats.value.confirmedNodes || 0
  return Math.round((confirmed / total) * 100)
})
const confirmationColor = computed(() => {
  if (confirmationRate.value >= 80) return '#67c23a'
  if (confirmationRate.value >= 50) return '#e6a23c'
  return '#f56c6c'
})

async function loadQuality() {
  if (!props.projectId) return
  try {
    const res: any = await graphApi.getGraphQualityReport(props.projectId, props.versionId)
    stats.value = res || {}
  } catch { /* ignore */ }
}

watch(() => props.versionId, () => { if (props.versionId) loadQuality() })
onMounted(() => { loadQuality() })
</script>

<style scoped>
.quality-panel { padding: 4px 0; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
.stat-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 6px 0; border-bottom: 1px solid var(--el-border-color-lighter);
}
.stat-row:last-child { border-bottom: none; }
.stat-row strong { font-size: 16px; margin: 0 8px; }
.green { color: #67c23a; }
.orange { color: #e6a23c; }
.red { color: #f56c6c; }
.blue { color: #409eff; }
</style>
