<template>
  <el-drawer
    v-model="visible"
    title="节点详情"
    direction="rtl"
    size="500px"
    :before-close="handleClose"
  >
    <div class="node-detail-container" v-loading="loading">
      <div class="section" v-if="node">
        <h4 class="section-title">基本信息</h4>
        <el-descriptions border :column="1" size="small">
          <el-descriptions-item label="节点ID">{{ node.id }}</el-descriptions-item>
          <el-descriptions-item label="节点名称">{{ node.data?.label || node.id }}</el-descriptions-item>
          <el-descriptions-item label="节点类型">
            <el-tag :type="getNodeTypeColor(node.type)">{{ node.type }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress
              :percentage="(node.data?.confidence || 0) * 100"
              :color="getConfidenceColor(node.data?.confidence)"
              :stroke-width="12"
            />
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="node.data?.status === 'VERIFIED' ? 'success' : 'warning'">
              {{ node.data?.status || '未验证' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="section" v-if="node && node.data?.properties">
        <h4 class="section-title">属性信息</h4>
        <div class="properties-container">
          <div class="property-item" v-for="(value, key) in node.data.properties" :key="key">
            <span class="property-key">{{ key }}</span>
            <span class="property-value">{{ formatValue(value) }}</span>
          </div>
        </div>
      </div>

      <div class="section">
        <h4 class="section-title">
          关联边
          <el-tag size="small" type="info">{{ (edges || []).length }} 条</el-tag>
        </h4>
        <el-table :data="edges || []" size="small" border stripe>
          <el-table-column prop="source" label="起点" width="140" show-overflow-tooltip />
          <el-table-column prop="label" label="关系" width="100">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="target" label="终点" width="140" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="section" v-if="evidenceList && evidenceList.length > 0">
        <h4 class="section-title">
          证据来源
          <el-tag size="small" type="success">{{ evidenceList.length }} 条</el-tag>
        </h4>
        <el-timeline>
          <el-timeline-item
            v-for="(evidence, index) in evidenceList"
            :key="index"
            :type="evidence.type === 'CODE' ? 'primary' : evidence.type === 'DOC' ? 'success' : 'warning'"
            :timestamp="evidence.timestamp"
          >
            <el-card shadow="hover" size="small">
              <template #header>
                <div class="evidence-header">
                  <el-tag size="small">{{ evidence.type }}</el-tag>
                  <span class="evidence-source">{{ evidence.source }}</span>
                </div>
              </template>
              <div class="evidence-content">
                <el-tooltip content="查看完整内容" placement="top">
                  <el-button text type="primary" @click="showEvidenceDetail(evidence)">
                    查看详情
                  </el-button>
                </el-tooltip>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </div>

      <div class="section" v-if="relatedNodes && relatedNodes.length > 0">
        <h4 class="section-title">
          关联节点
          <el-tag size="small" type="info">{{ relatedNodes.length }} 个</el-tag>
        </h4>
        <div class="related-nodes">
          <el-tag
            v-for="n in relatedNodes"
            :key="n.id"
            :type="getNodeTypeColor(n.type)"
            class="related-node-tag"
            closable
            @close="highlightNode(n.id)"
          >
            {{ n.data?.label || n.id }}
          </el-tag>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button @click="handleClose">关闭</el-button>
        <el-button type="primary" @click="handleReview">人工审核</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'

interface GraphNode {
  id: string
  type: string
  data?: {
    label?: string
    confidence?: number
    status?: string
    properties?: Record<string, any>
  }
}

interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
}

interface Evidence {
  type: string
  source: string
  content: string
  timestamp: string
}

const props = defineProps<{
  visible: boolean
  node: GraphNode | null
}>()

const emit = defineEmits<{
  update: [visible: boolean]
  review: [node: GraphNode]
  highlight: [nodeId: string]
}>()

const loading = ref(false)
const edges = ref<GraphEdge[]>([])
const evidenceList = ref<Evidence[]>([])
const relatedNodes = ref<GraphNode[]>([])

const visible = computed({
  get: () => props.visible,
  set: (val) => emit('update', val)
})

function getNodeTypeColor(type: string): string {
  const colorMap: Record<string, string> = {
    'Controller': 'primary',
    'Service': 'success',
    'Mapper': 'warning',
    'SQL': 'danger',
    'Column': 'info',
    'Table': 'warning',
    'Feature': 'success',
    'Page': 'info',
    'API': 'danger'
  }
  return colorMap[type] || 'info'
}

function getConfidenceColor(confidence: number): string {
  if (!confidence) return '#909399'
  if (confidence >= 0.8) return '#67c23a'
  if (confidence >= 0.6) return '#e6a23c'
  return '#f56c6c'
}

function formatValue(value: any): string {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

function showEvidenceDetail(evidence: Evidence) {
  ElMessage.info('查看证据详情功能开发中...')
}

function highlightNode(nodeId: string) {
  emit('highlight', nodeId)
}

function handleClose() {
  emit('update', false)
}

function handleReview() {
  if (props.node) {
    emit('review', props.node)
  }
}

// 模拟加载关联数据
const loadRelatedData = () => {
  loading.value = true
  setTimeout(() => {
    // 模拟数据
    edges.value = [
      { id: 'e1', source: 'TicketService.dispatch', target: 'TicketMapper.updateHandler', label: 'CALLS' },
      { id: 'e2', source: 'TicketController.dispatch', target: 'TicketService.dispatch', label: 'CALLS' }
    ]
    evidenceList.value = [
      { type: 'CODE', source: 'TicketController.java', content: '调用 TicketService.dispatch 方法', timestamp: '2024-01-15 10:30:00' },
      { type: 'CODE', source: 'TicketService.java', content: '调用 TicketMapper.updateHandler', timestamp: '2024-01-15 10:30:01' },
      { type: 'SQL', source: 'TicketMapper.xml', content: 'UPDATE t_ticket SET status = ? WHERE id = ?', timestamp: '2024-01-15 10:30:02' }
    ]
    relatedNodes.value = [
      { id: 'TicketService.dispatch', type: 'Service', data: { label: 'TicketService.dispatch' } },
      { id: 'TicketMapper.updateHandler', type: 'Mapper', data: { label: 'TicketMapper.updateHandler' } }
    ]
    loading.value = false
  }, 500)
}

// 监听节点变化，加载关联数据
watch(() => props.node, (newNode) => {
  if (newNode && visible.value) {
    loadRelatedData()
  }
})
</script>

<style scoped>
.node-detail-container {
  padding: 0 16px 16px;
}

.section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.properties-container {
  max-height: 300px;
  overflow-y: auto;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.property-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.property-item:last-child {
  border-bottom: none;
}

.property-key {
  color: #606266;
  font-size: 13px;
  font-weight: 500;
}

.property-value {
  color: #303133;
  font-size: 13px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.evidence-source {
  font-size: 12px;
  color: #909399;
}

.related-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.related-node-tag {
  cursor: pointer;
  transition: all 0.3s;
}

.related-node-tag:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid #ebeef5;
}
</style>
