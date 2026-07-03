<template>
  <div
    class="skeleton-container"
    :class="{ animated: animated }">
    <div
      v-if="type === 'table'"
      class="skeleton-table">
      <div class="skeleton-table-header">
        <div
          v-for="i in cols"
          :key="i"
          class="skeleton-col"
          :style="{ width: colWidth + '%' }"
        >
          <div class="skeleton-item skeleton-title" />
        </div>
      </div>
      <div class="skeleton-table-body">
        <div
          v-for="i in rows"
          :key="i"
          class="skeleton-row">
          <div
            v-for="j in cols"
            :key="j"
            class="skeleton-col"
            :style="{ width: colWidth + '%' }"
          >
            <div class="skeleton-item skeleton-text" />
          </div>
        </div>
      </div>
    </div>

    <div
      v-else-if="type === 'card'"
      class="skeleton-card">
      <div class="skeleton-card-header">
        <div class="skeleton-avatar-wrap">
          <div class="skeleton-item skeleton-avatar" />
        </div>
        <div class="skeleton-title-wrap">
          <div class="skeleton-item skeleton-title" />
          <div class="skeleton-item skeleton-subtitle" />
        </div>
      </div>
      <div class="skeleton-card-body">
        <div class="skeleton-item skeleton-paragraph" />
        <div
          class="skeleton-item skeleton-paragraph"
          style="width: 70%" />
        <div
          class="skeleton-item skeleton-paragraph"
          style="width: 85%" />
      </div>
      <div class="skeleton-card-footer">
        <div class="skeleton-item skeleton-button" />
        <div class="skeleton-item skeleton-button" />
      </div>
    </div>

    <div
      v-else-if="type === 'list'"
      class="skeleton-list">
      <div
        v-for="i in rows"
        :key="i"
        class="skeleton-list-item">
        <div class="skeleton-avatar-wrap">
          <div class="skeleton-item skeleton-avatar" />
        </div>
        <div class="skeleton-list-content">
          <div class="skeleton-item skeleton-title" />
          <div class="skeleton-item skeleton-subtitle" />
          <div class="skeleton-item skeleton-paragraph" />
        </div>
      </div>
    </div>

    <div
      v-else-if="type === 'detail'"
      class="skeleton-detail">
      <div class="skeleton-detail-header">
        <div class="skeleton-item skeleton-title large" />
        <div class="skeleton-item skeleton-subtitle" />
      </div>
      <div class="skeleton-detail-body">
        <div class="skeleton-detail-row">
          <div class="skeleton-detail-label">
            <div class="skeleton-item skeleton-text" />
          </div>
          <div class="skeleton-detail-value">
            <div
              class="skeleton-item skeleton-text"
              style="width: 60%" />
          </div>
        </div>
        <div
          v-for="i in 4"
          :key="i"
          class="skeleton-detail-row">
          <div class="skeleton-detail-label">
            <div class="skeleton-item skeleton-text" />
          </div>
          <div class="skeleton-detail-value">
            <div
              class="skeleton-item skeleton-text"
              style="width: 70%" />
          </div>
        </div>
      </div>
    </div>

    <div
      v-else-if="type === 'form'"
      class="skeleton-form">
      <div
        v-for="i in rows"
        :key="i"
        class="skeleton-form-row">
        <div class="skeleton-form-label">
          <div class="skeleton-item skeleton-text" />
        </div>
        <div class="skeleton-form-input">
          <div class="skeleton-item skeleton-input" />
        </div>
      </div>
    </div>

    <div
      v-else-if="type === 'graph'"
      class="skeleton-graph">
      <div class="skeleton-toolbar">
        <div class="skeleton-toolbar-left">
          <div
            v-for="i in 4"
            :key="i"
            class="skeleton-item skeleton-button" />
        </div>
        <div class="skeleton-toolbar-right">
          <div
            class="skeleton-item skeleton-input"
            style="width: 150px" />
          <div class="skeleton-item skeleton-button" />
        </div>
      </div>
      <div class="skeleton-graph-area">
        <svg
          viewBox="0 0 100 100"
          class="skeleton-svg">
          <circle
            cx="20"
            cy="30"
            r="8"
            fill="#e5e7eb" />
          <circle
            cx="50"
            cy="20"
            r="10"
            fill="#e5e7eb" />
          <circle
            cx="80"
            cy="35"
            r="9"
            fill="#e5e7eb" />
          <circle
            cx="35"
            cy="60"
            r="7"
            fill="#e5e7eb" />
          <circle
            cx="65"
            cy="55"
            r="8"
            fill="#e5e7eb" />
          <circle
            cx="90"
            cy="70"
            r="6"
            fill="#e5e7eb" />
          <line
            x1="20"
            y1="30"
            x2="35"
            y2="60"
            stroke="#e5e7eb"
            stroke-width="2" />
          <line
            x1="50"
            y1="20"
            x2="65"
            y2="55"
            stroke="#e5e7eb"
            stroke-width="2" />
          <line
            x1="65"
            y1="55"
            x2="80"
            y2="35"
            stroke="#e5e7eb"
            stroke-width="2" />
          <line
            x1="65"
            y1="55"
            x2="90"
            y2="70"
            stroke="#e5e7eb"
            stroke-width="2" />
          <line
            x1="35"
            y1="60"
            x2="65"
            y2="55"
            stroke="#e5e7eb"
            stroke-width="2" />
        </svg>
      </div>
      <div class="skeleton-legend">
        <div
          v-for="i in 3"
          :key="i"
          class="skeleton-legend-item">
          <div class="skeleton-item skeleton-dot" />
          <div class="skeleton-item skeleton-text" />
        </div>
      </div>
    </div>

    <div
      v-else-if="type === 'avatar'"
      class="skeleton-avatar-container">
      <div
        class="skeleton-item skeleton-avatar"
        :class="{ circle: avatarShape === 'circle' }"
        :style="{ width: avatarSize + 'px', height: avatarSize + 'px' }"
      />
    </div>

    <div
      v-else
      class="skeleton-custom">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  type?: 'table' | 'card' | 'list' | 'detail' | 'form' | 'graph' | 'avatar' | 'custom'
  rows?: number
  cols?: number
  animated?: boolean
  avatarSize?: number
  avatarShape?: 'circle' | 'square'
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  type: 'card',
  rows: 3,
  cols: 4,
  animated: true,
  avatarSize: 48,
  avatarShape: 'circle',
  loading: true
})

