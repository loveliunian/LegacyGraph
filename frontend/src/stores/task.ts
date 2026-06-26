import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ScanTask } from '@/types'
import { scanApi } from '@/api'

export const useTaskStore = defineStore('task', () => {
  const runningTasks = ref<ScanTask[]>([])
  const pollingTaskIds = ref<Set<string>>(new Set())

  const addRunningTask = (task: ScanTask) => {
    const existing = runningTasks.value.find(t => t.id === task.id)
    if (!existing) {
      runningTasks.value.push(task)
    }
  }

  const removeRunningTask = (taskId: string) => {
    runningTasks.value = runningTasks.value.filter(t => t.id !== taskId)
  }

  const updateTaskProgress = (taskId: string, progress: number, stage?: string) => {
    const task = runningTasks.value.find(t => t.id === taskId)
    if (task) {
      task.progress = progress
      if (stage) {
        task.stage = stage
      }
    }
  }

  const startPolling = (taskId: string, projectId: string, interval: number = 3000) => {
    if (pollingTaskIds.value.has(taskId)) {
      return
    }
    pollingTaskIds.value.add(taskId)
    
    const poll = async () => {
      if (!pollingTaskIds.value.has(taskId)) {
        return
      }
      try {
        const result = await scanApi.getProgress(projectId, taskId)
        updateTaskProgress(taskId, result.progress, result.stage)
        
        if (result.progress >= 100) {
          stopPolling(taskId)
          removeRunningTask(taskId)
          return
        }
      } catch (error) {
        console.error('Polling task progress failed:', error)
      }
      setTimeout(poll, interval)
    }
    
    poll()
  }

  const stopPolling = (taskId: string) => {
    pollingTaskIds.value.delete(taskId)
  }

  const stopAllPolling = () => {
    pollingTaskIds.value.clear()
  }

  return {
    runningTasks,
    pollingTaskIds,
    addRunningTask,
    removeRunningTask,
    updateTaskProgress,
    startPolling,
    stopPolling,
    stopAllPolling
  }
})
