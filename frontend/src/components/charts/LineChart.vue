<template>
  <BaseChart
    ref="baseChartRef"
    :option="chartOption"
    :width="width"
    :height="height"
    :loading="loading"
    @chart-ready="emit('chart-ready', $event)"
  />
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import BaseChart from './BaseChart.vue'
import type { EChartsOption, ECharts } from 'echarts'

export interface LineChartData {
  name: string
  type?: 'line' | 'bar'
  smooth?: boolean
  data: (number | string | null)[]
  areaStyle?: object
}

interface Props {
  xAxisData: string[]
  series: LineChartData[]
  title?: string
  width?: string
  height?: string
  loading?: boolean
  legend?: boolean
  grid?: object
  showTooltip?: boolean
  color?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '400px',
  loading: false,
  legend: true,
  showTooltip: true
})

const emit = defineEmits<{
  'chart-ready': [chart: ECharts]
}>()

const baseChartRef = ref()

const chartOption = computed<EChartsOption>(() => ({
  title: props.title
    ? {
        text: props.title,
        left: 'center',
        textStyle: { fontSize: 16, fontWeight: 500 }
      }
    : undefined,
  tooltip: props.showTooltip
    ? {
        trigger: 'axis',
        axisPointer: { type: 'cross' }
      }
    : undefined,
  legend: props.legend
    ? {
        top: props.title ? 40 : 10,
        left: 'center'
      }
    : undefined,
  grid: props.grid || {
    left: '3%',
    right: '4%',
    bottom: '3%',
    containLabel: true
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: props.xAxisData
  },
  yAxis: {
    type: 'value'
  },
  color: props.color,
  series: props.series.map((item) => ({
    type: item.type || 'line',
    name: item.name,
    data: item.data,
    smooth: item.smooth ?? true,
    areaStyle: item.areaStyle
  }))
}))
</script>
