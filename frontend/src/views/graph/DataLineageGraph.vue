<template>
  <div class="data-lineage-graph">
    <div class="page-header">
      <h3>数据血缘图谱</h3>
      <el-button type="primary" size="small" @click="showImpactAnalysis">
        <el-icon><Warning /></el-icon>
        影响分析
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="5">
        <el-card class="table-card">
          <template #header>
            <span>数据表</span>
          </template>
          <el-input
            v-model="searchKeyword"
            placeholder="搜索表名"
            prefix-icon="Search"
            size="small"
            style="margin-bottom: 12px;"
          />
          <div class="table-list">
            <div
              v-for="table in filteredTables"
              :key="table.id"
              class="table-item"
              :class="{ active: selectedTable === table.id }"
              @click="selectTable(table.id)"
            >
              <div class="table-icon" :class="table.type">
                <el-icon><Tickets /></el-icon>
              </div>
              <div class="table-info">
                <div class="table-name">{{ table.name }}</div>
                <div class="table-desc">
                  {{ table.columnCount }} 字段 · {{ table.relationCount }} 关联
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <el-button-group>
              <el-button size="small" :type="viewMode === 'lineage' ? 'primary' : ''" @click="viewMode = 'lineage'">
                完整血缘
              </el-button>
              <el-button size="small" :type="viewMode === 'downstream' ? 'primary' : ''" @click="viewMode = 'downstream'">
                下游影响
              </el-button>
              <el-button size="small" :type="viewMode === 'upstream' ? 'primary' : ''" @click="viewMode = 'upstream'">
                上游来源
              </el-button>
            </el-button-group>
          </div>
          <div class="graph-container">
            <div class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Share /></el-icon>
                <p>数据血缘可视化</p>
                <p class="placeholder-tip">展示数据表、API、服务之间的数据流转关系</p>
                <div class="stats">
                  <el-tag type="danger">数据表: {{ tables.length }}</el-tag>
                  <el-tag type="primary">API接口: {{ apiCount }}</el-tag>
                  <el-tag type="success">服务: {{ serviceCount }}</el-tag>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="5">
        <el-card class="detail-card" v-if="selectedTable">
          <template #header>
            <span>表结构详情</span>
          </template>
          <div class="table-detail">
            <h4>{{ getSelectedTableName() }}</h4>
            <div class="detail-item">
              <span class="label">字段数</span>
              <span class="value">{{ getSelectedTable()?.columnCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">上游来源</span>
              <span class="value">{{ getSelectedTable()?.upstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">下游影响</span>
              <span class="value">{{ getSelectedTable()?.downstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">关联API</span>
              <span class="value">{{ getSelectedTable()?.apiCount }}</span>
            </div>
          </div>

          <div class="field-list">
            <h4>字段列表</h4>
            <div class="field-item" v-for="field in tableFields" :key="field.id">
              <span class="field-name">{{ field.name }}</span>
              <span class="field-type">{{ field.type }}</span>
            </div>
          </div>
        </el-card>

        <el-card class="risk-card" style="margin-top: 16px;" v-else>
          <template #header>
            <span>数据风险提示</span>
          </template>
          <div class="risk-list">
            <div class="risk-item" v-for="risk in risks" :key="risk.id">
              <el-tag :type="risk.level === 'high' ? 'danger' : risk.level === 'medium' ? 'warning' : 'info'" size="small">
                {{ risk.level === 'high' ? '高风险' : risk.level === 'medium' ? '中风险' : '低风险' }}
              </el-tag>
              <span class="risk-text">{{ risk.text }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Warning, Tickets, Search } from '@element-plus/icons-vue'

const viewMode = ref('lineage')
const searchKeyword = ref('')
const selectedTable = ref<string | null>(null)

const tables = ref([
  { id: '1', name: 't_order', type: 'main', columnCount: 24, relationCount: 8, upstreamCount: 2, downstreamCount: 5, apiCount: 12 },
  { id: '2', name: 't_order_item', type: 'relation', columnCount: 12, relationCount: 4, upstreamCount: 1, downstreamCount: 3, apiCount: 6 },
  { id: '3', name: 't_user', type: 'main', columnCount: 32, relationCount: 15, upstreamCount: 3, downstreamCount: 10, apiCount: 25 },
  { id: '4', name: 't_product', type: 'main', columnCount: 18, relationCount: 6, upstreamCount: 2, downstreamCount: 4, apiCount: 8 },
  { id: '5', name: 't_inventory', type: 'main', columnCount: 14, relationCount: 5, upstreamCount: 1, downstreamCount: 3, apiCount: 6 }
])

const tableFields = ref([
  { id: '1', name: 'id', type: 'bigint' },
  { id: '2', name: 'order_no', type: 'varchar(64)' },
  { id: '3', name: 'user_id', type: 'bigint' },
  { id: '4', name: 'total_amount', type: 'decimal(10,2)' },
  { id: '5', name: 'status', type: 'int' },
  { id: '6', name: 'created_at', type: 'datetime' },
  { id: '7', name: 'updated_at', type: 'datetime' }
])

const risks = ref([
  { id: '1', level: 'high', text: 't_order 表缺少字段级别的数据血缘追踪' },
  { id: '2', level: 'medium', text: '订单状态变更没有记录操作人' },
  { id: '3', level: 'low', text: '库存扣减逻辑缺少事务保障说明' }
])

const apiCount = 42
const serviceCount = 12

const filteredTables = computed(() => {
  if (!searchKeyword.value) return tables.value
  return tables.value.filter(t => t.name.includes(searchKeyword.value))
})

const getSelectedTableName = () => {
  const table = tables.value.find(t => t.id === selectedTable.value)
  return table ? table.name : ''
}

const getSelectedTable = () => {
  return tables.value.find(t => t.id === selectedTable.value)
}

const selectTable = (id: string) => {
  selectedTable.value = selectedTable.value === id ? null : id
}

const showImpactAnalysis = () => {
  ElMessage.info('影响分析功能')
}
</script>

<style scoped>
.data-lineage-graph {
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

.table-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 600px;
  overflow-y: auto;
}

.table-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid transparent;
}

.table-item:hover {
  background: #f5f7fa;
}

.table-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.table-icon {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
}

.table-icon.main {
  background: #409eff;
}

.table-icon.relation {
  background: #67c23a;
}

.table-info {
  flex: 1;
}

.table-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 2px;
  font-family: monospace;
}

.table-desc {
  font-size: 11px;
  color: #909399;
}

.graph-card {
  height: 100%;
}

.graph-toolbar {
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

.table-detail h4 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #ebeef5;
}

.label {
  font-size: 13px;
  color: #606266;
}

.value {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.field-list {
  margin-top: 20px;
}

.field-list h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.field-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 12px;
  border-bottom: 1px dashed #ebeef5;
}

.field-name {
  color: #303133;
  font-family: monospace;
}

.field-type {
  color: #909399;
}

.risk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.risk-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.risk-text {
  font-size: 12px;
  color: #606266;
  flex: 1;
  line-height: 1.5;
}
</style>
