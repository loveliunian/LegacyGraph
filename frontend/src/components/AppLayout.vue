<template>
  <div class="app-layout">
    <!-- Top Header Bar -->
    <el-header class="app-header" height="var(--lg-header-height)">
      <div class="header-inner">
        <div class="header-left">
          <router-link to="/dashboard" class="header-logo">
            <svg width="28" height="28" viewBox="0 0 40 40" fill="none">
              <rect width="40" height="40" rx="10" fill="var(--el-color-primary)" opacity="0.9"/>
              <path d="M12 20L18 26L28 14" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span class="logo-text">LegacyGraph</span>
          </router-link>

          <nav class="header-nav">
            <router-link to="/dashboard" class="nav-link" :class="{ active: $route.path === '/dashboard' }">
              仪表盘
            </router-link>
            <router-link to="/projects" class="nav-link" :class="{ active: $route.path.startsWith('/projects') }">
              项目管理
            </router-link>
          </nav>
        </div>

        <div class="header-right">
          <!-- LLM 模型切换 -->
          <ModelSwitcher />

          <el-tooltip content="切换主题" placement="bottom">
            <el-button
              :icon="themeIcon"
              text
              circle
              @click="toggleTheme"
              class="header-icon-btn"
            />
          </el-tooltip>

          <el-tooltip content="语言切换" placement="bottom">
            <LangSwitcher />
          </el-tooltip>

          <el-dropdown @command="handleUserCommand" trigger="click">
            <span class="user-dropdown">
              <el-avatar :size="28" :icon="UserFilled" class="user-avatar" />
              <span class="user-name">{{ userInfo?.nickname || userInfo?.username }}</span>
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon>个人信息
                </el-dropdown-item>
                <el-dropdown-item command="settings">
                  <el-icon><Setting /></el-icon>系统设置
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </el-header>

    <!-- Page Content -->
    <div class="app-content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  UserFilled,
  ArrowDown,
  User,
  Setting,
  SwitchButton,
  Sunny,
  Moon
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import LangSwitcher from '@/components/LangSwitcher.vue'
import ModelSwitcher from '@/components/ModelSwitcher.vue'

const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()

const userInfo = computed(() => userStore.userInfo)
const themeIcon = computed(() => appStore.isDark ? Sunny : Moon)

const toggleTheme = () => {
  appStore.toggleTheme()
}

const handleUserCommand = async (command: string) => {
  switch (command) {
    case 'logout':
      try {
        await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })
        await userStore.logout()
        router.push('/login')
        ElMessage.success('已退出登录')
      } catch {
        // cancelled
      }
      break
    case 'profile':
      router.push('/dashboard')
      break
    case 'settings':
      router.push('/system/dictionaries')
      break
  }
}
</script>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--el-bg-color-page);
}

/* ── Header ── */
.app-header {
  display: flex;
  align-items: center;
  padding: 0 !important;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
  flex-shrink: 0;
  z-index: 100;
}

.header-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 100%;
  padding: 0 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 32px;
}

.header-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
}

.logo-text {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--el-text-color-primary);
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  text-decoration: none;
  padding: 6px 12px;
  border-radius: 6px;
  transition: var(--lg-transition);
}

.nav-link:hover {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
}

.nav-link.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon-btn {
  font-size: 18px;
  color: var(--el-text-color-secondary);
}

.header-icon-btn:hover {
  color: var(--el-text-color-primary);
}

.user-dropdown {
  display: flex;
  align-items: center;
  cursor: pointer;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  transition: var(--lg-transition);
}

.user-dropdown:hover {
  background: var(--el-fill-color-light);
}

.user-avatar {
  flex-shrink: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

/* ── Content ── */
.app-content {
  flex: 1;
  overflow-y: auto;
}
</style>
