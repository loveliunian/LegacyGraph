<template>
  <el-tag :type="getType(status)" size="small">
    {{ getText(status) }}
  </el-tag>
</template>

<script setup lang="ts">
interface Props {
  status: string
  statusMap?: Record<string, { text: string; type: 'primary' | 'success' | 'warning' | 'danger' | 'info' }>
}

const defaultMap: Record<string, { text: string; type: any }> = {
  // 通用状态
  PENDING: { text: '待处理', type: 'warning' },
  PROCESSING: { text: '处理中', type: 'primary' },
  COMPLETED: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
  // 审核状态
  PENDING_CONFIRM: { text: '待确认', type: 'warning' },
  CONFIRMED: { text: '已确认', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
  NEED_REVIEW: { text: '需审核', type: 'warning' },
  IGNORED: { text: '已忽略', type: 'info' },
  // 测试状态
  PASSED: { text: '通过', type: 'success' },
  PASSED: { text: '通过', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
  ERROR: { text: '错误', type: 'danger' },
  SKIPPED: { text: '跳过', type: 'info' },
  RUNNING: { text: '执行中', type: 'primary' },
  SCHEDULED: { text: '等待执行', type: 'warning' },
  // 节点状态
  ACTIVE: { text: '活跃', type: 'success' },
  INACTIVE: { text: '非活跃', type: 'info' },
  DELETED: { text: '已删除', type: 'danger' },
}

const props = withDefaults(defineProps<Props>(), {
  statusMap: undefined,
})

function getType(status: string) {
  const map = props.statusMap || defaultMap
  return map[status]?.type || 'info'
}

function getText(status: string) {
  const map = props.statusMap || defaultMap
  return map[status]?.text || status
}
</script>
