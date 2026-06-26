<template>
  <el-drawer
    v-model="visible"
    title="节点详情"
    size="50%"
    :before-close="handleClose"
  >
    <div v-if="node" class="node-detail">
      <el-descriptions title="基本信息" border :column="2" class="mb-4">
        <el-descriptions-item label="节点ID">{{ node.id }}</el-descriptions-item>
        <el-descriptions-item label="节点类型">
          <el-tag size="small">{{ node.nodeType }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="节点名称">{{ node.nodeName }}</el-descriptions-item>
        <el-descriptions-item label="显示名称">{{ node.displayName }}</el-descriptions-item>
        <el-descriptions-item label="置信度">
          <ConfidenceBadge :value="node.confidence" />
        </el-descriptions-item>
        <el-descriptions-item label="审核状态">
          <el-tag :type="getReviewStatusType(node.reviewStatus)" size="small">
            {{ node.reviewStatus }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源类型">
          <el-tag size="small">{{ node.sourceType }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="证据数">{{ node.evidenceCount }}</el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">
          {{ formatTime(node.createdAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-descriptions v-if="node.properties && Object.keys(node.properties).length > 0" title="属性信息" border :column="2" class="mb-4">
        <el-descriptions-item v-for="(value, key) in node.properties" :key="key" :label="key">
          {{ formatProperty(value) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="section">
        <h4>关联关系 ({{ edges.length }})</h4>
        <el-table :data="edges" border stripe size="small">
          <el-table-column prop="edgeType" label="关系类型" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.edgeType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="源节点" width="200" show-overflow-tooltip />
          <el-table-column prop="target" label="目标节点" width="200" show-overflow-tooltip />
          <el-table-column prop="confidence" label="置信度" width="100">
            <template #default="{ row }">
              <el-tag :type="row.confidence >= 0.8 ? 'success' : row.confidence >= 0.6 ? 'warning' : 'danger'" size="small">
                {{ (row.confidence * 100).toFixed(0) }}%
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="edges.length === 0" description="暂无关联关系" />
      </div>

      <div class="section">
        <h4>证据来源 ({{ evidence.length }})</h4>
        <EvidencePanel v-if="evidence.length > 0" :evidence="evidence" />
        <el-empty v-else description="暂无证据来源" />
      </div>

      <div class="section">
        <h4>测试验证</h4>
        <el-tag v-if="node.testStatus === 'PASSED'" type="success">测试通过</el-tag>
        <el-tag v-else-if="node.testStatus === 'FAILED'" type="danger">测试失败</el-tag>
        <el-tag v-else-if="node.testStatus === 'PARTIAL'" type="warning">部分通过</el-tag>
        <el-tag v-else type="info">未测试</el-tag>
      </div>

      <div class="section">
        <h4>人工审核</h4>
        <el-space wrap>
          <el-button type="success" @click="approve">确认正确</el-button>
          <el-button type="danger" @click="reject">标记错误</el-button>
          <el-button @click="needMoreEvidence">需要更多证据</el-button>
        </el-space>
      </div>
    </div>

    <template #footer>
      <el-space>
        <el-button @click="handleClose">关闭</el-button>
        <el-button type="primary" @click="navigateToNode">查看完整信息</el-button>
      </el-space>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import dayjs from 'dayjs'
import type { GraphNode, GraphEdge, Evidence, ReviewStatus } from '@/types'
import ConfidenceBadge from './ConfidenceBadge.vue'
import EvidencePanel from './EvidencePanel.vue'
import { ElMessage } from 'element-plus'

interface Props {
  node: GraphNode | null
  edges?: GraphEdge[]
  evidence?: Evidence[]
  visible: boolean
}

const props = withDefaults(defineProps<Props>(), {
  edges: () => [],
  evidence: () => []
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  close: []
  approve: [nodeId: string]
  reject: [nodeId: string]
  needMoreEvidence: [nodeId: string]
  navigate: [nodeId: string]
}>()

const visible = computed({
  get: () => props.visible,
  set: (value) => emit('update:visible', value)
})

const handleClose = () => {
  emit('close')
}

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const formatProperty = (value: any): string => {
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2)
  }
  return String(value)
}

const getReviewStatusType = (status: ReviewStatus): string => {
  const map: Record<ReviewStatus, string> = {
    APPROVED: 'success',
    REJECTED: 'danger',
    PENDING: 'warning',
    CONFIRMED: 'success',
    IGNORED: 'info'
  }
  return map[status] || 'info'
}

const approve = () => {
  if (props.node) {
    emit('approve', props.node.id)
    ElMessage.success('已确认')
  }
}

const reject = () => {
  if (props.node) {
    emit('reject', props.node.id)
    ElMessage.success('已标记错误')
  }
}

const needMoreEvidence = () => {
  if (props.node) {
    emit('needMoreEvidence', props.node.id)
    ElMessage.success('已标记需要更多证据')
  }
}

const navigateToNode = () => {
  if (props.node) {
    emit('navigate', props.node.id)
  }
}
</script>

<style scoped>
.node-detail {
  padding: 0 16px;
}

.section {
  margin-top: 24px;
}

.section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.mb-4 {
  margin-bottom: 16px;
}
</style>
