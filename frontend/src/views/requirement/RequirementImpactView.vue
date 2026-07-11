<template>
  <div class="requirement-impact-view">
    <!-- 需求选择器 -->
    <el-card class="selector-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">
            <el-icon><Connection /></el-icon>
            需求影响分析
          </span>
        </div>
      </template>

      <div class="selector-row">
        <el-input
          v-model="requirementIdInput"
          placeholder="请输入需求 ID（如从需求分析页面保存后获得）"
          clearable
          @keyup.enter="handleAnalyze">
          <template #prepend>需求 ID</template>
        </el-input>
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!requirementIdInput.trim()"
          @click="handleAnalyze">
          <el-icon><DataAnalysis /></el-icon>
          分析影响
        </el-button>
      </div>
    </el-card>

    <!-- 加载中 -->
    <el-skeleton
      v-if="loading"
      :rows="6"
      animated />

    <!-- 结果区 -->
    <template v-else-if="vizData">
      <el-row :gutter="16">
        <!-- 影响子图可视化 -->
        <el-col :span="16">
          <el-card class="graph-card">
            <template #header>
              <span class="card-title">
                <el-icon><Share /></el-icon>
                影响子图
                <el-tag
                  size="small"
                  round>
                  {{ vizData.nodes.length }} 节点 / {{ vizData.edges.length }} 边
                </el-tag>
              </span>
            </template>
            <ImpactGraphVisualization
              :nodes="vizData.nodes"
              :edges="vizData.edges" />
          </el-card>
        </el-col>

        <!-- 影响摘要面板 -->
        <el-col :span="8">
          <ImpactSummaryPanel :summary="vizData.summary" />
        </el-col>
      </el-row>
    </template>

    <!-- 空状态 -->
    <el-empty
      v-else
      description="输入需求 ID 后点击「分析影响」查看影响子图可视化"
      class="placeholder-empty" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Connection,
  DataAnalysis,
  Share,
} from '@element-plus/icons-vue'
import { impactVizApi } from '@/api'
import type { ImpactVisualizationData } from '@/api'
import ImpactGraphVisualization from '@/components/ImpactGraphVisualization.vue'
import ImpactSummaryPanel from '@/components/ImpactSummaryPanel.vue'

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)

const requirementIdInput = ref('')
const loading = ref(false)
const vizData = ref<ImpactVisualizationData | null>(null)

/** 触发影响可视化分析 */
async function handleAnalyze() {
  const reqId = requirementIdInput.value.trim()
  if (!reqId) return
  loading.value = true
  try {
    const data = await impactVizApi.getVisualization(projectId.value, reqId)
    vizData.value = data
    if (!data.nodes || data.nodes.length === 0) {
      ElMessage.info('该需求暂未发现受影响节点')
    } else {
      ElMessage.success(`影响分析完成：${data.nodes.length} 个受影响节点`)
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.requirement-impact-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.selector-row {
  display: flex;
  gap: 12px;
  align-items: center;
}

.selector-row .el-input {
  flex: 1;
}

.graph-card {
  height: 100%;
}

.placeholder-empty {
  padding: 60px 0;
}
</style>
