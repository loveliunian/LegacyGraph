import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from '@/stores/task'

describe('Task Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should initialize with empty tasks', () => {
    const store = useTaskStore()
    expect(store.runningTasks).toEqual([])
  })

  it('should add running task', () => {
    const store = useTaskStore()
    const testTask = {
      id: 'task-1',
      taskName: 'Test Scan',
      status: 'RUNNING',
      progress: 50
    } as any
    store.addRunningTask(testTask)
    expect(store.runningTasks).toHaveLength(1)
    expect(store.runningTasks[0].id).toBe('task-1')
  })

  it('should not add duplicate task', () => {
    const store = useTaskStore()
    const testTask = {
      id: 'task-1',
      taskName: 'Test Scan',
      status: 'RUNNING',
      progress: 50
    } as any
    store.addRunningTask(testTask)
    store.addRunningTask(testTask)
    expect(store.runningTasks).toHaveLength(1)
  })

  it('should update task progress', () => {
    const store = useTaskStore()
    const testTask = {
      id: 'task-1',
      taskName: 'Test Scan',
      status: 'RUNNING',
      progress: 50
    } as any
    store.addRunningTask(testTask)
    store.updateTaskProgress('task-1', 75)
    expect(store.runningTasks[0].progress).toBe(75)
  })

  it('should remove running task', () => {
    const store = useTaskStore()
    const testTask = {
      id: 'task-1',
      taskName: 'Test Scan',
      status: 'RUNNING',
      progress: 50
    } as any
    store.addRunningTask(testTask)
    store.removeRunningTask('task-1')
    expect(store.runningTasks).toHaveLength(0)
  })

  it('should not throw error when removing non-existent task', () => {
    const store = useTaskStore()
    expect(() => store.removeRunningTask('non-existent')).not.toThrow()
  })

  it('should not throw error when updating non-existent task', () => {
    const store = useTaskStore()
    expect(() => store.updateTaskProgress('non-existent', 50)).not.toThrow()
  })
})
