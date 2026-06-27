<template>
  <div class="risk-detail">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <el-button @click="goBack">
            <el-icon><arrow-left /></el-icon>
            返回
          </el-button>
          <span>风险详情 - {{ risk?.affectedNodeName }}</span>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="风险类型">
          <el-tag :type="getRiskTypeTag(risk?.riskType)">
            {{ getRiskTypeName(risk?.riskType) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="风险等级">
          <el-progress
            :percentage="risk?.riskLevel * 100"
            :color="getRiskLevelColor(risk?.riskLevel)"
            :stroke-width="8"
          />
        </el-descriptions-item>
        <el-descriptions-item label="节点ID">
          {{ risk?.affectedNodeId }}
        </el-descriptions-item>
        <el-descriptions-item label="描述">
          {{ risk?.description }}
        </el-descriptions-item>
      </el-descriptions>

      <el-divider />

      <div class="node-info" v-if="nodeInfo">
        <h4>节点信息</h4>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="节点类型">{{ nodeInfo.nodeType }}</el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress
              :percentage="nodeInfo.confidence * 100"
              :color="nodeInfo.confidence >= 0.8 ? '#67c23a' : nodeInfo.confidence >= 0.5 ? '#e6a23c' : '#f56c6c'"
              :stroke-width="8"
            />
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ nodeInfo.status }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ nodeInfo.description }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="actions">
        <el-button type="primary" @click="goToNodeGraph">
          在图谱中查看
        </el-button>
        <el-button @click="markConfirmed">
          标记已确认
        </el-button>
        <el-button @click="markIgnored">
          标记已忽略
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useRouter, useRoute } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { graphApi } from '@/api'
import type { RiskItem, GraphNode } from '@/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()

const loading = ref(false)
const risk = ref<RiskItem | null>(null)
const nodeInfo = ref<GraphNode | null>(null)

function goBack() {
  const projectId = projectStore.currentProjectId
  router.push(`/projects/${projectId}/migration/risks`)
}

async function loadRiskDetail() {
  const projectId = projectStore.currentProjectId
  const riskId = route.params.riskId as string
  // TODO: 从报告中获取风险详情，这里简化处理
  // 实际应用中，风险在报告中已经获取，这里获取节点详细信息
  loading.value = true
  try {
    // 这里可以从路由状态获取风险数据
    // nodeInfo.value = await graphApi.getNode(projectId, risk.value?.affectedNodeId || '')
  } catch (error) {
    console.error(error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function getRiskTypeTag(riskType: string) {
  switch (riskType) {
    case 'LOW_CONFIDENCE': return 'warning'
    case 'DISCONNECTED': return 'danger'
    default: return 'info'
  }
}

function getRiskTypeName(riskType: string) {
  switch (riskType) {
    case 'LOW_CONFIDENCE': return '低置信度'
    case 'DISCONNECTED': return '孤立节点'
    default: return riskType
  }
}

function getRiskLevelColor(riskLevel: number) {
  if (riskLevel >= 0.7) return '#f56c6c'
  if (riskLevel >= 0.4) return '#e6a23c'
  return '#67c23a'
}

function goToNodeGraph() {
  const projectId = projectStore.currentProjectId
  router.push(`/projects/${projectId}/graph/code`)
}

function markConfirmed() {
  // TODO: 调用接口标记确认
  ElMessage.success('已标记为确认')
  goBack()
}

function markIgnored() {
  // TODO: 调用接口标记忽略
  ElMessage.success('已标记为忽略')
  goBack()
}

onMounted(() => {
  loadRiskDetail()
})
</script>

<style scoped>
.risk-detail {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.node-info {
  margin-top: 16px;
}

h4 {
  margin-bottom: 16px;
  font-size: 16px;
  font-weight: 600;
}

.actions {
  margin-top: 24px;
  text-align: center;
}
</style>
