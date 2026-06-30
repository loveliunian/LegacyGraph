<template>
  <div class="project-layout">
    <el-container>
      <el-aside width="220px" class="sidebar">
        <div class="project-info">
          <h3>{{ currentProject?.projectName || '项目详情' }}</h3>
          <el-tag v-if="currentProject?.status" size="small">{{ currentProject.status }}</el-tag>
        </div>
        
        <el-menu
          :default-active="activeMenu"
          class="sidebar-menu"
          @select="handleMenuSelect"
        >
          <el-menu-item :index="`/projects/${projectId}/overview`">
            <el-icon><DataBoard /></el-icon>
            <span>项目概览</span>
          </el-menu-item>

          <el-sub-menu index="sources">
            <template #title>
              <el-icon><FolderOpened /></el-icon>
              <span>资料接入</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/repos`">代码仓库</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/databases`">数据库连接</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/documents`">文档资料</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="scan">
            <template #title>
              <el-icon><Search /></el-icon>
              <span>扫描任务</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/scan-versions`">任务列表</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/scan-versions/create`">新建扫描</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="graph">
            <template #title>
              <el-icon><Connection /></el-icon>
              <span>图谱中心</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/graph/unified`">统一图谱</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/graph/business`">业务图谱</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/graph/feature`">功能图谱</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/graph/code`">代码图谱</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/graph/lineage`">数据血缘</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/graph/runtime`">运行链路</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="fact">
            <template #title>
              <el-icon><Document /></el-icon>
              <span>事实与证据</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/facts`">事实列表</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/evidence`">证据检索</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="review">
            <template #title>
              <el-icon><DocumentChecked /></el-icon>
              <span>人工审核</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/reviews`">审核队列</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/review-history`">审核历史</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="test">
            <template #title>
              <el-icon><VideoPlay /></el-icon>
              <span>测试验证</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/test-cases`">测试用例</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/test-runs`">测试执行</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="report">
            <template #title>
              <el-icon><Tickets /></el-icon>
              <span>验证报告</span>
            </template>
            <el-menu-item :index="`/projects/${projectId}/validation`">验证报告</el-menu-item>
            <el-menu-item :index="`/projects/${projectId}/migration/risks`">迁移风险</el-menu-item>
          </el-sub-menu>

          <el-menu-item :index="`/projects/${projectId}/workbench`">
            <el-icon><DocumentChecked /></el-icon>
            <span>证据工作台</span>
          </el-menu-item>
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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataBoard,
  FolderOpened,
  Search,
  Connection,
  Document,
  DocumentChecked,
  VideoPlay,
  Tickets
} from '@element-plus/icons-vue'
import { useProjectStore } from '@/stores/project'
import { useTaskStore } from '@/stores/task'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const taskStore = useTaskStore()

const projectId = computed(() => route.params.projectId as string)

const currentProject = computed(() => projectStore.currentProject)
const runningTasksCount = computed(() => taskStore.runningTasks.length)

const activeMenu = computed(() => route.path)

onMounted(async () => {
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
