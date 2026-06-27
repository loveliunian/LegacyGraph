<template>
  <div class="validation-report">
    <div class="page-header">
      <h3>验证报告</h3>
      <div class="header-actions">
        <el-select v-model="selectedVersion" placeholder="选择图谱版本" size="small" style="width: 220px;">
          <el-option label="当前版本 (v1.0)" value="v1.0" />
          <el-option label="历史版本 (v0.9)" value="v0.9" />
        </el-select>
        <el-button type="primary" size="small" @click="exportReport">
          <el-icon><Download /></el-icon>
          导出报告
        </el-button>
      </div>
    </div>

    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-icon primary">
              <el-icon><Connection /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ reportData?.totalNodes || 0 }}</div>
              <div class="stat-label">总节点数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-icon success">
              <el-icon><Link /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ reportData?.totalEdges || 0 }}</div>
              <div class="stat-label">总关系数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-icon warning">
              <el-icon><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ reportData?.validationPassRate || 0 }}%</div>
              <div class="stat-label">验证通过率</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-icon danger">
              <el-icon><Warning /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ reportData?.riskLevel || '中等' }}</div>
              <div class="stat-label">迁移风险等级</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="content-row">
      <el-col :span="12">
        <el-card class="section-card">
          <template #header>
            <span>节点类型分布</span>
          </template>
          <div class="chart-placeholder">
            <div class="chart-content">
              <div class="pie-chart">
                <div class="pie-item" v-for="item in nodeTypeDistribution" :key="item.type">
                  <div class="pie-color" :style="{ backgroundColor: item.color }"></div>
                  <span class="pie-label">{{ item.label }}</span>
                  <span class="pie-value">{{ item.count }} ({{ item.percentage }}%)</span>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="section-card">
          <template #header>
            <span>置信度分布</span>
          </template>
          <div class="chart-placeholder">
            <div class="confidence-chart">
              <div class="confidence-item" v-for="item in confidenceDistribution" :key="item.range">
                <div class="confidence-label">{{ item.range }}</div>
                <div class="confidence-bar">
                  <div class="confidence-fill" :style="{ width: item.percentage + '%', backgroundColor: item.color }"></div>
                </div>
                <div class="confidence-value">{{ item.count }}个</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="content-row">
      <el-col :span="16">
        <el-card class="section-card">
          <template #header>
            <div class="card-header">
              <span>高风险项列表</span>
              <el-tag type="danger">{{ risks.length }}个风险点</el-tag>
            </div>
          </template>
          <div class="risk-list">
            <div class="risk-item" v-for="risk in risks" :key="risk.id">
              <div class="risk-header">
                <el-tag :type="risk.severity === 'HIGH' ? 'danger' : risk.severity === 'MEDIUM' ? 'warning' : 'info'" size="small">
                  {{ risk.severity === 'HIGH' ? '高风险' : risk.severity === 'MEDIUM' ? '中风险' : '低风险' }}
                </el-tag>
                <span class="risk-type">{{ getRiskTypeText(risk.riskType) }}</span>
              </div>
              <div class="risk-name">{{ risk.riskName }}</div>
              <div class="risk-desc">{{ risk.description }}</div>
              <div class="risk-suggestion" v-if="risk.suggestion">
                <el-icon><Opportunity /></el-icon>
                <span>建议: {{ risk.suggestion }}</span>
              </div>
              <div class="risk-meta">
                <span>影响节点: {{ risk.impactedCount }}</span>
                <span>证据数: {{ risk.evidenceCount }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card class="section-card">
          <template #header>
            <span>测试覆盖统计</span>
          </template>
          <div class="coverage-stats">
            <div class="coverage-item">
              <div class="coverage-label">API接口覆盖率</div>
              <div class="coverage-bar">
                <el-progress :percentage="78" :stroke-width="20" status="warning" />
              </div>
              <div class="coverage-value">78%</div>
            </div>
            <div class="coverage-item">
              <div class="coverage-label">业务流程覆盖率</div>
              <div class="coverage-bar">
                <el-progress :percentage="65" :stroke-width="20" status="warning" />
              </div>
              <div class="coverage-value">65%</div>
            </div>
            <div class="coverage-item">
              <div class="coverage-label">数据库表覆盖率</div>
              <div class="coverage-bar">
                <el-progress :percentage="82" :stroke-width="20" status="success" />
              </div>
              <div class="coverage-value">82%</div>
            </div>
            <div class="coverage-item">
              <div class="coverage-label">代码行覆盖率</div>
              <div class="coverage-bar">
                <el-progress :percentage="45" :stroke-width="20" status="exception" />
              </div>
              <div class="coverage-value">45%</div>
            </div>
          </div>
        </el-card>

        <el-card class="section-card" style="margin-top: 16px;">
          <template #header>
            <span>关键指标摘要</span>
          </template>
          <div class="summary-list">
            <div class="summary-item">
              <span class="summary-label">已验证节点</span>
              <span class="summary-value success">{{ reportData?.validatedNodes || 0 }}</span>
            </div>
            <div class="summary-item">
              <span class="summary-label">测试覆盖节点</span>
              <span class="summary-value primary">{{ reportData?.testCoveredNodes || 0 }}</span>
            </div>
            <div class="summary-item">
              <span class="summary-label">待审核项</span>
              <span class="summary-value warning">{{ reportData?.pendingItems || 0 }}</span>
            </div>
            <div class="summary-item">
              <span class="summary-label">未覆盖关键路径</span>
              <span class="summary-value danger">3</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="section-card">
      <template #header>
        <span>AI 分析建议</span>
      </template>
      <div class="ai-suggestions">
        <div class="suggestion-item">
          <el-icon><Star /></el-icon>
          <div class="suggestion-content">
            <div class="suggestion-title">优先完成高置信度节点的测试用例</div>
            <div class="suggestion-desc">当前有 45 个置信度 >= 90% 的节点缺少测试用例，建议优先生成这些节点的测试用例，可快速提升整体验证率。</div>
          </div>
        </div>
        <div class="suggestion-item">
          <el-icon><Star /></el-icon>
          <div class="suggestion-content">
            <div class="suggestion-title">重点关注订单-库存耦合问题</div>
            <div class="suggestion-desc">分析发现订单模块与库存模块存在强数据耦合，涉及 8 张数据表和 12 个服务接口，建议在迁移前进行解耦设计。</div>
          </div>
        </div>
        <div class="suggestion-item">
          <el-icon><Star /></el-icon>
          <div class="suggestion-content">
            <div class="suggestion-title">补充文档事实来源</div>
            <div class="suggestion-desc">当前有 23 个业务节点仅来源于代码分析，缺少文档证据支持，建议补充相关业务文档以提高置信度。</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Connection, Link, CircleCheck, Warning, Opportunity, Star } from '@element-plus/icons-vue'

