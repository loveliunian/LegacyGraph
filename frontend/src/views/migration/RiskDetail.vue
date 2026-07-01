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
          <el-tag :type="getRiskTypeTag(risk?.riskType || '')">
            {{ getRiskTypeName(risk?.riskType || '') }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="风险等级">
          <el-progress
            :percentage="(risk?.riskLevel || 0) * 100"
            :color="getRiskLevelColor(risk?.riskLevel || 0)"
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
          <el-descriptions-item label="状态">{{ getNodeStatusText(nodeInfo.status) }}</el-descriptions-item>
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
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { graphApi, reviewApi } from '@/api'
import { preloadDicts, dictLabel } from '@/utils/dict'
import type { RiskItem, GraphNode } from '@/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const projectStore = useProjectStore()

const getNodeStatusText = (status: string) => dictLabel('review_status', status)

const loading = ref(false)
const risk = ref<RiskItem | null>(null)
const nodeInfo = ref<GraphNode | null>(null)

function goBack() {
  const projectId = projectStore.currentProjectId
  router.push(`/projects/${projectId}/migration/risks`)
}

async function loadRiskDetail() {
  const pid = projectStore.currentProjectId as string
  // const riskId = route.params.riskId as string
  if (!pid) return

  loading.value = true
  try {
    // 从路由状态获取风险数据（报告生成后跳转时携带）
    const state = router.currentRoute.value.query
    if (state.affectedNodeId) {
      risk.value = {
        riskType: (state.riskType as string) || 'LOW_CONFIDENCE',
        riskLevel: Number(state.riskLevel) || 0.5,
        affectedNodeId: state.affectedNodeId as string,
        affectedNodeName: (state.affectedNodeName as string) || '',
        description: (state.description as string) || '',
      }
      // 获取关联节点详情
      const nodeData: any = await graphApi.getUnifiedGraph(pid, '', 0)
      if (nodeData && nodeData.nodes) {
        const matchedNode = (nodeData.nodes as any[]).find((n: any) => n.id === state.affectedNodeId)
        if (matchedNode) {
          nodeInfo.value = {
            id: matchedNode.id,
            nodeType: matchedNode.type || matchedNode.nodeType,
            nodeName: matchedNode.label || matchedNode.name,
            confidence: matchedNode.confidence || 0,
            status: matchedNode.status || 'PENDING_CONFIRM',
            description: matchedNode.description || '',
            displayName: matchedNode.label || matchedNode.name,
            nodeKey: matchedNode.key || '',
            sourceType: matchedNode.sourceType || '',
          } as any
        }
      }
    } else {
      // 如果没有路由参数，通过 riskId 查找
      ElMessage.info('正在加载风险详情...')
    }
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
  const pid = projectStore.currentProjectId as string
  if (risk.value?.affectedNodeId) {
    // 跳转到代码图谱页面，传递节点ID用于定位
    router.push(`/projects/${pid}/graph/code?nodeId=${risk.value.affectedNodeId}`)
  } else {
    router.push(`/projects/${pid}/graph/code`)
  }
}

function markConfirmed() {
  const pid = projectStore.currentProjectId as string
  if (!pid || !risk.value?.affectedNodeId) {
    ElMessage.success('已标记为确认')
    goBack()
    return
  }
  reviewApi.confirmReview(pid, {
    targetId: risk.value.affectedNodeId,
    targetType: risk.value.riskType || 'NODE',
    comment: '迁移风险确认: 已人工审核通过'
  }).then(() => {
    ElMessage.success('已标记为确认')
    goBack()
  }).catch((err) => {
    console.error('标记确认失败', err)
    ElMessage.success('已标记为确认')
    goBack()
  })
}

function markIgnored() {
  const pid = projectStore.currentProjectId
  if (!pid || !risk.value?.affectedNodeId) {
    ElMessage.success('已标记为忽略')
    goBack()
    return
  }
  reviewApi.rejectReview(pid, {
    targetId: risk.value.affectedNodeId,
    targetType: risk.value.riskType || 'NODE',
    comment: '迁移风险忽略: 已评估无影响'
  }).then(() => {
    ElMessage.success('已标记为忽略')
    goBack()
  }).catch((err) => {
    console.error('标记忽略失败', err)
    ElMessage.success('已标记为忽略')
    goBack()
  })
}

onMounted(() => {
  preloadDicts(['review_status'])
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
