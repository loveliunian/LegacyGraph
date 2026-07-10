<template>
  <div class="error-page">
    <div class="error-bg-grid"></div>
    <div class="error-content">
      <div class="error-code">404</div>
      <h1 class="error-title">{{ $t('common.notFound') }}</h1>
      <p class="error-desc">抱歉，您访问的页面不存在</p>
      <div class="error-actions">
        <el-button
          type="primary"
          size="large"
          @click="goHome">
          <el-icon><House /></el-icon>
          {{ $t('common.backToHome') }}
        </el-button>
        <el-button
          size="large"
          @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          {{ $t('common.back') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { House, ArrowLeft } from '@element-plus/icons-vue'

const router = useRouter()

function goHome() {
  router.push('/dashboard')
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    goHome()
  }
}
</script>

<style scoped>
.error-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page);
  position: relative;
  overflow: hidden;
}

.error-bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--el-border-color-lighter) 1px, transparent 1px),
    linear-gradient(90deg, var(--el-border-color-lighter) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 60% 50% at 50% 50%, black 30%, transparent 70%);
  -webkit-mask-image: radial-gradient(ellipse 60% 50% at 50% 50%, black 30%, transparent 70%);
  opacity: 0.6;
}

.error-content {
  text-align: center;
  z-index: 1;
  position: relative;
  animation: fadeInUp 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.error-code {
  font-family: var(--font-display);
  font-size: 140px;
  font-weight: 400;
  line-height: 1;
  margin-bottom: 24px;
  background: linear-gradient(135deg, var(--el-color-primary) 0%, var(--el-color-primary-dark-2) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: -0.04em;
}

.error-title {
  font-family: var(--font-body);
  font-size: 28px;
  margin: 0 0 12px 0;
  font-weight: 600;
  color: var(--el-text-color-primary);
  letter-spacing: -0.01em;
}

.error-desc {
  font-size: 15px;
  color: var(--el-text-color-secondary);
  margin-bottom: 40px;
}

.error-actions {
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
}

.error-actions .el-button {
  padding: 12px 28px;
  font-size: 15px;
  border-radius: 10px;
}

@media (max-width: 768px) {
  .error-code {
    font-size: 90px;
  }

  .error-title {
    font-size: 22px;
  }

  .error-desc {
    font-size: 14px;
  }
}
</style>
