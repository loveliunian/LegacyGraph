<template>
  <el-tag :type="tagType" :size="size">
    {{ displayText }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  value: number
  showText?: boolean
  size?: 'small' | 'default' | 'large'
}

const props = withDefaults(defineProps<Props>(), {
  showText: true,
  size: 'small'
})

const tagType = computed(() => {
  if (props.value >= 0.8) return 'success'
  if (props.value >= 0.6) return 'warning'
  return 'danger'
})

const displayText = computed(() => {
  if (!props.showText) return ''
  if (props.value >= 0.8) return '高置信度'
  if (props.value >= 0.6) return '中置信度'
  return '低置信度'
})
</script>
