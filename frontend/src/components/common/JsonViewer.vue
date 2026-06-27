<template>
  <div class="json-viewer">
    <div v-if="collapsible" class="json-viewer__header">
      <el-button link size="small" @click="toggleExpand">
        <el-icon><component :is="isExpanded ? arrowDown : arrowRight" /></el-icon>
        {{ label || 'JSON' }} ({{ count }})
      </el-button>
      <span class="json-viewer__type">{{ typeofData }}</span>
    </div>
    <div v-show="isExpanded || !collapsible" class="json-viewer__content">
      <div v-if="Array.isArray(data)" class="json-viewer__array">
        <div
          v-for="(item, index) in data"
          :key="index"
          class="json-viewer__item"
        >
          <span class="json-viewer__key">{{ index }}</span>
          <JsonViewer
            :data="item"
            :collapsible="collapsible"
            :level="level + 1"
          />
        </div>
      </div>
      <div v-else-if="typeof data === 'object' && data !== null" class="json-viewer__object">
        <div
          v-for="(value, key) in data"
          :key="key"
          class="json-viewer__item"
        >
          <span class="json-viewer__key">{{ key }}</span>
          <JsonViewer
            :data="value"
            :collapsible="collapsible"
            :level="level + 1"
          />
        </div>
      </div>
      <div v-else class="json-viewer__value" :class="getValueTypeClass(data)">
        {{ formatValue(data) }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ArrowRight, ArrowDown } from '@element-plus/icons-vue'

interface Props {
  data: any
  collapsible?: boolean
  level?: number
  label?: string
}

const props = withDefaults(defineProps<Props>(), {
  collapsible: true,
  level: 0,
  label: undefined,
})

const isExpanded = ref(props.level < 2)

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}

const count = computed(() => {
  if (Array.isArray(props.data)) {
    return `${props.data.length} items`
  } else if (typeof props.data === 'object' && props.data !== null) {
    return `${Object.keys(props.data).length} keys`
  }
  return ''
})

const typeofData = computed(() => {
  if (Array.isArray(props.data)) return 'Array'
  if (props.data === null) return 'null'
  return typeof props.data
})

function getValueTypeClass(value: any) {
  if (value === null || value === undefined) return 'json-value--null'
  if (typeof value === 'boolean') return 'json-value--boolean'
  if (typeof value === 'number') return 'json-value--number'
  if (typeof value === 'string') return 'json-value--string'
  return ''
}

function formatValue(value: any): string {
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'
  return String(value)
}
</script>

<style scoped>
.json-viewer {
  display: block;
}

.json-viewer__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 4px;
  background: #f5f7fa;
  border-radius: 3px;
  margin-bottom: 4px;
}

.json-viewer__type {
  font-size: 11px;
  color: #909399;
  background: #e4e7ed;
  padding: 2px 6px;
  border-radius: 3px;
}

.json-viewer__content {
  padding-left: 16px;
}

.json-viewer__item {
  display: flex;
  gap: 8px;
  padding: 2px 0;
}

.json-viewer__key {
  color: #6f42c1;
  font-weight: 500;
  min-width: 80px;
}

.json-viewer__value {
  flex: 1;
}

.json-value--null {
  color: #909399;
}

.json-value--boolean {
  color: #0000ff;
  font-weight: 500;
}

.json-value--number {
  color: #0000ff;
}

.json-value--string {
  color: #008000;
  word-break: break-all;
}
</style>
