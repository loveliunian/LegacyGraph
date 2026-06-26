import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useGraphStore } from '@/stores/graph'

describe('Graph Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should initialize with default values', () => {
    const store = useGraphStore()
    expect(store.currentGraphType).toBe('CODE')
    expect(store.graphVersionId).toBeNull()
    expect(store.graphData).toBeNull()
    expect(store.selectedNode).toBeNull()
    expect(store.selectedEdge).toBeNull()
  })

  it('should set graph type correctly', () => {
    const store = useGraphStore()
    store.setGraphType('BUSINESS')
    expect(store.currentGraphType).toBe('BUSINESS')
  })

  it('should select node correctly', () => {
    const store = useGraphStore()
    const testNode = { id: 'test-node-1', label: 'Test Node', type: 'METHOD' } as any
    store.selectNode(testNode)
    expect(store.selectedNode).toEqual(testNode)
    expect(store.selectedEdge).toBeNull()
  })

  it('should select edge correctly', () => {
    const store = useGraphStore()
    const testEdge = { id: 'test-edge-1', source: 'node1', target: 'node2', type: 'CALLS' } as any
    store.selectEdge(testEdge)
    expect(store.selectedEdge).toEqual(testEdge)
    expect(store.selectedNode).toBeNull()
  })

  it('should clear selection correctly', () => {
    const store = useGraphStore()
    const testNode = { id: 'test-node-1', label: 'Test Node', type: 'METHOD' } as any
    store.selectNode(testNode)
    store.clearSelection()
    expect(store.selectedNode).toBeNull()
    expect(store.selectedEdge).toBeNull()
  })

  it('should set graph data correctly', () => {
    const store = useGraphStore()
    const testData = {
      nodes: [{ id: '1', label: 'Node1' }],
      edges: [{ id: 'e1', source: '1', target: '2' }]
    } as any
    store.setGraphData(testData)
    expect(store.graphData).toEqual(testData)
  })

  it('should have default filters with correct initial values', () => {
    const store = useGraphStore()
    expect(store.filters.nodeTypes).toEqual([])
    expect(store.filters.edgeTypes).toEqual([])
    expect(store.filters.minConfidence).toBe(0)
  })

  it('should update filters correctly', () => {
    const store = useGraphStore()
    store.setFilters({
      nodeTypes: ['METHOD', 'CLASS'],
      minConfidence: 0.5
    })
    expect(store.filters.nodeTypes).toEqual(['METHOD', 'CLASS'])
    expect(store.filters.minConfidence).toBe(0.5)
  })

  it('should reset filters correctly', () => {
    const store = useGraphStore()
    store.setFilters({
      nodeTypes: ['METHOD'],
      edgeTypes: ['CALLS'],
      minConfidence: 0.5
    })
    store.resetFilters()
    expect(store.filters.nodeTypes).toEqual([])
    expect(store.filters.edgeTypes).toEqual([])
    expect(store.filters.minConfidence).toBe(0)
  })
})
