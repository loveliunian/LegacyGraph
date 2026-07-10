<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import type { LocaleType } from '@/locales'

const elementLocale = ref(getInitialLocale())

function getInitialLocale() {
  const saved = localStorage.getItem('legacy_graph_locale')
  return saved === 'en-US' ? en : zhCn
}

const handleLocaleChange = ((e: CustomEvent<LocaleType>) => {
  elementLocale.value = e.detail === 'en-US' ? en : zhCn
}) as EventListener

window.addEventListener('locale-changed', handleLocaleChange)

onUnmounted(() => {
  window.removeEventListener('locale-changed', handleLocaleChange)
})
</script>

<style>
body > #app {
  font-family: var(--font-body);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  height: 100vh;
}
</style>