const colWidth = computed(() => {
  if (props.cols <= 0) return 25
  return Math.floor(100 / props.cols)
})
</script>

<style scoped>
.skeleton-container {
  width: 100%;
}

.skeleton-item {
  background: linear-gradient(90deg, #f0f2f5 25%, #f5f7fa 50%, #f0f2f5 75%);
  background-size: 200% 100%;
  border-radius: 4px;
}

.animated .skeleton-item {
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

.skeleton-text {
  height: 16px;
  width: 100%;
}

.skeleton-title {
  height: 20px;
  width: 60%;
}

.skeleton-subtitle {
  height: 14px;
  width: 40%;
  margin-top: 8px;
}

.skeleton-paragraph {
  height: 16px;
  margin-top: 12px;
  width: 100%;
}

.skeleton-avatar {
  width: 48px;
  height: 48px;
  border-radius: 4px;
}

.skeleton-avatar.circle {
  border-radius: 50%;
}

.skeleton-button {
  width: 80px;
  height: 32px;
  border-radius: 6px;
}

.skeleton-input {
  height: 36px;
  border-radius: 4px;
}

.skeleton-table {
  width: 100%;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.skeleton-table-header {
  display: flex;
  padding: 12px 16px;
  background: #fafafa;
  border-bottom: 1px solid #ebeef5;
}

.skeleton-table-body {
  padding: 0 16px;
}

.skeleton-row {
  display: flex;
  padding: 12px 0;
  border-bottom: 1px solid #f5f7fa;
}

.skeleton-row:last-child {
  border-bottom: none;
}

.skeleton-col {
  padding: 0 8px;
}

.skeleton-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
}

.skeleton-card-header {
  display: flex;
  align-items: center;
  padding: 16px;
  gap: 12px;
}

.skeleton-card-body {
  padding: 16px;
}

.skeleton-card-footer {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-top: 1px solid #ebeef5;
}

.skeleton-list {
  width: 100%;
}

.skeleton-list-item {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #f5f7fa;
}

.skeleton-list-item:last-child {
  border-bottom: none;
}

.skeleton-list-content {
  flex: 1;
}

.skeleton-detail {
  width: 100%;
}

.skeleton-detail-header {
  padding: 20px;
  border-bottom: 1px solid #ebeef5;
}

.skeleton-detail-header .skeleton-title.large {
  height: 24px;
  width: 40%;
}

.skeleton-detail-body {
  padding: 20px;
}

.skeleton-detail-row {
  display: flex;
  margin-bottom: 16px;
}

.skeleton-detail-label {
  width: 120px;
  flex-shrink: 0;
}

.skeleton-detail-value {
  flex: 1;
}

.skeleton-form {
  width: 100%;
  padding: 20px;
}

.skeleton-form-row {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
  gap: 12px;
}

.skeleton-form-label {
  width: 100px;
  flex-shrink: 0;
}

.skeleton-form-input {
  flex: 1;
}

.skeleton-graph {
  width: 100%;
  height: 400px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
}

.skeleton-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  background: #fafafa;
}

.skeleton-toolbar-left,
.skeleton-toolbar-right {
  display: flex;
  gap: 8px;
  align-items: center;
}

.skeleton-graph-area {
  padding: 20px;
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.skeleton-svg {
  width: 100%;
  height: 100%;
}

.skeleton-legend {
  display: flex;
  gap: 24px;
  padding: 16px;
  border-top: 1px solid #ebeef5;
}

.skeleton-legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.skeleton-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.skeleton-avatar-container {
  display: inline-block;
}
</style>
