<template>
  <div class="data-lineage-graph">
    <div class="page-header">
      <h3>数据血缘图谱</h3>
      <el-button type="primary" size="small" @click="refreshGraph" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新图谱
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
          <div class="graph-container" ref="graphContainer">
            <div v-if="tables.length === 0" class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Share /></el-icon>
                <p>数据血缘可视化</p>
                <p class="placeholder-tip">选择数据表查看表之间的数据流转关系</p>
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
        <el-card class="detail-card" v-if="selectedTable && getSelectedTable()">
          <template #header>
            <span>表结构详情</span>
          </template>
          <div class="table-detail">
            <h4>{{ getSelectedTable()?.name || '' }}</h4>
            <div class="detail-item">
              <span class="label">字段数</span>
              <span class="value">{{ getSelectedTable()?.columnCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">上游来源</span>
              <span class="value">{{ upstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">下游影响</span>
              <span class="value">{{ downstreamCount }}</span>
            </div>
            <div class="detail-item">
              <span class="label">关联API</span>
              <span class="value">{{ apiCount }}</span>
            </div>
          </div>
        </el-card>

        <el-card class="risk-card" style="margin-top: 16px;" v-if="!selectedTable">
          <template #header>
            <span>数据风险提示</span>
          </template>
          <div class="risk-list">
            <div class="risk-item" v-for="risk in risks" :key="risk.id">
              <el-tag
                :type="risk.level === 'high' ? 'danger' : risk.level === 'medium' ? 'warning' : 'info'"
                size="small"
              >
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
import { ref, computed, onMounted } from 'vue'
import { Tickets, Share, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { graphApi } from '@/api'
import { useRoute } from 'vue-router'

interface TableColumn {
  columnName: string
  dataType: string
  columnComment?: string
}

interface TableListItem {
  id: string
  name: string
  type: string
  columnCount: number
  relationCount: number
  upstreamCount: number
  downstreamCount: number
  apiCount: number
  serviceCount?: number
  columns?: TableColumn[]
}

interface RiskItem {
  id: string
  level: 'high' | 'medium' | 'low'
  text: string
}

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)
const currentVersion = computed(() => route.query.version as string)

const viewMode = ref<'lineage' | 'downstream' | 'upstream'>('lineage')
const searchKeyword = ref('')
const selectedTable = ref<string | null>(null)
const loading = ref(false)

const tables = ref<TableListItem[]>([
  { id: 't-order', name: 't_order', type: 'table', columnCount: 24, relationCount: 8, upstreamCount: 2, downstreamCount: 5, apiCount: 12 },
  { id: 't-order-item', name: 't_order_item', type: 'relation', columnCount: 12, relationCount: 4, upstreamCount: 1, downstreamCount: 3, apiCount: 6 },
  { id: 't-user', name: 't_user', type: 'main', columnCount: 32, relationCount: 15, upstreamCount: 3, downstreamCount: 10, apiCount: 25 },
  { id: 't-product', name: 't_product', type: 'main', columnCount: 18, relationCount: 6, upstreamCount: 2, downstreamCount: 4, apiCount: 8 },
  { id: 't-inventory', name: 't_inventory', type: 'main', columnCount: 14, relationCount: 5, upstreamCount: 1, downstreamCount: 3, apiCount: 6 },
])

const filteredTables = computed(() => {
  if (!searchKeyword.value) return tables.value
  return tables.value.filter(t => t.name.includes(searchKeyword.value))
})

const apiCount = computed(() => {
  const table = getSelectedTable()
  return table ? table.apiCount : 0
})

const serviceCount = computed(() => {
  const table = getSelectedTable()
  return table ? (table.serviceCount || 0) : 0
})

const upstreamCount = computed(() => {
  const table = getSelectedTable()
  return table ? table.upstreamCount : 0
})

const downstreamCount = computed(() => {
  const table = getSelectedTable()
  return table ? table.downstreamCount : 0
})

const risks = ref<RiskItem[]>([
  { id: '1', level: 'high', text: 't_order 表缺少主键定义' },
  { id: '2', level: 'medium', text: '缺少更新时间戳字段缺少注释' },
  { id: '3', level: 'low', text: '库存扣减逻辑缺少数据库事务' },
])

function getSelectedTable(): TableListItem | undefined {
  return tables.value.find(t => t.id === selectedTable.value)
}

async function loadTablesFromBackend() {
  if (!projectId.value || !currentVersion.value) return
  loading.value = true
  try {
    // 从后端获取数据库中已扫描到的表列表
    const data: any = await graphApi.getUnifiedGraph(projectId.value, currentVersion.value, 0)
    if (data && data.nodes) {
      const tableNodes = (data.nodes || []).filter((n: any) =>
        n.type === 'Table' || n.type === 'table'
      )
      if (tableNodes.length > 0) {
        tables.value = tableNodes.map((n: any, idx: number) => ({
          id: n.id || n.key || `table-${idx}`,
          name: n.label || n.key || n.name || `table-${idx}`,
          type: 'table',
          columnCount: 0,
          relationCount: 0,
          upstreamCount: 0,
          downstreamCount: 0,
          apiCount: 0,
        }))
      }
    }
  } catch (error) {
    console.error('加载后端表列表失败，使用本地示例数据', error)
    // 保持本地的示例数据不变
  } finally {
    loading.value = false
  }
}

async function selectTable(id: string) {
  selectedTable.value = selectedTable.value === id ? null : id
  if (!selectedTable.value || !projectId.value || !currentVersion.value) return

  loading.value = true
  try {
    const selected = getSelectedTable()
    if (selected) {
      // 调用后端 API 查询该表的影响范围
      const impact = await graphApi.getTableImpact(projectId.value, currentVersion.value, selected.name)
      if (impact) {
        console.log('Table impact data loaded:', impact)
        ElMessage.success(`已加载 ${selected.name} 的影响分析`)
      }
    }
  } catch (error) {
    console.error('加载表影响数据失败', error)
  } finally {
    loading.value = false
  }
}

async function refreshGraph() {
  if (!selectedTable.value) {
    ElMessage.warning('请先选择一个数据表')
    return
  }
  await selectTable(selectedTable.value)
}

onMounted(async () => {
  if (projectId.value && currentVersion.value) {
    await loadTablesFromBackend()
  }
})
</script>

<style scoped>
.data-lineage-graph {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.table-list {
  max-height: 500px;
  overflow-y: auto;
}

.table-item {
  display: flex;
  align-items: center;
  padding: 10px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: all 0.2s;
}

.table-item:hover {
  background-color: #f5f7fa;
}

.table-item.active {
  background-color: #ecf5ff;
}

.table-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 10px;
  background-color: #f0f9eb;
  color: #67c23a;
}

.table-icon.relation {
  background-color: #ecf5ff;
  color: #409eff;
}

.table-icon.main {
  background-color: #fef0f0;
  color: #f56c6c;
}

.table-info {
  flex: 1;
  min-width: 0;
}

.table-name {
  font-weight: 500;
  font-size: 14px;
  color: #303133;
}

.table-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.graph-toolbar {
  margin-bottom: 12px;
}

.graph-container {
  height: 550px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border-radius: 4px;
}

.graph-placeholder .placeholder-content {
  text-align: center;
  color: #909399;
}

.graph-placeholder p {
  margin: 12px 0;
  font-size: 16px;
}

.placeholder-tip {
  font-size: 13px !important;
  color: #c0c4cc !important;
}

.stats {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  justify-content: center;
}

.table-detail h4 {
  margin: 0 0 12px 0;
  font-size: 15px;
  font-weight: 600;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.detail-item .label {
  color: #909399;
  font-size: 13px;
}

.detail-item .value {
  font-weight: 500;
  color: #303133;
}

.risk-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.risk-text {
  font-size: 13px;
  color: #606266;
  line-height: 1.4;
}
</style>
