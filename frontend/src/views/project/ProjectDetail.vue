<template>
  <div class="project-layout">
    <el-container>
      <el-aside width="220px" class="sidebar">
        <div class="project-info">
          <h3>{{ currentProject?.projectName || '项目详情' }}</h3>
          <el-tag v-if="currentProject?.status" size="small">{{ getProjectStatusText(currentProject.status) }}</el-tag>
        </div>
        
        <el-menu
          :default-active="activeMenu"
          :default-openeds="defaultOpeneds"
          class="sidebar-menu"
          @select="handleMenuSelect"
        >
          <el-menu-item :index="`/projects/${projectId}/overview`">
            <el-icon><DataBoard /></el-icon>
            <span>项目概览</span>
          </el-menu-item>

          <el-menu-item :index="`/projects/${projectId}/qa`">
            <el-icon><ChatDotRound /></el-icon>
            <span>QA 问答</span>
          </el-menu-item>

          <el-sub-menu v-for="section in menuSections" :key="section.index" :index="section.index">
            <template #title>
              <el-icon><component :is="section.icon" /></el-icon>
              <span>{{ section.label }}</span>
            </template>
            <el-menu-item v-for="item in section.items" :key="item.path" :index="item.path">
              {{ item.label }}
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-aside>

      <el-container class="main-container">
        <el-header class="page-header">
          <div class="header-left">
            <el-breadcrumb separator="/">
              <el-breadcrumb-item :to="{ path: '/projects' }">项目列表</el-breadcrumb-item>
              <el-breadcrumb-item>{{ currentProject?.projectName }}</el-breadcrumb-item>
            </el-breadcrumb>
          </div>
          <div class="header-right">
            <el-space>
              <el-tag v-if="runningTasksCount > 0" type="warning" size="small">
                {{ runningTasksCount }} 个任务运行中
              </el-tag>
            </el-space>
          </div>
        </el-header>

        <el-main class="page-content">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataBoard,
  FolderOpened,
  Connection,
  DocumentChecked,
  VideoPlay,
  Tools,
  ChatDotRound
} from '@element-plus/icons-vue'
import { useProjectStore } from '@/stores/project'
import { useTaskStore } from '@/stores/task'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const taskStore = useTaskStore()

const projectId = computed(() => route.params.projectId as string)

const currentProject = computed(() => projectStore.currentProject)
const runningTasksCount = computed(() => taskStore.runningTasks.length)

interface ProjectMenuItem {
  label: string
  path: string
  routeNames?: string[]
}

interface ProjectMenuSection {
  index: string
  label: string
  icon: Component
  items: ProjectMenuItem[]
}

const menuSections = computed<ProjectMenuSection[]>(() => {
  const basePath = `/projects/${projectId.value}`
  return [
    {
      index: 'ingest',
      label: '接入与扫描',
      icon: markRaw(FolderOpened),
      items: [
        { label: '代码仓库', path: `${basePath}/repos` },
        { label: '数据库连接', path: `${basePath}/databases` },
        { label: '文档资料', path: `${basePath}/documents` },
        { label: '扫描版本', path: `${basePath}/scan-versions` },
        { label: '新建扫描', path: `${basePath}/scan-versions/create` }
      ]
    },
    {
      index: 'graph',
      label: '图谱与问答',
      icon: markRaw(Connection),
      items: [
        { label: '统一图谱', path: `${basePath}/graph/unified` },
        { label: '业务图谱', path: `${basePath}/graph/business` },
        { label: '功能图谱', path: `${basePath}/graph/feature` },
        { label: '代码图谱', path: `${basePath}/graph/code` },
        { label: '数据血缘', path: `${basePath}/graph/lineage` },
        { label: '运行链路', path: `${basePath}/graph/runtime` }
      ]
    },
    {
      index: 'evidence',
      label: '证据与审核',
      icon: markRaw(DocumentChecked),
      items: [
        { label: '证据工作台', path: `${basePath}/workbench` },
        { label: '事实列表', path: `${basePath}/facts` },
        { label: '证据检索', path: `${basePath}/evidence` },
        { label: '审核队列', path: `${basePath}/reviews` },
        { label: '审核历史', path: `${basePath}/review-history` }
      ]
    },
    {
      index: 'quality',
      label: '测试与报告',
      icon: markRaw(VideoPlay),
      items: [
        { label: '测试用例', path: `${basePath}/test-cases`, routeNames: ['TestCaseEditorNew', 'TestCaseEditorEdit'] },
        { label: '测试执行', path: `${basePath}/test-runs`, routeNames: ['TestRunDetail'] },
        { label: '验证报告', path: `${basePath}/validation` },
        { label: '迁移风险', path: `${basePath}/migration/risks`, routeNames: ['MigrationRiskDetail'] },
        { label: '操作日志', path: `${basePath}/audit/logs`, routeNames: ['AuditLogDetail'] }
      ]
    },
    {
      index: 'automation',
      label: '智能变更',
      icon: markRaw(Tools),
      items: [
        { label: '变更任务', path: `${basePath}/change-tasks` },
        { label: 'AI 助手', path: `${basePath}/agents` }
      ]
    }
  ]
})

const activeMenu = computed(() => {
  const routeName = route.name as string
  const matchedItem = menuSections.value
    .flatMap(section => section.items)
    .find(item => item.routeNames?.includes(routeName))
  return matchedItem?.path || route.path
})

const defaultOpeneds = computed(() =>
  menuSections.value
    .filter(section => section.items.some(item => item.path === activeMenu.value))
    .map(section => section.index)
)

const getProjectStatusText = (status: string) => dictLabel('project_status', status)

onMounted(async () => {
  preloadDicts(['project_status'])
  if (projectId.value) {
    projectStore.setCurrentProject(projectId.value)
    await projectStore.fetchCurrentProject()
  }
})

function handleMenuSelect(index: string) {
  router.push(index)
}
</script>

<style scoped>
.project-layout {
  height: 100vh;
  background: #f5f7fa;
}

.sidebar {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
}

.project-info {
  padding: 20px 16px;
  border-bottom: 1px solid #e4e7ed;
}

.project-info h3 {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.sidebar-menu {
  border-right: none;
}

.sidebar-menu :deep(.el-sub-menu__title),
.sidebar-menu :deep(.el-menu-item) {
  font-size: 14px;
}

.main-container {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 60px;
}

.header-right {
  display: flex;
  align-items: center;
}

.page-content {
  padding: 24px;
  overflow-y: auto;
}
</style>
