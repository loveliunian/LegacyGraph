<template>
  <div class="execution-log">
    <div class="header">
      <span>执行日志</span>
      <el-button
        link
        size="small"
        @click="clear">
        清空
      </el-button>
    </div>
    <div
      ref="logContainer"
      class="log-container"
      v-html="formattedLog" />
    <div
      v-if="!log"
      class="empty">
      暂无日志
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps<{
  log: string[]
}>()

const formattedLog = computed(() => {
  return props.log
    .map(line => {
      // 颜色高亮
      if (line.includes('PASSED') || line.includes('通过') || line.includes('success')) {
        return `<div class="line success">${escapeHtml(line)}</div>`
      }
      if (line.includes('FAILED') || line.includes('失败') || line.includes('error')) {
        return `<div class="line error">${escapeHtml(line)}</div>`
      }
      return `<div class="line">${escapeHtml(line)}</div>`
    })
    .join('')
})

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function clear() {
  // 清空由父组件处理
  emit('clear')
}

const emit = defineEmits<{
  clear: []
}>()

const logContainer = ref<HTMLElement | null>(null)

watch(
  () => props.log,
  () => {
    nextTick(() => {
      if (logContainer.value) {
        logContainer.value.scrollTop = logContainer.value.scrollHeight
      }
    })
  },
  { deep: true }
)
</script>

<style scoped>
.execution-log {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #dcdfe6;
  font-weight: 600;
}

.log-container {
  height: 300px;
  overflow-y: auto;
  padding: 8px;
  background: #1e1e1e;
  color: #d4d4d4;
}

.line {
  padding: 2px 4px;
  font-family: monospace;
  font-size: 12px;
  line-height: 1.5;
}

.line.success {
  color: #67c23a;
}

.line.error {
  color: #f56c6c;
}

.empty {
  text-align: center;
  padding: 40px;
  color: #909399;
}
</style>
