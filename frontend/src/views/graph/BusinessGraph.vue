<template>
  <div class="business-graph">
    <div class="page-header">
      <h3>业务图谱</h3>
      <el-button type="primary" size="small" @click="toggleAiView">
        <el-icon><View /></el-icon>
        {{ showAiView ? '显示原始' : 'AI归纳视图' }}
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="5">
        <el-card class="domain-card">
          <template #header>
            <span>业务领域</span>
          </template>
          <el-tree
            :data="domainTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            default-expand-all
            @node-click="handleDomainClick"
          >
            <template #default="{ node, data }">
              <span class="custom-tree-node">
                <span>{{ node.label }}</span>
                <el-tag v-if="data.confidence" size="small" :type="data.confidence >= 0.8 ? 'success' : data.confidence >= 0.6 ? 'warning' : 'danger'" style="margin-left: 8px;">
                  {{ (data.confidence * 100).toFixed(0) }}%
                </el-tag>
              </span>
            </template>
          </el-tree>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <span>当前视图: {{ showAiView ? 'AI归纳' : '原始数据' }}</span>
            <el-button-group>
              <el-button size="small" @click="zoomIn">放大</el-button>
              <el-button size="small" @click="zoomOut">缩小</el-button>
              <el-button size="small" @click="fitView">适应</el-button>
            </el-button-group>
          </div>
          <div class="graph-container">
            <div class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Share /></el-icon>
                <p>业务图谱可视化</p>
                <p class="placeholder-tip">展示业务领域、流程、规则之间的关联关系</p>
                <div class="stats">
                  <el-tag type="success">业务领域: {{ businessStats.domains }}</el-tag>
                  <el-tag type="primary">业务流程: {{ businessStats.processes }}</el-tag>
                  <el-tag type="warning">业务规则: {{ businessStats.rules }}</el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="5">
        <el-card class="detail-card">
          <template #header>
            <span>节点详情</span>
          </template>
          <el-empty description="点击节点查看详情" />
        </el-card>

        <el-card class="ai-card" style="margin-top: 16px;">
          <template #header>
            <div class="card-header">
              <span><el-icon><MagicStick /></el-icon> AI 分析</span>
            </div>
          </template>
          <div class="ai-analysis">
            <p>基于业务图谱，AI 识别出以下核心洞察：</p>
            <ol>
              <li>订单管理是核心业务领域，关联了 12 个业务流程</li>
              <li>支付流程存在 3 个潜在的业务规则冲突点</li>
              <li>库存管理与订单管理的数据耦合度较高，建议关注</li>
            </ol>
            <el-button type="primary" size="small" style="width: 100%; margin-top: 12px;">
              查看完整分析报告
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { View, Share, MagicStick } from '@element-plus/icons-vue'

const showAiView = ref(false)

const domainTree = ref([
  {
    id: '1',
    name: '订单管理',
    confidence: 0.95,
    children: [
      { id: '1-1', name: '创建订单', confidence: 0.92 },
      { id: '1-2', name: '支付流程', confidence: 0.88 },
      { id: '1-3', name: '订单状态流转', confidence: 0.90 }
    ]
  },
  {
    id: '2',
    name: '库存管理',
    confidence: 0.90,
    children: [
      { id: '2-1', name: '库存扣减', confidence: 0.85 },
      { id: '2-2', name: '库存预警', confidence: 0.82 }
    ]
  },
  {
    id: '3',
    name: '用户管理',
    confidence: 0.88,
    children: [
      { id: '3-1', name: '用户注册', confidence: 0.85 },
      { id: '3-2', name: '实名认证', confidence: 0.80 }
    ]
  }
])

const businessStats = {
  domains: 5,
  processes: 18,
  rules: 42
}

const handleDomainClick = (data: any) => {
  ElMessage.info(`选中: ${data.name}`)
}

const toggleAiView = () => {
  showAiView.value = !showAiView.value
  ElMessage.success(showAiView.value ? '已切换到AI归纳视图' : '已切换到原始视图')
}

const zoomIn = () => ElMessage.info('放大')
const zoomOut = () => ElMessage.info('缩小')
const fitView = () => ElMessage.info('适应视图')
</script>

<style scoped>
.business-graph {
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

.custom-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 1;
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

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-analysis {
  font-size: 13px;
  line-height: 1.8;
  color: #606266;
}

.ai-analysis ol {
  margin: 12px 0 0 0;
  padding-left: 20px;
}

.ai-analysis li {
  margin-bottom: 8px;
}
</style>