const selectedVersion = ref('v1.0')

const reportData = ref({
  totalNodes: 1256,
  totalEdges: 3428,
  validatedNodes: 892,
  validatedEdges: 2156,
  testCoveredNodes: 985,
  testCoveredEdges: 2568,
  validationPassRate: 78,
  pendingItems: 364,
  riskLevel: '中等'
})

const nodeTypeDistribution = ref([
  { type: 'CONTROLLER', label: 'Controller', count: 45, percentage: 14, color: '#409eff' },
  { type: 'SERVICE', label: 'Service', count: 78, percentage: 25, color: '#67c23a' },
  { type: 'MAPPER', label: 'Mapper', count: 56, percentage: 18, color: '#e6a23c' },
  { type: 'TABLE', label: '数据表', count: 42, percentage: 13, color: '#f56c6c' },
  { type: 'API', label: 'API接口', count: 89, percentage: 28, color: '#909399' },
  { type: 'OTHER', label: '其他', count: 15, percentage: 5, color: '#c0c4cc' }
])

const confidenceDistribution = ref([
  { range: '90-100%', count: 456, percentage: 36, color: '#67c23a' },
  { range: '80-90%', count: 389, percentage: 31, color: '#85ce61' },
  { range: '70-80%', count: 256, percentage: 20, color: '#e6a23c' },
  { range: '60-70%', count: 123, percentage: 10, color: '#f56c6c' },
  { range: '<60%', count: 32, percentage: 3, color: '#ff4d4f' }
])

