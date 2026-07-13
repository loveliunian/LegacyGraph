<template>
  <div
    class="custom-node"
    :class="[`node-${nodeTypeClass}`, { 'node-selected': isSelected, 'node-pending': isPending, 'node-duplicate': isDuplicate }]"
    :style="{ borderColor: nodeColor }"
  >
    <div
      class="node-header"
      :style="{ backgroundColor: nodeColor + '20' }">
      <div
        class="node-icon"
        :style="{ color: nodeColor }">
        <el-icon :size="18">
          <component :is="nodeIcon" />
        </el-icon>
      </div>
      <div
        class="node-title"
        :title="nodeLabel">
        {{ nodeLabel }}
      </div>
    </div>
    <div class="node-body">
      <div class="node-type">
        <el-tag
          size="small"
          :type="getTagType(nodeType)"
          effect="plain">
          {{ getTypeLabel(nodeType) }}
        </el-tag>
      </div>
      <div class="node-confidence">
        <span class="confidence-label">置信度</span>
        <el-progress
          :percentage="confidence * 100"
          :color="nodeColor"
          :stroke-width="6"
          :show-text="false"
        />
        <span class="confidence-value">{{ (confidence * 100).toFixed(0) }}%</span>
      </div>
      <div
        v-if="evidenceCount"
        class="node-evidence">
        <el-icon
          size="12"
          color="#909399">
          <Document />
        </el-icon>
        <span>{{ evidenceCount }} 条证据</span>
      </div>
    </div>
    <Handle
      type="source"
      :position="Position.Bottom"
      :style="{ background: nodeColor }" />
    <Handle
      type="target"
      :position="Position.Top"
      :style="{ background: nodeColor }" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { Node } from '@vue-flow/core'
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

const props = defineProps<{
  /** 兼容旧调用方直接传入完整 VueFlow 节点对象 */
  node?: Node<any>
  /** Vue Flow 自定义节点标准 props */
  id?: string
  data?: Record<string, any>
  selected?: boolean
}>()

const nodeData = computed<Record<string, any>>(() => props.node?.data ?? props.data ?? {})

const nodeLabel = computed(() => nodeData.value.label || '未命名')

const nodeType = computed(() => nodeData.value.type || 'default')

const normalizedType = computed(() => normalizeType(nodeType.value))

const nodeTypeClass = computed(() => normalizedType.value.replace(/[^a-z0-9_-]/gi, '-').toLowerCase())

const nodeStatus = computed(() => String(nodeData.value.status || ''))

const isSelected = computed(() => props.selected === true)

const isPending = computed(() => ['pending', 'pending_confirm'].includes(nodeStatus.value.toLowerCase()))

/** P6: 疑似重复节点（与 duplicateNodeIds 集合匹配），显示虚线框高亮 */
const isDuplicate = computed(() => nodeData.value.duplicate === true)

const confidence = computed(() => Number(nodeData.value.confidence ?? 0))

const evidenceCount = computed(() => Number(nodeData.value.evidenceCount ?? 0))

const nodeColor = computed(() => {
  if (confidence.value >= 0.9) return '#67c23a'
  if (confidence.value >= 0.7) return '#409eff'
  if (confidence.value >= 0.5) return '#e6a23c'
  return '#f56c6c'
})

const nodeIcon = computed(() => {
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
  return iconMap[normalizedType.value] || QuestionFilled
})

function getTagType(type?: string): string {
  const normalized = normalizeType(type)
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
  return typeMap[normalized] || 'info'
}

function getTypeLabel(type?: string): string {
  const normalized = normalizeType(type)
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
  return labelMap[normalized] || type || '未知'
}

function normalizeType(type?: string): string {
  const value = String(type || '').trim()
  const aliasMap: Record<string, string> = {
    BusinessDomain: 'business_domain',
    BusinessProcess: 'business_process',
    FeatureModule: 'feature_module',
    Feature: 'feature',
    ApiEndpoint: 'api',
    Controller: 'controller',
    Service: 'service',
    Mapper: 'mapper',
    SqlStatement: 'sql',
    Table: 'table',
    Column: 'column'
  }
  return aliasMap[value] || value
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

/* P6: 疑似重复节点高亮（虚线橙边框 + 同色背景提示） */
.node-duplicate {
  border-style: dashed !important;
  border-color: #e6a23c !important;
  border-width: 2px !important;
  background: rgba(230, 162, 60, 0.06);
}

.node-duplicate:hover {
  box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.25);
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
