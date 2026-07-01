<template>
  <el-dropdown trigger="click" @command="handleCommand" @visible-change="onVisibleChange">
    <div class="notification-trigger">
      <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="notification-badge">
        <el-button :icon="Bell" circle />
      </el-badge>
    </div>

    <template #dropdown>
      <div class="notification-dropdown">
        <div class="notification-header">
          <span class="header-title">通知中心</span>
          <div class="header-actions">
            <el-button text size="small" @click.stop="markAllAsRead" v-if="unreadCount > 0">
              全部已读
            </el-button>
            <el-button text size="small" @click.stop="clearAll">
              清空
            </el-button>
          </div>
        </div>

        <div class="notification-tabs">
          <div
            v-for="tab in tabs"
            :key="tab.key"
            class="tab-item"
            :class="{ active: activeTab === tab.key }"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
            <el-badge v-if="getUnreadByType(tab.key) > 0" :value="getUnreadByType(tab.key)" class="tab-badge" />
          </div>
        </div>

        <div class="notification-list" v-if="filteredNotifications.length > 0">
          <transition-group name="list">
            <div
              v-for="item in filteredNotifications"
              :key="item.id"
              class="notification-item"
              :class="{ unread: !item.isRead }"
              @click="handleItemClick(item)"
            >
              <div class="item-icon" :class="item.type">
                <el-icon :size="20">
                  <component :is="getNotificationIcon(item.type)" />
                </el-icon>
              </div>
              <div class="item-content">
                <div class="item-title">{{ item.title }}</div>
                <div class="item-desc">{{ item.content }}</div>
                <div class="item-meta">
                  <span class="item-time">{{ formatTime(item.timestamp) }}</span>
                  <el-button
                    v-if="item.actions"
                    v-for="action in item.actions"
                    :key="action.key"
                    text
                    size="small"
                    type="primary"
                    @click.stop="handleAction(item, action)"
                  >
                    {{ action.label }}
                  </el-button>
                </div>
              </div>
            </div>
          </transition-group>
        </div>

        <div class="notification-empty" v-else>
          <el-empty description="暂无通知" :image-size="80" />
        </div>

        <div class="notification-footer" v-if="notifications.length > 5">
          <router-link to="/notification/list" class="view-all-link">
            查看全部通知
            <el-icon><ArrowRight /></el-icon>
          </router-link>
        </div>
      </div>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { Bell, CircleCheck, InfoFilled, WarningFilled, CircleClose, ArrowRight, Tools } from '@element-plus/icons-vue'
import { get } from '@/utils/request'

export interface NotificationAction {
  key: string
  label: string
}

export interface NotificationItem {
  id: string
  type: 'info' | 'success' | 'warning' | 'error' | 'system'
  title: string
  content: string
  timestamp: Date
  isRead: boolean
  data?: Record<string, any>
  actions?: NotificationAction[]
}

const router = useRouter()

const activeTab = ref('all')
const notifications = ref<NotificationItem[]>([])

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'info', label: '信息' },
  { key: 'system', label: '系统' }
]

const unreadCount = computed(() => notifications.value.filter(n => !n.isRead).length)

const filteredNotifications = computed(() => {
  if (activeTab.value === 'all') return notifications.value.slice(0, 10)
  return notifications.value.filter(n => n.type === activeTab.value).slice(0, 10)
})

function getUnreadByType(type: string): number {
  if (type === 'all') return unreadCount.value
  return notifications.value.filter(n => n.type === type && !n.isRead).length
}

function getNotificationIcon(type: string) {
  const iconMap: Record<string, any> = {
    info: InfoFilled,
    success: CircleCheck,
    warning: WarningFilled,
    error: CircleClose,
    system: Tools
  }
  return iconMap[type] || InfoFilled
}

function handleItemClick(item: NotificationItem) {
  if (!item.isRead) {
    markAsRead(item.id)
  }
  if (item.data?.url) {
    router.push(item.data.url)
  }
}

function handleAction(item: NotificationItem, action: NotificationAction) {
  emit('action', { item, action })
}

