<template>
  <div class="system-layout">
    <div class="system-header">
      <h3>系统管理</h3>
    </div>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange" class="system-tabs">
      <el-tab-pane label="用户管理" name="users" />
      <el-tab-pane label="字典管理" name="dictionaries" />
      <el-tab-pane label="系统配置" name="settings" />
      <el-tab-pane label="LLM 提供商" name="llm" />
      <el-tab-pane label="提示词管理" name="prompts" />
    </el-tabs>

    <div class="system-content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

// 子路由名 → tab name
const routeToTab: Record<string, string> = {
  SystemUserList: 'users',
  SystemDictionaryList: 'dictionaries',
  SystemSettings: 'settings',
  LlmProviderSettings: 'llm',
  PromptTemplateList: 'prompts'
}

// tab name → 路由名
const tabToRoute: Record<string, string> = {
  users: 'SystemUserList',
  dictionaries: 'SystemDictionaryList',
  settings: 'SystemSettings',
  llm: 'LlmProviderSettings',
  prompts: 'PromptTemplateList'
}

const activeTab = ref(routeToTab[route.name as string] || 'dictionaries')

watch(() => route.name, (name) => {
  const tab = routeToTab[name as string]
  if (tab) activeTab.value = tab
})

function handleTabChange(tab: string) {
  const routeName = tabToRoute[tab]
  if (routeName) router.push({ name: routeName })
}
</script>

<style scoped>
.system-layout {
  padding: 20px;
}
.system-header {
  margin-bottom: 16px;
}
.system-header h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.system-tabs {
  margin-bottom: 16px;
}
.system-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.system-content {
  min-height: 400px;
}
</style>
