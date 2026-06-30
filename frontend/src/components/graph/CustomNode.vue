<template>
  <div
    v-if="node"
    class="custom-node"
    :class="[`node-${node.data?.type || 'default'}`, { 'node-selected': selected, 'node-pending': node.data?.status === 'pending' }]"
    :style="{ borderColor: nodeColor }"
  >
    <div class="node-header" :style="{ backgroundColor: nodeColor + '20' }">
      <div class="node-icon" :style="{ color: nodeColor }">
        <el-icon :size="18">
          <component :is="nodeIcon" />
        </el-icon>
      </div>
      <div class="node-title" :title="node.data?.label">{{ node.data?.label || '未命名' }}</div>
    </div>
    <div class="node-body">
      <div class="node-type">
        <el-tag size="small" :type="getTagType(node.data?.type)" effect="plain">
          {{ getTypeLabel(node.data?.type) }}
        </el-tag>
      </div>
      <div class="node-confidence">
        <span class="confidence-label">置信度</span>
        <el-progress
          :percentage="(node.data?.confidence || 0) * 100"
          :color="nodeColor"
          :stroke-width="6"
          :show-text="false"
        />
        <span class="confidence-value">{{ ((node.data?.confidence || 0) * 100).toFixed(0) }}%</span>
      </div>
      <div class="node-evidence" v-if="node.data?.evidenceCount">
        <el-icon size="12" color="#909399"><Document /></el-icon>
        <span>{{ node.data.evidenceCount }} 条证据</span>
      </div>
    </div>
    <Handle type="source" :position="Position.Bottom" :style="{ background: nodeColor }" />
    <Handle type="target" :position="Position.Top" :style="{ background: nodeColor }" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Node, Position } from '@vue-flow/core'
import { Handle } from '@vue-flow/core'
import {
  Document,
  Files,
  Operation,
  Coin,
  Platform,
  Menu,
  Link,
  QuestionFilled
} from '@element-plus/icons-vue'

/**
 * 组件属性定义
 */
const props = defineProps<{
  /** VueFlow节点对象 */
  node: Node<any>
  /** 是否选中 */
  selected?: boolean
}>()

const nodeColor = computed(() => {
  const confidence = props.node.data?.confidence || 0.8
  if (confidence >= 0.9) return '#67c23a'
  if (confidence >= 0.7) return '#409eff'
  if (confidence >= 0.5) return '#e6a23c'
  return '#f56c6c'
})

const nodeIcon = computed(() => {
  const type = props.node.data?.type
  const iconMap: Record<string, any> = {
    business_domain: Menu,
    business_process: Operation,
    feature_module: Files,
    feature: Link,
    api: Operation,
    controller: Platform,
    service: Platform,
    mapper: Platform,
    sql: Coin,
    table: Coin,
    column: Document
  }
  return iconMap[type] || QuestionFilled
})

function getTagType(type?: string): string {
  const typeMap: Record<string, string> = {
    business_domain: 'success',
    business_process: 'warning',
    feature_module: 'primary',
    feature: 'info',
    api: 'primary',
    controller: 'success',
    service: 'warning',
    mapper: 'info',
    sql: 'danger',
    table: 'danger',
    column: 'info'
  }
  return typeMap[type || ''] || 'info'
}

function getTypeLabel(type?: string): string {
  const labelMap: Record<string, string> = {
    business_domain: '业务域',
    business_process: '业务流程',
    feature_module: '功能模块',
    feature: '功能点',
    api: 'API接口',
    controller: 'Controller',
    service: 'Service',
    mapper: 'Mapper',
    sql: 'SQL语句',
    table: '数据库表',
    column: '字段'
  }
  return labelMap[type || ''] || type || '未知'
}
</script>

<style scoped>
.custom-node {
  width: 180px;
  min-height: 100px;
  background: white;
  border-radius: 8px;
  border: 2px solid #e5e7eb;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  transition: all 0.2s ease;
}

.custom-node:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  transform: translateY(-2px);
}

.node-selected {
  border-color: #409eff !important;
  box-shadow: 0 0 0 4px rgba(64, 158, 255, 0.2) !important;
}

.node-pending {
  opacity: 0.85;
}

.node-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  gap: 8px;
}

.node-icon {
  flex-shrink: 0;
}

.node-title {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-body {
  padding: 10px 12px;
}

.node-type {
  margin-bottom: 8px;
}

.node-confidence {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.confidence-label {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
}

.confidence-value {
  font-size: 11px;
  color: #606266;
  font-weight: 500;
  white-space: nowrap;
}

.node-evidence {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #909399;
}

:deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid white;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.2);
}

:deep(.vue-flow__handle-top) {
  top: -6px;
}

:deep(.vue-flow__handle-bottom) {
  bottom: -6px;
}
</style>