function handleCommand(command: string) {
  emit('command', command)
}

function onVisibleChange(visible: boolean) {
  if (visible && notifications.value.length === 0) {
    loadNotifications()
  }
}

function markAsRead(id: string) {
  const item = notifications.value.find(n => n.id === id)
  if (item) {
    item.isRead = true
  }
}

function markAllAsRead() {
  notifications.value.forEach(n => n.isRead = true)
  ElMessage.success('已全部标记为已读')
}

function clearAll() {
  notifications.value = []
  ElMessage.success('已清空所有通知')
}

function formatTime(date: Date): string {
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (hours < 24) return `${hours} 小时前`
  if (days < 7) return `${days} 天前`
  return date.toLocaleDateString()
}

async function loadNotifications() {
  try {
    const res: any = await get('/lg/audit/list', { pageNum: 1, pageSize: 10 })
    const logs = res?.list || res?.records || []
    notifications.value = logs.map((log: any, idx: number) => {
      const typeMap: Record<string, NotificationItem['type']> = {
        CREATE: 'success',
        UPDATE: 'info',
        DELETE: 'error',
        QUERY: 'info',
        LOGIN: 'info',
        LOGOUT: 'info',
      }
      return {
        id: String(log.id || idx),
        type: typeMap[log.operation] || 'info',
        title: log.operation || '系统通知',
        content: log.description || log.operation || '',
        timestamp: log.createdAt ? new Date(log.createdAt) : new Date(),
        isRead: false,
        data: {},
        actions: [],
      }
    })
  } catch {
    // 后端不可用时显示空状态，不阻断页面渲染
    notifications.value = []
  }
}

// 定时轮询通知，每30秒刷新一次
let pollTimer: number | null = null
function startPolling() {
  loadNotifications()
  pollTimer = window.setInterval(() => {
    loadNotifications()
  }, 30000)
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const emit = defineEmits<{
  action: [payload: { item: NotificationItem; action: NotificationAction }]
  command: [command: string]
}>()

onMounted(() => {
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.notification-trigger {
  display: inline-flex;
  align-items: center;
}

.notification-badge {
  margin-right: 0;
}

.notification-dropdown {
  width: 420px;
  max-height: 500px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.notification-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
}

.header-title {
  font-weight: 600;
  color: #303133;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.notification-tabs {
  display: flex;
  border-bottom: 1px solid #ebeef5;
  background: #f5f7fa;
}

.tab-item {
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
  color: #606266;
  border-bottom: 2px solid transparent;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tab-item:hover {
  color: #409eff;
}

.tab-item.active {
  color: #409eff;
  border-bottom-color: #409eff;
}

.tab-badge {
  margin-left: 0;
}

.notification-list {
  flex: 1;
  overflow-y: auto;
  max-height: 350px;
}

.notification-item {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #f5f7fa;
  cursor: pointer;
  transition: background 0.2s;
}

.notification-item:hover {
  background: #f5f7fa;
}

.notification-item.unread {
  background: #ecf5ff;
}

.notification-item.unread:hover {
  background: #e6f1ff;
}

.item-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.item-icon.info {
  background: #ecf5ff;
  color: #409eff;
}

.item-icon.success {
  background: #f0f9eb;
  color: #67c23a;
}

.item-icon.warning {
  background: #fdf6ec;
  color: #e6a23c;
}

.item-icon.error {
  background: #fef0f0;
  color: #f56c6c;
}

.item-icon.system {
  background: #f4f4f5;
  color: #909399;
}

.item-content {
  flex: 1;
  min-width: 0;
}

.item-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.item-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.item-time {
  font-size: 12px;
  color: #909399;
}

.notification-empty {
  padding: 40px 20px;
}

.notification-footer {
  padding: 12px 16px;
  border-top: 1px solid #ebeef5;
  text-align: center;
}

.view-all-link {
  color: #409eff;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
}

.view-all-link:hover {
  text-decoration: underline;
}

.list-enter-active,
.list-leave-active {
  transition: all 0.3s ease;
}

.list-enter-from,
.list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}
</style>
