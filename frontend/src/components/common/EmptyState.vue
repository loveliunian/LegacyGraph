<template>
  <div class="empty-state">
    <div class="empty-state__image">
      <slot name="image">
        <el-icon
          :size="80"
          color="#c0c4cc">
          <document />
        </el-icon>
      </slot>
    </div>
    <div class="empty-state__description">
      <slot name="description">
        <p>{{ description }}</p>
      </slot>
    </div>
    <div
      v-if="$slots.actions || actions"
      class="empty-state__actions">
      <slot name="actions">
        <el-button
          v-for="action in actions"
          :key="action.text"
          @click="action.onClick">
          {{ action.text }}
        </el-button>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Document } from '@element-plus/icons-vue'

interface EmptyAction {
  text: string
  onClick: () => void
}

interface Props {
  description?: string
  actions?: EmptyAction[]
}

withDefaults(defineProps<Props>(), {
  description: '暂无数据',
  actions: undefined,
})
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #909399;
}

.empty-state__image {
  margin-bottom: 16px;
}

.empty-state__description p {
  margin: 0;
  font-size: 14px;
  color: #909399;
}

.empty-state__actions {
  margin-top: 24px;
}
</style>
