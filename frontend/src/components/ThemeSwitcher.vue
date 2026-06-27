<template>
  <el-tooltip :content="isDark ? '切换到亮色模式' : '切换到暗色模式'" placement="bottom">
    <el-button :icon="isDark ? Sunny : Moon" circle @click="toggleTheme" />
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Sunny, Moon } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const isDark = computed(() => appStore.theme === 'dark')

function toggleTheme() {
  appStore.toggleTheme()
}
</script>
