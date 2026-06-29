<template>
  <div ref="chartRef" class="base-chart" :style="{ width, height }" />
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption, ECharts } from 'echarts'

interface Props {
  option: EChartsOption
  width?: string
  height?: string
  theme?: string | object
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '400px',
  loading: false
})

const emit = defineEmits<{
  'chart-ready': [chart: ECharts]
}>()

const chartRef = ref<HTMLElement>()
let chartInstance: ECharts | null = null

function initChart() {
  if (!chartRef.value) return

  chartInstance = echarts.init(chartRef.value, props.theme)
  chartInstance.setOption(props.option)
  emit('chart-ready', chartInstance)
}

function updateChart() {
  if (!chartInstance) return
  chartInstance.setOption(props.option, true)
}

function resizeChart() {
  chartInstance?.resize()
}

function toggleLoading(loading: boolean) {
  if (loading) {
    chartInstance?.showLoading()
  } else {
    chartInstance?.hideLoading()
  }
}

watch(() => props.option, updateChart, { deep: true })

watch(() => props.loading, toggleLoading)

onMounted(() => {
  initChart()
  toggleLoading(props.loading)
  window.addEventListener('resize', resizeChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chartInstance?.dispose()
  chartInstance = null
})

defineExpose({
  resize: resizeChart,
  getInstance: () => chartInstance
})
</script>

<style scoped>
.base-chart {
  min-height: 200px;
}
</style>
