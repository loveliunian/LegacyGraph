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

export interface PieChartData {
  name: string
  value: number
}

interface Props {
  data: PieChartData[]
  title?: string
  width?: string
  height?: string
  loading?: boolean
  legend?: boolean
  showTooltip?: boolean
  radius?: string | string[]
  roseType?: boolean | 'radius' | 'area'
  center?: string[]
  color?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '400px',
  loading: false,
  legend: true,
  showTooltip: true,
  radius: ['40%', '70%']
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
        trigger: 'item',
        formatter: '{a} <br/>{b}: {c} ({d}%)'
      }
    : undefined,
  legend: props.legend
    ? {
        orient: 'horizontal',
        bottom: 10,
        left: 'center'
      }
    : undefined,
  color: props.color,
  series: [
    {
      name: props.title,
      type: 'pie',
      radius: props.radius,
      center: props.center || ['50%', '50%'],
      roseType: props.roseType,
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 10,
        borderColor: '#fff',
        borderWidth: 2
      },
      label: {
        show: true,
        formatter: '{b}: {c} ({d}%)'
      },
      emphasis: {
        label: {
          show: true,
          fontSize: 14,
          fontWeight: 'bold'
        }
      },
      labelLine: {
        show: true
      },
      data: props.data
    }
  ]
}))
</script>