const risks = ref([
  {
    id: '1',
    severity: 'HIGH',
    riskType: 'COMPLEX_CALL_CHAIN',
    riskName: '订单创建流程存在复杂调用链',
    description: '订单创建过程涉及12个服务接口的同步调用，包括库存、支付、用户、通知等多个模块，任意一个环节失败都可能导致数据不一致。',
    suggestion: '建议引入分布式事务方案，如Seata或本地消息表+最终一致性。',
    impactedCount: 12,
    evidenceCount: 8
  },
  {
    id: '2',
    severity: 'HIGH',
    riskType: 'TABLE_COUPLING',
    riskName: 't_order表与多表存在耦合',
    description: '订单主表被8个服务直接读写，跨模块数据访问频繁，存在数据安全和性能隐患。',
    suggestion: '建议拆分订单表，按领域划分建立独立的订单域服务。',
    impactedCount: 8,
    evidenceCount: 15
  },
  {
    id: '3',
    severity: 'MEDIUM',
    riskType: 'MISSING_DOC',
    riskName: '支付回调流程缺少完整文档',
    description: '支付回调的业务规则仅在代码中体现，缺少架构设计文档和时序图，新人理解成本高。',
    suggestion: '补充支付模块的架构设计文档，包含时序图和状态流转说明。',
    impactedCount: 5,
    evidenceCount: 2
  },
  {
    id: '4',
    severity: 'MEDIUM',
    riskType: 'UNCLEAR_LINEAGE',
    riskName: '用户数据血缘不完整',
    description: '用户信息在多个模块之间流转，但数据血缘图谱存在断点，部分中间状态的处理逻辑不明确。',
    suggestion: '完善用户数据的全链路追踪，补充中间节点的证据来源。',
    impactedCount: 6,
    evidenceCount: 3
  },
  {
    id: '5',
    severity: 'LOW',
    riskType: 'EXTERNAL_DEPENDENCY',
    riskName: '第三方短信服务直接耦合',
    description: '多个模块直接调用短信服务接口，缺少统一的消息发送网关。',
    suggestion: '引入统一的消息发送服务，支持多种渠道扩展。',
    impactedCount: 4,
    evidenceCount: 6
  }
])

const getRiskTypeText = (type: string) => {
  const map: Record<string, string> = {
    COMPLEX_CALL_CHAIN: '复杂调用链',
    TABLE_COUPLING: '表耦合',
    MISSING_DOC: '文档缺失',
    MISSING_TEST: '测试缺失',
    EXTERNAL_DEPENDENCY: '外部依赖',
    LOW_CONFIDENCE: '低置信度',
    UNCLEAR_LINEAGE: '血缘不清',
    UNCLEAR_PERMISSION: '权限不明'
  }
  return map[type] || type
}

const exportReport = () => {
  ElMessage.success('报告导出中...')
}
</script>

<style scoped>
.validation-report {
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

.stats-row,
.content-row {
  margin-bottom: 24px;
}

.stat-card {
  height: 100%;
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 24px;
}

.stat-icon.primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.stat-icon.success {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.stat-icon.warning {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.stat-icon.danger {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 32px;
  font-weight: 600;
  color: #303133;
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.section-card {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-placeholder {
  padding: 20px 0;
}

.pie-chart {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pie-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pie-color {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  flex-shrink: 0;
}

.pie-label {
  width: 100px;
  font-size: 13px;
  color: #606266;
}

.pie-value {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.confidence-chart {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.confidence-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.confidence-label {
  width: 80px;
  font-size: 13px;
  color: #606266;
}

.confidence-bar {
  flex: 1;
  height: 20px;
  background: #f0f2f5;
  border-radius: 10px;
  overflow: hidden;
}

.confidence-fill {
  height: 100%;
  border-radius: 10px;
  transition: width 0.3s;
}

.confidence-value {
  width: 60px;
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  text-align: right;
}

.risk-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.risk-item {
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border-left: 4px solid;
  border-color: #f56c6c;
}

.risk-item:nth-child(2) {
  border-color: #f56c6c;
}

.risk-item:nth-child(3) {
  border-color: #e6a23c;
}

.risk-item:nth-child(4) {
  border-color: #e6a23c;
}

.risk-item:nth-child(5) {
  border-color: #909399;
}

.risk-header {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.risk-type {
  font-size: 12px;
  color: #909399;
}

.risk-name {
  font-size: 15px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
}

.risk-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  margin-bottom: 8px;
}

.risk-suggestion {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 13px;
  color: #67c23a;
  line-height: 1.6;
  padding: 8px 12px;
  background: #f0f9eb;
  border-radius: 4px;
  margin-bottom: 8px;
}

.risk-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #909399;
}

.coverage-stats {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.coverage-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.coverage-label {
  font-size: 13px;
  color: #606266;
}

.coverage-value {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  text-align: right;
}

.summary-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.summary-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.summary-label {
  font-size: 13px;
  color: #606266;
}

.summary-value {
  font-size: 18px;
  font-weight: 600;
}

.summary-value.success {
  color: #67c23a;
}

.summary-value.primary {
  color: #409eff;
}

.summary-value.warning {
  color: #e6a23c;
}

.summary-value.danger {
  color: #f56c6c;
}

.ai-suggestions {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.suggestion-item {
  display: flex;
  gap: 12px;
  padding: 16px;
  background: linear-gradient(135deg, #667eea15 0%, #764ba215 100%);
  border-radius: 8px;
}

.suggestion-item .el-icon {
  color: #667eea;
  font-size: 20px;
  flex-shrink: 0;
  margin-top: 2px;
}

.suggestion-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 6px;
}

.suggestion-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}
</style>
