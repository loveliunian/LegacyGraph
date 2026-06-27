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
import { computed, ref } from 'vue'
import BaseChart from './BaseChart.vue'
import type { EChartsOption, ECharts } from 'echarts'

export interface BarChartData {
  name: string
  data: (number | string)[]
}

interface Props {
  xAxisData: string[]
  series: BarChartData[]
  title?: string
  width?: string
  height?: string
  loading?: boolean
  legend?: boolean
  showTooltip?: boolean
  horizontal?: boolean
  stack?: boolean
  color?: string[]
  barWidth?: number | string
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '400px',
  loading: false,
  legend: true,
  showTooltip: true,
  horizontal: false,
  stack: false
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
        axisPointer: {
          type: props.horizontal ? 'shadow' : 'line'
        }
      }
    : undefined,
  legend: props.legend
    ? {
        top: props.title ? 40 : 10,
        left: 'center'
      }
    : undefined,
  grid: {
    left: '3%',
    right: '4%',
    bottom: '3%',
    containLabel: true
  },
  xAxis: props.horizontal
    ? {
        type: 'value'
      }
    : {
        type: 'category',
        data: props.xAxisData,
        axisLabel: {
          rotate: props.xAxisData.length > 10 ? 45 : 0
        }
      },
  yAxis: props.horizontal
    ? {
        type: 'category',
        data: props.xAxisData
      }
    : {
        type: 'value'
      },
  color: props.color,
  series: props.series.map((item) => ({
    type: 'bar',
    name: item.name,
    data: item.data,
    stack: props.stack ? 'total' : undefined,
    barWidth: props.barWidth,
    itemStyle: {
      borderRadius: props.horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]
    }
  }))
}))
</script>
