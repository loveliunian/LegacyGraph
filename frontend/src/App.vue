<template>
  <div id="app">
    <el-container>
      <el-aside width="200px">
        <el-menu
          :default-active="activeMenu"
          class="sidebar-menu"
          router
        >
          <el-menu-item index="/projects">
            <el-icon><List /></el-icon>
            <span>项目列表</span>
          </el-menu-item>
          <el-sub-menu index="graph">
            <template #title>
              <el-icon><Connection /></el-icon>
              <span>图谱视图</span>
            </template>
            <el-menu-item index="/graph/code">代码图谱</el-menu-item>
            <el-menu-item index="/graph/feature">功能图谱</el-menu-item>
            <el-menu-item index="/graph/business">业务图谱</el-menu-item>
          </el-sub-menu>
          <el-menu-item index="/scan">
            <el-icon><Search /></el-icon>
            <span>扫描任务</span>
          </el-menu-item>
          <el-menu-item index="/review">
            <el-icon><DocumentChecked /></el-icon>
            <span>人工确认</span>
          </el-menu-item>
          <el-menu-item index="/test">
            <el-icon><VideoPlay /></el-icon>
            <span>测试执行</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header>
          <h2>LegacyGraph - 老项目 AI 图谱理解平台</h2>
        </el-header>
        <el-main>
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  List,
  Connection,
  Search,
  DocumentChecked,
  VideoPlay
} from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = ref(route.path)
</script>

<style>
#app {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Fira Sans', 'Droid Sans', 'Helvetica Neue', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  height: 100vh;
}

.el-container {
  height: 100%;
}

.el-aside {
  border-right: 1px solid #e6e6e6;
}

.el-header {
  background-color: #fff;
  border-bottom: 1px solid #e6e6e6;
  line-height: 60px;
}

.el-header h2 {
  margin: 0;
  color: #303133;
  font-size: 18px;
}

.sidebar-menu {
  border-right: none;
}
</style>
