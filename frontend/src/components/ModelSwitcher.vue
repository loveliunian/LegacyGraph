<template>
  <div class="model-switcher">
    <el-dropdown v-if="providers.length > 0" @command="handleSwitch" trigger="click">
      <span class="switcher-trigger">
        <el-tag
          size="small"
          effect="plain"
          :type="currentProvider?.isActive ? '' : 'info'"
        >
          {{ currentLabel }}
        </el-tag>
        <el-icon class="switcher-arrow"><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="p in activeProviders"
            :key="p.providerCode"
            :command="p.providerCode"
            :class="{ 'is-active': p.isDefault }"
          >
            <div class="provider-item">
              <span>{{ p.providerCode }} / {{ p.modelId }}</span>
              <el-icon v-if="p.isDefault" class="default-check"><Check /></el-icon>
            </div>
          </el-dropdown-item>
          <el-dropdown-item divided command="__manage__">
            <el-icon><Setting /></el-icon>管理 LLM
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <el-button
      v-else
      text
      size="small"
      @click="goToSettings"
      class="no-provider-btn"
    >
      未配置 LLM
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { llmApi, type LlmProvider } from '@/api/llm.api'
import { ArrowDown, Check, Setting } from '@element-plus/icons-vue'

const router = useRouter()
const providers = ref<LlmProvider[]>([])

const activeProviders = computed(() =>
  providers.value.filter(p => p.isActive)
)

const currentProvider = computed(() =>
  providers.value.find(p => p.isDefault) || activeProviders.value[0] || null
)

const currentLabel = computed(() => {
  const p = currentProvider.value
  if (!p) return '未配置'
  return `${p.providerCode} · ${p.modelId}`
})

async function loadProviders() {
  try {
    providers.value = await llmApi.listAll()
  } catch {
    // 静默处理
  }
}

async function handleSwitch(code: string) {
  if (code === '__manage__') {
    goToSettings()
    return
  }
  try {
    await llmApi.setDefault(code)
    await loadProviders()
  } catch {
    // 静默处理
  }
}

function goToSettings() {
  // 如果在项目内，跳转到项目的 LLM 设置
  const projectId = router.currentRoute.value.params.projectId as string
  if (projectId) {
    router.push(`/projects/${projectId}/system/llm`)
  } else {
    router.push('/dashboard')
  }
}

onMounted(() => {
  loadProviders()
})
</script>

<style scoped>
.model-switcher {
  display: flex;
  align-items: center;
}

.switcher-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 6px;
  transition: background 0.2s;
}

.switcher-trigger:hover {
  background: var(--el-fill-color-light);
}

.switcher-arrow {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.provider-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 200px;
}

.default-check {
  color: var(--el-color-primary);
  margin-left: 8px;
}

.is-active {
  background: var(--el-color-primary-light-9);
}

.no-provider-btn {
  font-size: 12px;
  color: var(--el-color-warning);
}
</style>
