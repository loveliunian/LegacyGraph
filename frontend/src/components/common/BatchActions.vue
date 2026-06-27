<template>
  <div class="batch-actions" v-if="selectedCount > 0">
    <div class="batch-info">
      <el-tag type="info" size="small">
        已选择 {{ selectedCount }} 项
      </el-tag>
      <el-button
        v-if="showClear"
        type="text"
        size="small"
        @click="$emit('clear')"
      >
        清除选择
      </el-button>
    </div>

    <div class="batch-buttons">
      <template v-for="action in actions" :key="action.key">
        <el-button
          v-if="!action.divider"
          :type="action.type || 'primary'"
          :size="action.size || 'small'"
          :icon="action.icon"
          :disabled="action.disabled"
          :loading="action.loading"
          @click="$emit('action', action.key, selectedItems)"
        >
          {{ action.label }}
        </el-button>
        <el-divider v-else direction="vertical" />
      </template>
    </div>

    <div class="batch-more" v-if="moreActions.length > 0">
      <el-dropdown @command="(key: string) => $emit('action', key, selectedItems)">
        <el-button size="small">
          更多操作
          <el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="action in moreActions"
              :key="action.key"
              :command="action.key"
              :disabled="action.disabled"
            >
              <el-icon v-if="action.icon" class="action-icon">
                <component :is="action.icon" />
              </el-icon>
              {{ action.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'

interface BatchAction {
  key: string
  label: string
  icon?: any
  type?: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  size?: 'large' | 'default' | 'small'
  disabled?: boolean
  loading?: boolean
  divider?: boolean
}

interface Props<T = any> {
  actions: BatchAction[]
  moreActions?: BatchAction[]
  selectedItems: T[]
  showClear?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  moreActions: () => [],
  showClear: true
})

const emit = defineEmits<{
  action: [key: string, items: any[]]
  clear: []
}>()

const selectedCount = computed(() => props.selectedItems.length)
</script>

<style scoped>
.batch-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 8px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px rgba(102, 126, 234, 0.3);
}

.batch-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.batch-info .el-tag {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
}

.batch-info .el-button {
  color: rgba(255, 255, 255, 0.9);
}

.batch-info .el-button:hover {
  color: white;
}

.batch-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.batch-more {
  margin-left: auto;
}

.batch-more .el-button {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
}

.batch-more .el-button:hover {
  background: rgba(255, 255, 255, 0.3);
}

.action-icon {
  margin-right: 6px;
}
</style>
