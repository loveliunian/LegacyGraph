<template>
  <el-dropdown @command="handleLocaleChange" trigger="click">
    <span class="lang-switcher">
      <el-icon :size="18"><Switch /></el-icon>
      <span class="current-lang">{{ currentLangLabel }}</span>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="lang in locales"
          :key="lang.value"
          :command="lang.value"
          :class="{ active: currentLocale === lang.value }"
        >
          {{ lang.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Switch } from '@element-plus/icons-vue'
import i18n, { setLocale, getLocale, locales, type LocaleType } from '@/locales'

const currentLocale = computed(() => getLocale())

const currentLangLabel = computed(() => {
  const lang = locales.find(l => l.value === currentLocale.value)
  return lang?.label || 'Language'
})

function handleLocaleChange(locale: string) {
  setLocale(locale as LocaleType)
  ElMessage.success(i18n.global.t('common.operationSuccess'))
}
</script>

<style scoped>
.lang-switcher {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 4px;
  transition: all 0.2s;
  color: #606266;
  font-size: 14px;
}

.lang-switcher:hover {
  background-color: #f5f7fa;
  color: #409eff;
}

.current-lang {
  font-size: 13px;
}

:deep(.el-dropdown-menu__item.active) {
  color: #409eff;
  background-color: #ecf5ff;
}
</style>
