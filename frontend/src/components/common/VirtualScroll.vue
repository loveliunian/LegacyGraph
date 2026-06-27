<template>
  <div class="virtual-scroll-container" ref="containerRef" @scroll="handleScroll">
    <div class="virtual-scroll-phantom" :style="{ height: totalHeight + 'px' }"></div>
    <div class="virtual-scroll-content" :style="{ transform: `translateY(${offset}px)` }">
      <div
        v-for="(item, index) in visibleData"
        :key="itemKey ? item[itemKey] : index"
        class="virtual-scroll-item"
        @click="handleItemClick(item, startIndex + index)"
      >
        <slot name="item" :item="item" :index="startIndex + index"></slot>
      </div>
    </div>

    <div v-if="showLoading && isLoading" class="virtual-scroll-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ loadingText }}</span>
    </div>

    <div v-if="showNoMore && !isLoading && !hasMore" class="virtual-scroll-no-more">
      {{ noMoreText }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { Loading } from '@element-plus/icons-vue'

interface Props<T = any> {
  data: T[]
  itemHeight?: number
  bufferSize?: number
  itemKey?: string
  showLoading?: boolean
  loadingText?: string
  showNoMore?: boolean
  noMoreText?: string
  hasMore?: boolean
  isLoading?: boolean
  threshold?: number
}

const props = withDefaults(defineProps<Props>(), {
  itemHeight: 60,
  bufferSize: 5,
  showLoading: true,
  loadingText: '加载中...',
  showNoMore: true,
  noMoreText: '没有更多了',
  hasMore: true,
  isLoading: false,
  threshold: 200
})

const emit = defineEmits<{
  'load-more': []
  'item-click': [item: any, index: number]
}>()

const containerRef = ref<HTMLElement>()
const scrollTop = ref(0)
const containerHeight = ref(0)
const itemHeightCache = ref<Map<number, number>>(new Map())

const totalHeight = computed(() => {
  return props.data.length * props.itemHeight
})

const visibleCount = computed(() => {
  return Math.ceil(containerHeight.value / props.itemHeight) + props.bufferSize * 2
})

const startIndex = computed(() => {
  return Math.max(0, Math.floor(scrollTop.value / props.itemHeight) - props.bufferSize)
})

const endIndex = computed(() => {
  return Math.min(props.data.length, startIndex.value + visibleCount.value)
})

const visibleData = computed(() => {
  return props.data.slice(startIndex.value, endIndex.value)
})

const offset = computed(() => {
  return startIndex.value * props.itemHeight
})

const handleScroll = (e: Event) => {
  const target = e.target as HTMLElement
  scrollTop.value = target.scrollTop

  const { scrollTop, scrollHeight, clientHeight } = target
  if (scrollHeight - scrollTop - clientHeight <= props.threshold && !props.isLoading && props.hasMore) {
    emit('load-more')
  }
}

const handleItemClick = (item: any, index: number) => {
  emit('item-click', item, index)
}

const scrollToIndex = (index: number) => {
  if (!containerRef.value) return
  containerRef.value.scrollTop = index * props.itemHeight
}

const scrollToTop = () => {
  if (!containerRef.value) return
  containerRef.value.scrollTop = 0
}

const scrollToBottom = () => {
  if (!containerRef.value) return
  containerRef.value.scrollTop = totalHeight.value
}

const resize = () => {
  if (!containerRef.value) return
  containerHeight.value = containerRef.value.clientHeight
}

watch(() => props.data, () => {
  nextTick(resize)
}, { deep: true })

onMounted(() => {
  resize()
  window.addEventListener('resize', resize)
})

defineExpose({
  scrollToIndex,
  scrollToTop,
  scrollToBottom,
  resize
})
</script>

<style scoped>
.virtual-scroll-container {
  position: relative;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
}

.virtual-scroll-phantom {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  z-index: -1;
}

.virtual-scroll-content {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
}

.virtual-scroll-item {
  transition: background-color 0.2s;
}

.virtual-scroll-item:hover {
  background-color: #f5f7fa;
}

.virtual-scroll-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px;
  color: #909399;
  font-size: 14px;
}

.virtual-scroll-loading .el-icon {
  font-size: 18px;
}

.virtual-scroll-no-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  color: #909399;
  font-size: 14px;
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: #c0c4cc;
}

::-webkit-scrollbar-track {
  background: #f5f7fa;
}
</style>
