<template>
  <el-dropdown
    trigger="click"
    @command="handleCommand">
    <el-badge
      :value="unreadCount"
      :hidden="unreadCount === 0"
      :max="99">
      <el-icon
        :size="20"
        style="cursor: pointer;">
        <Bell />
      </el-icon>
    </el-badge>
    <template #dropdown>
      <el-dropdown-menu style="width: 360px; max-height: 500px; overflow-y: auto;">
        <el-dropdown-item
          v-if="notifications.length === 0"
          disabled>
          暂无通知
        </el-dropdown-item>
        <el-dropdown-item
          v-for="n in notifications"
          :key="n.id"
          :command="n.id"
          :divided="notifications.indexOf(n) > 0"
        >
          <div class="notification-item">
            <div class="notification-header">
              <el-tag
                :type="eventTypeTag(n.eventType)"
                size="small">
                {{ n.eventType }}
              </el-tag>
              <span class="notification-time">{{ formatTime(n.createdAt) }}</span>
            </div>
            <div class="notification-content">{{ n.payload?.summary || n.eventType }}</div>
            <el-tag
              v-if="!n.read"
              type="danger"
              size="small"
              style="margin-top: 4px;">
              未读
            </el-tag>
          </div>
        </el-dropdown-item>
        <el-dropdown-item divided>
          <el-button
            link
            type="primary"
            @click.stop="handleMarkAllRead">
            全部标记已读
          </el-button>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { notificationApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useProjectStore } from '@/stores/project'

interface Notification {
  id: string
  eventType: string
  payload: any
  read: boolean
  createdAt: string
}

const notifications = ref<Notification[]>([])
let eventSource: EventSource | null = null

const unreadCount = computed(() => notifications.value.filter(n => !n.read).length)

const formatTime = (time: string) => time ? dayjs(time).format('MM-DD HH:mm') : '-'

const eventTypeTag = (type: string) => {
  const map: Record<string, string> = {
    SCAN_COMPLETED: 'success',
    SCAN_FAILED: 'danger',
    EVIDENCE_CONFLICT: 'warning',
    PR_CREATED: 'primary',
  }
  return map[type] || 'info'
}

async function loadNotifications() {
  try {
    const projectStore = useProjectStore()
    const res = await notificationApi.getRecent(projectStore.currentProjectId || 'default')
    const list = Array.isArray(res) ? res : []
    if (Array.isArray(list)) {
      notifications.value = list.map((n: any) => ({
        id: n.id,
        eventType: n.eventType || n.type,
        payload: n.payload || {},
        read: n.read || false,
        createdAt: n.createdAt || n.created_at,
      }))
    }
  } catch { /* 静默降级 */ }
}

function connectSSE() {
  const userStore = useUserStore()
  const projectStore = useProjectStore()
  const projectId = projectStore.currentProjectId || 'default'
  const token = userStore.accessToken || ''
  eventSource = new EventSource(`/api/lg/notifications/stream?projectId=${projectId}&token=${token}`)
  
  eventSource.addEventListener('notification', (event) => {
    try {
      const n = JSON.parse(event.data) as Notification
      notifications.value.unshift(n)
      if (notifications.value.length > 50) notifications.value.pop()
    } catch (e) {
      console.error('Failed to parse notification:', e)
    }
  })
  
  eventSource.onerror = () => {
    console.warn('SSE connection error, will retry automatically')
  }
}

function handleMarkAllRead() {
  notifications.value.forEach(n => n.read = true)
  ElMessage.success('已全部标记为已读')
}

function handleCommand(id: string) {
  const n = notifications.value.find(x => x.id === id)
  if (n) {
    n.read = true
    notificationApi.markRead(id)
    ElMessage.info(`${n.eventType}: ${n.payload?.summary || '查看详情'}`)
  }
}

function addNotification(eventType: string, payload: any) {
  const n: Notification = {
    id: Date.now().toString(),
    eventType,
    payload,
    read: false,
    createdAt: new Date().toISOString(),
  }
  notifications.value.unshift(n)
  if (notifications.value.length > 50) notifications.value.pop()
}

defineExpose({ addNotification })

onMounted(() => {
  loadNotifications()
  connectSSE()
})

onUnmounted(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.notification-item { padding: 4px 0; }
.notification-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.notification-time { font-size: 12px; color: #999; }
.notification-content { font-size: 13px; line-height: 1.4; }
</style>
