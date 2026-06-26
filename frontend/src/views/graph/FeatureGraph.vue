<template>
  <div class="feature-graph">
    <div class="page-header">
      <h3>功能图谱</h3>
      <el-button type="primary" size="small" @click="exportReport">
        <el-icon><Download /></el-icon>
        导出功能清单
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="module-card">
          <template #header>
            <span>功能模块</span>
          </template>
          <div class="module-list">
            <div
              v-for="module in modules"
              :key="module.id"
              class="module-item"
              :class="{ active: selectedModule === module.id }"
              @click="selectModule(module.id)"
            >
              <div class="module-icon" :style="{ backgroundColor: module.color }">
                <el-icon><Grid /></el-icon>
              </div>
              <div class="module-info">
                <div class="module-name">{{ module.name }}</div>
                <div class="module-stats">
                  {{ module.featureCount }} 个功能点
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <span>模块: {{ getSelectedModuleName() }}</span>
            <el-button type="primary" size="small" @click="showFeatureMatrix">
              功能矩阵
            </el-button>
          </div>
          <div class="graph-container">
            <div class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Menu /></el-icon>
                <p>功能图谱可视化</p>
                <p class="placeholder-tip">展示功能模块、功能点、页面、按钮之间的层级关系</p>
                <div class="stats">
                  <el-tag type="primary">功能模块: {{ modules.length }}</el-tag>
                  <el-tag type="success">功能点: {{ totalFeatures }}</el-tag>
                  <el-tag type="info">页面数: {{ totalPages }}</el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="detail-card">
          <template #header>
            <span>功能详情</span>
          </template>
          <el-empty description="点击功能节点查看详情" />
        </el-card>

        <el-card class="test-card" style="margin-top: 16px;">
          <template #header>
            <span>测试覆盖率</span>
          </template>
          <div class="coverage-stats">
            <div class="coverage-item">
              <span class="coverage-label">整体覆盖率</span>
              <span class="coverage-value">68%</span>
            </div>
            <el-progress :percentage="68" status="warning" />
          </div>
          <div class="coverage-item">
            <span class="coverage-label">核心功能</span>
            <span class="coverage-value success">85%</span>
          </div>
          <div class="coverage-item">
            <span class="coverage-label">边缘场景</span>
            <span class="coverage-value danger">32%</span>
          </div>
          <el-button type="primary" size="small" style="width: 100%; margin-top: 16px;">
            生成补充测试用例
          </el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Grid, Menu } from '@element-plus/icons-vue'

const selectedModule = ref<string | null>(null)

const modules = ref([
  { id: '1', name: '用户模块', color: '#409eff', featureCount: 15 },
  { id: '2', name: '订单模块', color: '#67c23a', featureCount: 22 },
  { id: '3', name: '支付模块', color: '#e6a23c', featureCount: 12 },
  { id: '4', name: '商品模块', color: '#f56c6c', featureCount: 18 },
  { id: '5', name: '库存模块', color: '#909399', featureCount: 10 }
])

const totalFeatures = computed(() => 
  modules.value.reduce((sum, m) => sum + m.featureCount, 0)
)
const totalPages = 45

const getSelectedModuleName = () => {
  if (!selectedModule.value) return '全部'
  const module = modules.value.find(m => m.id === selectedModule.value)
  return module ? module.name : '全部'
}

const selectModule = (id: string) => {
  selectedModule.value = selectedModule.value === id ? null : id
}

const exportReport = () => {
  ElMessage.success('功能清单导出中...')
}

const showFeatureMatrix = () => {
  ElMessage.info('功能矩阵视图')
}
</script>

<style scoped>
.feature-graph {
  padding: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.module-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.module-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid transparent;
}

.module-item:hover {
  background: #f5f7fa;
}

.module-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.module-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.module-info {
  flex: 1;
}

.module-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.module-stats {
  font-size: 12px;
  color: #909399;
}

.graph-card {
  height: 100%;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.graph-container {
  min-height: 500px;
  background: #fafafa;
  border-radius: 4px;
}

.graph-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-content {
  text-align: center;
  color: #909399;
}

.placeholder-content p {
  margin: 12px 0 4px 0;
  font-size: 16px;
}

.placeholder-tip {
  font-size: 12px !important;
}

.stats {
  margin-top: 20px;
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.coverage-stats {
  margin-bottom: 20px;
}

.coverage-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.coverage-label {
  font-size: 13px;
  color: #606266;
}

.coverage-value {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.coverage-value.success {
  color: #67c23a;
}

.coverage-value.danger {
  color: #f56c6c;
}
</style>
