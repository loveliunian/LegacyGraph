import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ScanTask } from '@/types'
import { scanApi } from '@/api'
import { clearVersionsCache } from '@/utils/versionsCache'

/** L-27: 扫描终态 — 出现这些 status 时停止轮询 */
const TERMINAL_STATUSES = ['SUCCESS', 'FAILED', 'CANCELLED', 'COMPLETED']

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

        // L-27: 终态停止轮询（SUCCESS/FAILED/CANCELLED/COMPLETED），不再仅依赖 progress >= 100
        if (TERMINAL_STATUSES.includes(result.status)) {
          stopPolling(taskId)
          removeRunningTask(taskId)
          // L-19: 扫描完成时主动清除版本缓存，使前端页面立即获取最新数据
          clearVersionsCache(projectId)
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
