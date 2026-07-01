import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ScanTask } from '@/types'
import { scanApi } from '@/api'

export const useTaskStore = defineStore('task', () => {
  const runningTasks = ref<ScanTask[]>([])
  const pollingTaskIds = ref<Set<string>>(new Set())
  // F-M14：存储轮询定时器 ID，stopAllPolling/stopPolling 可取消未完成的 setTimeout
  const pollingTimers = new Map<string, ReturnType<typeof setTimeout>>()

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
        const result = await scanApi.progress(projectId, taskId) as any
        updateTaskProgress(taskId, result.progress, result.stage)
        
        if (result.progress >= 100) {
          stopPolling(taskId)
          removeRunningTask(taskId)
          return
        }
      } catch (error) {
        console.error('Polling task progress failed:', error)
      }
      const timerId = setTimeout(poll, interval)
      pollingTimers.set(taskId, timerId)
    }
    
    poll()
  }

  const stopPolling = (taskId: string) => {
    pollingTaskIds.value.delete(taskId)
    const timerId = pollingTimers.get(taskId)
    if (timerId) {
      clearTimeout(timerId)
      pollingTimers.delete(taskId)
    }
  }

  const stopAllPolling = () => {
    pollingTimers.forEach(timerId => clearTimeout(timerId))
    pollingTimers.clear()
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
