<template>
  <div class="theme-settings-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>主题设置</span>
        </div>
      </template>

      <div class="settings-section">
        <h4 class="section-title">显示模式</h4>
        <div class="mode-options">
          <div
            v-for="option in modeOptions"
            :key="option.value"
            class="mode-option"
            :class="{ active: themeStore.theme === option.value }"
            @click="themeStore.setTheme(option.value as ThemeMode)"
          >
            <div
              class="mode-preview"
              :class="option.value">
              <div class="preview-header" />
              <div class="preview-body">
                <div class="preview-sidebar" />
                <div class="preview-content">
                  <div class="preview-card" />
                  <div class="preview-card" />
                </div>
              </div>
            </div>
            <span class="mode-label">{{ option.label }}</span>
            <el-icon
              v-if="themeStore.theme === option.value"
              class="check-icon">
              <Check />
            </el-icon>
          </div>
        </div>
      </div>

      <el-divider />

      <div class="settings-section">
        <h4 class="section-title">主题色配置</h4>
        <div class="color-picker-row">
          <div
            v-for="color in colorOptions"
            :key="color.key"
            class="color-item">
            <span class="color-label">{{ color.label }}</span>
            <el-color-picker
              v-model="color.value"
              :show-alpha="false"
              size="large"
              @change="updateThemeColors"
            />
          </div>
        </div>

        <div class="preset-colors">
          <span class="preset-label">预设配色</span>
          <div class="preset-list">
            <div
              v-for="(preset, index) in colorPresets"
              :key="index"
              class="preset-item"
              :title="preset.name"
              @click="applyColorPreset(preset)"
            >
              <div class="preset-colors">
                <div
                  v-for="(color, key) in preset.colors"
                  :key="key"
                  class="preset-color"
                  :style="{ background: color }"
                />
              </div>
              <span class="preset-name">{{ preset.name }}</span>
            </div>
          </div>
        </div>

        <el-button
          type="primary"
          plain
          style="margin-top: 20px"
          @click="resetColors">
          恢复默认配色
        </el-button>
      </div>

      <el-divider />

      <div class="settings-section">
        <h4 class="section-title">预览效果</h4>
        <div class="preview-section">
          <el-row :gutter="20">
            <el-col :span="12">
              <div class="preview-item">
                <el-button type="primary">主要按钮</el-button>
                <el-button type="success">成功按钮</el-button>
                <el-button type="warning">警告按钮</el-button>
                <el-button type="danger">危险按钮</el-button>
                <el-button type="info">信息按钮</el-button>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="preview-item">
                <el-tag type="primary">标签一</el-tag>
                <el-tag type="success">标签二</el-tag>
                <el-tag type="warning">标签三</el-tag>
                <el-tag type="danger">标签四</el-tag>
                <el-tag type="info">标签五</el-tag>
              </div>
            </el-col>
          </el-row>

          <el-row
            :gutter="20"
            style="margin-top: 20px">
            <el-col :span="12">
              <el-progress
                :percentage="80"
                color="#409eff" />
              <el-progress
                :percentage="100"
                status="success"
                color="#67c23a" />
              <el-progress
                :percentage="70"
                status="warning"
                color="#e6a23c" />
              <el-progress
                :percentage="50"
                status="exception"
                color="#f56c6c" />
            </el-col>
            <el-col :span="12">
              <div class="preview-item">
                <el-alert
                  title="成功提示"
                  type="success"
                  :closable="false" />
                <el-alert
                  title="信息提示"
                  type="info"
                  :closable="false"
                  style="margin-top: 10px" />
                <el-alert
                  title="警告提示"
                  type="warning"
                  :closable="false"
                  style="margin-top: 10px" />
                <el-alert
                  title="错误提示"
                  type="error"
                  :closable="false"
                  style="margin-top: 10px" />
              </div>
            </el-col>
          </el-row>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import type { ThemeMode } from '@/types'

const themeStore = useAppStore()

const modeOptions = [
  { value: 'light', label: '浅色模式' },
  { value: 'dark', label: '深色模式' },
  { value: 'auto', label: '跟随系统' }
]

const colorOptions = reactive([
  { key: 'primaryColor', label: '主色调', value: themeStore.themeConfig.primaryColor },
  { key: 'successColor', label: '成功色', value: themeStore.themeConfig.successColor },
  { key: 'warningColor', label: '警告色', value: themeStore.themeConfig.warningColor },
  { key: 'dangerColor', label: '危险色', value: themeStore.themeConfig.dangerColor },
  { key: 'infoColor', label: '信息色', value: themeStore.themeConfig.infoColor }
])

