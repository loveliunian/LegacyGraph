import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { GraphNode, GraphEdge, GraphData, GraphNodeType, GraphEdgeType } from '@/types'

export const useGraphStore = defineStore('graph', () => {
  const currentGraphType = ref<string>('CODE')
  const graphVersionId = ref<string | null>(null)
  const graphData = ref<GraphData | null>(null)
  const selectedNode = ref<GraphNode | null>(null)
  const selectedEdge = ref<GraphEdge | null>(null)
  
  const filters = ref({
    nodeTypes: [] as GraphNodeType[],
    edgeTypes: [] as GraphEdgeType[],
    minConfidence: 0,
    reviewStatus: undefined as string | undefined,
    keyword: ''
  })

  const setGraphType = (type: string) => {
    currentGraphType.value = type
  }

  const setGraphData = (data: GraphData | null) => {
    graphData.value = data
  }

  const selectNode = (node: GraphNode | null) => {
    selectedNode.value = node
    selectedEdge.value = null
  }

  const selectEdge = (edge: GraphEdge | null) => {
    selectedEdge.value = edge
    selectedNode.value = null
  }

  const clearSelection = () => {
    selectedNode.value = null
    selectedEdge.value = null
  }

  const setFilters = (newFilters: Partial<typeof filters.value>) => {
    Object.assign(filters.value, newFilters)
  }

  const resetFilters = () => {
    filters.value = {
      nodeTypes: [],
      edgeTypes: [],
      minConfidence: 0,
      reviewStatus: undefined,
      keyword: ''
    }
  }

  return {
    currentGraphType,
    graphVersionId,
    graphData,
    selectedNode,
    selectedEdge,
    filters,
    setGraphType,
    setGraphData,
    selectNode,
    selectEdge,
    clearSelection,
    setFilters,
    resetFilters
  }
})
