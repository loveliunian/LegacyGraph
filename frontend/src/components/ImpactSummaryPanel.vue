<template>
  <div class="impact-summary-panel">
    <el-card class="summary-card">
      <template #header>
        <span class="card-title">
          <el-icon><DataAnalysis /></el-icon>
          影响摘要
        </span>
      </template>

      <el-empty
        v-if="!summary"
        description="暂无摘要数据" />

      <template v-else>
        <!-- 核心指标 -->
        <div class="metric-grid">
          <div class="metric-item">
            <div class="metric-value">{{ summary.totalNodes }}</div>
            <div class="metric-label">受影响节点</div>
          </div>
          <div class="metric-item">
            <div class="metric-value">{{ formatScore(summary.maxRiskScore) }}</div>
            <div class="metric-label">最大风险分数</div>
          </div>
          <div class="metric-item">
            <div class="metric-value">{{ summary.highRiskNodes.length }}</div>
            <div class="metric-label">高风险节点</div>
          </div>
        </div>

        <!-- 各级别数量 -->
        <div class="level-section">
          <div class="section-subtitle">影响层级分布</div>
          <div class="level-list">
            <div
              v-for="level in levelItems"
              :key="level.key"
              class="level-row">
              <span
                class="level-dot"
                :style="{ background: level.color }" />
              <span class="level-name">{{ level.label }}</span>
              <el-progress
                :percentage="level.percent"
                :color="level.color"
                :stroke-width="10"
                :show-text="false"
                class="level-bar" />
              <span class="level-count">{{ level.count }}</span>
            </div>
          </div>
        </div>

        <!-- 高风险节点列表 -->
        <div
          v-if="summary.highRiskNodes.length > 0"
          class="high-risk-section">
          <div class="section-subtitle">
            高风险节点
            <el-tag size="small" type="danger" round>
              {{ summary.highRiskNodes.length }}
            </el-tag>
          </div>
          <div class="high-risk-list">
            <el-tag
              v-for="(name, i) in summary.highRiskNodes"
              :key="i"
              type="danger"
              effect="light"
              class="high-risk-tag">
              {{ name }}
            </el-tag>
          </div>
        </div>
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon>
          <template #title>暂无高风险节点</template>
        </el-alert>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DataAnalysis } from '@element-plus/icons-vue'
import type { VizSummary } from '@/api'

const props = defineProps<{
  summary: VizSummary | null
}>()

/** 影响层级配置 */
const levelConfig = [
  { key: 'L0', label: 'L0 直接', color: '#e63757' },
  { key: 'L1', label: 'L1 代码', color: '#ff8c00' },
  { key: 'L2', label: 'L2 交互', color: '#ffd700' },
  { key: 'L3', label: 'L3 质量', color: '#4a90d9' },
  { key: 'L4', label: 'L4 架构', color: '#b0b0b0' },
]

/** 计算各级别数量与占比 */
const levelItems = computed(() => {
  if (!props.summary) return []
  const total = props.summary.totalNodes || 1
  return levelConfig.map((cfg) => {
    const count = props.summary!.byLevel?.[cfg.key] ?? 0
    return {
      ...cfg,
      count,
      percent: Math.round((count / total) * 100),
    }
  })
})

/** 格式化风险分数 */
function formatScore(score: number): string {
  return (score ?? 0).toFixed(3)
}
</script>

<style scoped>
.impact-summary-panel {
  width: 100%;
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.metric-item {
  text-align: center;
  padding: 12px 8px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.metric-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.4;
}

.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.level-section,
.high-risk-section {
  margin-bottom: 16px;
}

.section-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 10px;
}

.level-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.level-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.level-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.level-name {
  width: 70px;
  flex-shrink: 0;
  color: var(--el-text-color-regular);
}

.level-bar {
  flex: 1;
}

.level-count {
  width: 28px;
  text-align: right;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.high-risk-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.high-risk-tag {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