const colorPresets = [
  {
    name: '默认蓝',
    colors: {
      primaryColor: '#409eff',
      successColor: '#67c23a',
      warningColor: '#e6a23c',
      dangerColor: '#f56c6c',
      infoColor: '#909399'
    }
  },
  {
    name: '暗夜紫',
    colors: {
      primaryColor: '#722ed1',
      successColor: '#52c41a',
      warningColor: '#faad14',
      dangerColor: '#f5222d',
      infoColor: '#722ed1'
    }
  },
  {
    name: '森林绿',
    colors: {
      primaryColor: '#52c41a',
      successColor: '#389e0d',
      warningColor: '#faad14',
      dangerColor: '#f5222d',
      infoColor: '#52c41a'
    }
  },
  {
    name: '珊瑚红',
    colors: {
      primaryColor: '#eb2f96',
      successColor: '#52c41a',
      warningColor: '#faad14',
      dangerColor: '#cf1322',
      infoColor: '#eb2f96'
    }
  },
  {
    name: '科技蓝',
    colors: {
      primaryColor: '#1890ff',
      successColor: '#52c41a',
      warningColor: '#faad14',
      dangerColor: '#f5222d',
      infoColor: '#13c2c2'
    }
  }
]

function updateThemeColors() {
  themeStore.updateThemeConfig({
    primaryColor: colorOptions[0].value,
    successColor: colorOptions[1].value,
    warningColor: colorOptions[2].value,
    dangerColor: colorOptions[3].value,
    infoColor: colorOptions[4].value
  })
}

function applyColorPreset(preset: typeof colorPresets[0]) {
  themeStore.updateThemeConfig(preset.colors)
  colorOptions[0].value = preset.colors.primaryColor
  colorOptions[1].value = preset.colors.successColor
  colorOptions[2].value = preset.colors.warningColor
  colorOptions[3].value = preset.colors.dangerColor
  colorOptions[4].value = preset.colors.infoColor
  ElMessage.success(`已应用"${preset.name}"配色方案`)
}

function resetColors() {
  themeStore.resetThemeConfig()
  colorOptions[0].value = themeStore.themeConfig.primaryColor
  colorOptions[1].value = themeStore.themeConfig.successColor
  colorOptions[2].value = themeStore.themeConfig.warningColor
  colorOptions[3].value = themeStore.themeConfig.dangerColor
  colorOptions[4].value = themeStore.themeConfig.infoColor
  ElMessage.success('已恢复默认配色')
}

watch(() => themeStore.themeConfig, (newConfig) => {
  colorOptions[0].value = newConfig.primaryColor
  colorOptions[1].value = newConfig.successColor
  colorOptions[2].value = newConfig.warningColor
  colorOptions[3].value = newConfig.dangerColor
  colorOptions[4].value = newConfig.infoColor
}, { deep: true })
</script>

<style scoped>
.theme-settings-page {
  padding: 20px;
}

.card-header {
  font-size: 16px;
  font-weight: 600;
}

.settings-section {
  padding: 10px 0;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin: 0 0 20px 0;
}

.mode-options {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.mode-option {
  position: relative;
  cursor: pointer;
  padding: 8px;
  border-radius: 8px;
  border: 2px solid transparent;
  transition: all 0.3s;
}

.mode-option:hover {
  border-color: #dcdfe6;
}

.mode-option.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.mode-preview {
  width: 180px;
  height: 120px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid #dcdfe6;
  background: #f5f7fa;
}

.mode-preview.dark {
  background: #141414;
  border-color: #363637;
}

.mode-preview.auto {
  background: linear-gradient(90deg, #f5f7fa 50%, #141414 50%);
}

.preview-header {
  height: 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.mode-preview.dark .preview-header,
.mode-preview.auto .preview-header {
  background: #1f1f1f;
  border-bottom-color: #363637;
}

.preview-body {
  display: flex;
  height: calc(100% - 24px);
}

.preview-sidebar {
  width: 40px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.mode-preview.dark .preview-sidebar,
.mode-preview.auto .preview-sidebar {
  background: #1f1f1f;
  border-right-color: #363637;
}

.preview-content {
  flex: 1;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.preview-card {
  flex: 1;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.mode-preview.dark .preview-card,
.mode-preview.auto .preview-card {
  background: #1f1f1f;
}

.mode-label {
  display: block;
  text-align: center;
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}

.check-icon {
  position: absolute;
  top: 4px;
  right: 4px;
  color: #409eff;
  font-size: 18px;
}

.color-picker-row {
  display: flex;
  gap: 30px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}

.color-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.color-label {
  font-size: 14px;
  color: #606266;
  min-width: 60px;
}

.preset-colors {
  margin-top: 20px;
}

.preset-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 12px;
  display: block;
}

.preset-list {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.preset-item {
  cursor: pointer;
  padding: 8px;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  transition: all 0.3s;
}

.preset-item:hover {
  border-color: #409eff;
}

.preset-colors {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
}

.preset-color {
  width: 24px;
  height: 24px;
  border-radius: 4px;
}

.preset-name {
  font-size: 12px;
  color: #909399;
  text-align: center;
  display: block;
}

.preview-section {
  background: #fafafa;
  padding: 20px;
  border-radius: 8px;
}

:deep(.dark) .preview-section {
  background: #1d1e1f;
}

.preview-item {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
