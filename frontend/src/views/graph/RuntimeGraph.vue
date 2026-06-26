<template>
  <div class="runtime-graph">
    <div class="page-header">
      <h3>运行链路图谱</h3>
      <div class="header-actions">
        <el-select v-model="selectedEnv" placeholder="选择环境" size="small" style="width: 120px;">
          <el-option label="生产环境" value="prod" />
          <el-option label="测试环境" value="test" />
          <el-option label="开发环境" value="dev" />
        </el-select>
        <el-button type="primary" size="small" @click="refreshTraces">
          <el-icon><Refresh /></el-icon>
          刷新链路
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="trace-card">
          <template #header>
            <span>最近请求链路</span>
          </template>
          <div class="trace-list">
            <div
              v-for="trace in traces"
              :key="trace.id"
              class="trace-item"
              :class="{ active: selectedTrace === trace.id }"
              @click="selectTrace(trace.id)"
            >
              <div class="trace-header">
                <el-tag size="small" :type="trace.status === 'success' ? 'success' : 'danger'">
                  {{ trace.status === 'success' ? '成功' : '失败' }}
                </el-tag>
                <span class="trace-duration">{{ trace.duration }}ms</span>
              </div>
              <div class="trace-api">{{ trace.api }}</div>
              <div class="trace-meta">
                <span>{{ trace.time }}</span>
                <span>{{ trace.serviceCount }} 个服务</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <el-button-group>
              <el-button size="small" :type="viewType === 'topology' ? 'primary' : ''" @click="viewType = 'topology'">
                服务拓扑
              </el-button>
              <el-button size="small" :type="viewType === 'timeline' ? 'primary' : ''" @click="viewType = 'timeline'">
                调用时序
              </el-button>
            </el-button-group>
            <el-button type="primary" size="small" @click="showErrorAnalysis">
              错误分析
            </el-button>
          </div>
          <div class="graph-container">
            <div class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Connection /></el-icon>
                <p>运行链路可视化</p>
                <p class="placeholder-tip">展示服务间的调用关系、时序和错误分布</p>
                <div class="stats">
                  <el-tag type="primary">服务数: {{ services.length }}</el-tag>
                  <el-tag type="success">实例数: {{ instanceCount }}</el-tag>
                  <el-tag type="danger">错误数: {{ errorCount }}</el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="service-card">
          <template #header>
            <span>服务详情</span>
          </template>
          <div class="service-list">
            <div
              v-for="service in services"
              :key="service.id"
              class="service-item"
              :class="{ active: selectedService === service.id }"
              @click="selectService(service.id)"
            >
              <div class="service-header">
                <span class="service-name">{{ service.name }}</span>
                <el-tag size="small" :type="service.health === 'healthy' ? 'success' : 'danger'">
                  {{ service.health === 'healthy' ? '健康' : '异常' }}
                </el-tag>
              </div>
              <div class="service-stats">
                <span>QPS: {{ service.qps }}</span>
                <span>P99: {{ service.p99 }}ms</span>
                <span>错误率: {{ service.errorRate }}%</span>
              </div>
            </div>
          </div>
        </el-card>

        <el-card class="slow-card" style="margin-top: 16px;">
          <template #header>
            <span>慢请求 Top 5</span>
          </template>
          <div class="slow-list">
            <div class="slow-item" v-for="slow in slowRequests" :key="slow.id">
              <div class="slow-api">{{ slow.api }}</div>
              <div class="slow-info">
                <span class="slow-duration">{{ slow.avgDuration }}ms</span>
                <span class="slow-count">{{ slow.count }} 次</span>
              </div>
              <el-progress :percentage="slow.warningLevel * 20" :status="slow.warningLevel >= 4 ? 'exception' : 'warning'" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Connection } from '@element-plus/icons-vue'

const selectedEnv = ref('prod')
const viewType = ref('topology')
const selectedTrace = ref<string | null>(null)
const selectedService = ref<string | null>(null)

const traces = ref([
  { id: '1', status: 'success', duration: 245, api: 'POST /api/order/create', time: '10:32:15', serviceCount: 4 },
  { id: '2', status: 'success', duration: 186, api: 'GET /api/user/profile', time: '10:32:10', serviceCount: 2 },
  { id: '3', status: 'error', duration: 523, api: 'POST /api/payment/notify', time: '10:31:58', serviceCount: 3 },
  { id: '4', status: 'success', duration: 124, api: 'GET /api/product/list', time: '10:31:45', serviceCount: 2 },
  { id: '5', status: 'error', duration: 892, api: 'PUT /api/inventory/deduct', time: '10:31:30', serviceCount: 3 }
])

const services = ref([
  { id: '1', name: 'order-service', health: 'healthy', qps: 156, p99: 245, errorRate: 0.2 },
  { id: '2', name: 'user-service', health: 'healthy', qps: 234, p99: 120, errorRate: 0.1 },
  { id: '3', name: 'payment-service', health: 'error', qps: 89, p99: 523, errorRate: 5.6 },
  { id: '4', name: 'product-service', health: 'healthy', qps: 312, p99: 98, errorRate: 0.05 },
  { id: '5', name: 'inventory-service', health: 'healthy', qps: 178, p99: 156, errorRate: 0.3 }
])

const slowRequests = ref([
  { id: '1', api: 'POST /api/payment/notify', avgDuration: 892, count: 23, warningLevel: 5 },
  { id: '2', api: 'PUT /api/inventory/deduct', avgDuration: 523, count: 45, warningLevel: 4 },
  { id: '3', api: 'POST /api/order/create', avgDuration: 245, count: 128, warningLevel: 2 },
  { id: '4', api: 'GET /api/user/profile', avgDuration: 186, count: 256, warningLevel: 1 },
  { id: '5', api: 'GET /api/product/list', avgDuration: 124, count: 512, warningLevel: 1 }
])

const instanceCount = 18
const errorCount = 23

const selectTrace = (id: string) => {
  selectedTrace.value = selectedTrace.value === id ? null : id
}

const selectService = (id: string) => {
  selectedService.value = selectedService.value === id ? null : id
}

const refreshTraces = () => {
  ElMessage.success('链路已刷新')
}

const showErrorAnalysis = () => {
  ElMessage.info('错误分析功能')
}
</script>

<style scoped>
.runtime-graph {
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

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.trace-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 500px;
  overflow-y: auto;
}

.trace-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid #ebeef5;
}

.trace-item:hover {
  background: #f5f7fa;
}

.trace-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.trace-duration {
  font-size: 12px;
  font-weight: 500;
  color: #303133;
}

.trace-api {
  font-size: 13px;
  color: #303133;
  font-family: monospace;
  margin-bottom: 4px;
}

.trace-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
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

.service-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.service-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid #ebeef5;
}

.service-item:hover {
  background: #f5f7fa;
}

.service-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.service-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.service-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  font-family: monospace;
}

.service-stats {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #909399;
}

.slow-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.slow-item {
  padding-bottom: 12px;
  border-bottom: 1px dashed #ebeef5;
}

.slow-api {
  font-size: 12px;
  color: #303133;
  font-family: monospace;
  margin-bottom: 4px;
}

.slow-info {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.slow-duration {
  font-size: 12px;
  font-weight: 500;
  color: #f56c6c;
}

.slow-count {
  font-size: 11px;
  color: #909399;
}
</style>
