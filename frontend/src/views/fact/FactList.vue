<template>
  <div class="fact-list">
    <div class="page-header">
      <h3>事实列表</h3>
      <div class="header-actions">
        <el-select v-model="filters.factType" placeholder="事实类型" size="small" clearable style="width: 140px;">
          <el-option label="API接口" value="API" />
          <el-option label="类定义" value="CLASS" />
          <el-option label="方法" value="METHOD" />
          <el-option label="SQL语句" value="SQL" />
          <el-option label="数据表" value="TABLE" />
          <el-option label="业务规则" value="RULE" />
        </el-select>
        <el-select v-model="filters.sourceType" placeholder="来源类型" size="small" clearable style="width: 120px;">
          <el-option label="代码" value="CODE" />
          <el-option label="数据库" value="DB" />
          <el-option label="文档" value="DOC" />
          <el-option label="AI归纳" value="AI" />
          <el-option label="测试" value="TEST" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="搜索事实" size="small" style="width: 200px;" clearable>
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>
    </div>

    <el-table :data="facts" v-loading="loading" border stripe>
      <el-table-column prop="factName" label="事实名称" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="fact-name">
            <el-tag size="small" type="info">{{ row.factType }}</el-tag>
            <span>{{ row.factName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="sourceType" label="来源类型" width="100">
        <template #default="{ row }">
          <el-tag size="small">{{ row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourcePath" label="来源路径" min-width="200" show-overflow-tooltip />
      <el-table-column prop="sourceLine" label="行号" width="80" />
      <el-table-column prop="confidence" label="置信度" width="120">
        <template #default="{ row }">
          <el-progress :percentage="row.confidence * 100" :stroke-width="8" :show-text="false" />
          <span class="confidence-text">{{ (row.confidence * 100).toFixed(0) }}%</span>
        </template>
      </el-table-column>
      <el-table-column prop="relatedNodeCount" label="关联节点" width="100" align="center">
        <template #default="{ row }">
          <el-badge :value="row.relatedNodeCount" class="item" />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
          <el-button type="success" link size="small" @click="viewRelatedNodes(row)">关联图谱</el-button>
          <el-button type="info" link size="small" @click="viewEvidence(row)">证据</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pagination.page"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top: 20px; justify-content: flex-end;"
    />

    <el-dialog v-model="detailVisible" title="事实详情" width="800px">
      <div v-if="selectedFact" class="fact-detail">
        <el-descriptions border :column="2">
          <el-descriptions-item label="事实名称">{{ selectedFact.factName }}</el-descriptions-item>
          <el-descriptions-item label="事实类型">{{ selectedFact.factType }}</el-descriptions-item>
          <el-descriptions-item label="来源类型">{{ selectedFact.sourceType }}</el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress :percentage="selectedFact.confidence * 100" :stroke-width="8" />
          </el-descriptions-item>
          <el-descriptions-item label="来源路径" :span="2">{{ selectedFact.sourcePath }}</el-descriptions-item>
          <el-descriptions-item label="行号">{{ selectedFact.sourceLine }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(selectedFact.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section" v-if="selectedFact.contentSummary">
          <h4>内容摘要</h4>
          <div class="code-block">{{ selectedFact.contentSummary }}</div>
        </div>

        <div class="detail-section">
          <h4>关联节点 ({{ selectedFact.relatedNodeCount }})</h4>
          <el-table :data="relatedNodes" size="small" border>
            <el-table-column prop="nodeName" label="节点名称" />
            <el-table-column prop="nodeType" label="节点类型" width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ row.nodeType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="100">
              <template #default="{ row }">{{ (row.confidence * 100).toFixed(0) }}%</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const loading = ref(false)
const detailVisible = ref(false)
const selectedFact = ref<any>(null)

const filters = ref({
  factType: '',
  sourceType: '',
  keyword: ''
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 156
})

const facts = ref([
  {
    id: '1',
    factName: 'OrderController.createOrder',
    factType: 'METHOD',
    sourceType: 'CODE',
    sourcePath: 'com/example/controller/OrderController.java',
    sourceLine: 45,
    confidence: 0.95,
    relatedNodeCount: 3,
    contentSummary: '创建订单接口，接收用户ID和商品列表，返回订单ID',
    createdAt: new Date(Date.now() - 3600000).toISOString()
  },
  {
    id: '2',
    factName: 'OrderService.processOrder',
    factType: 'METHOD',
    sourceType: 'CODE',
    sourcePath: 'com/example/service/OrderService.java',
    sourceLine: 78,
    confidence: 0.92,
    relatedNodeCount: 5,
    contentSummary: '处理订单逻辑，包括校验库存、计算价格、生成订单',
    createdAt: new Date(Date.now() - 3600000).toISOString()
  },
  {
    id: '3',
    factName: 't_order表',
    factType: 'TABLE',
    sourceType: 'DB',
    sourcePath: 'legacy_db.public',
    sourceLine: null,
    confidence: 0.98,
    relatedNodeCount: 8,
    contentSummary: '订单主表，包含订单ID、用户ID、总金额、状态等字段',
    createdAt: new Date(Date.now() - 86400000).toISOString()
  },
  {
    id: '4',
    factName: 'SELECT * FROM t_order WHERE user_id = ?',
    factType: 'SQL',
    sourceType: 'CODE',
    sourcePath: 'com/example/mapper/OrderMapper.xml',
    sourceLine: 23,
    confidence: 0.88,
    relatedNodeCount: 2,
    contentSummary: '根据用户ID查询订单列表',
    createdAt: new Date(Date.now() - 7200000).toISOString()
  },
  {
    id: '5',
    factName: '订单创建成功后需要扣减库存',
    factType: 'RULE',
    sourceType: 'DOC',
    sourcePath: 'docs/业务流程.md',
    sourceLine: 45,
    confidence: 0.75,
    relatedNodeCount: 4,
    contentSummary: '业务规则：订单创建成功后，需要同步扣减对应商品的库存',
    createdAt: new Date(Date.now() - 172800000).toISOString()
  },
  {
    id: '6',
    factName: 'GET /api/product/list',
    factType: 'API',
    sourceType: 'CODE',
    sourcePath: 'com/example/controller/ProductController.java',
    sourceLine: 32,
    confidence: 0.94,
    relatedNodeCount: 3,
    contentSummary: '商品列表查询接口，支持分页和分类筛选',
    createdAt: new Date(Date.now() - 3600000).toISOString()
  }
])

const relatedNodes = ref([
  { nodeName: 'OrderController', nodeType: 'CONTROLLER', confidence: 0.95 },
  { nodeName: 'OrderService', nodeType: 'SERVICE', confidence: 0.92 },
  { nodeName: 't_order', nodeType: 'TABLE', confidence: 0.88 }
])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const viewDetail = (row: any) => {
  selectedFact.value = row
  detailVisible.value = true
}

const viewRelatedNodes = (row: any) => {
  ElMessage.info(`查看 ${row.factName} 的关联图谱`)
}

const viewEvidence = (row: any) => {
  ElMessage.info(`查看 ${row.factName} 的证据`)
}
</script>

<style scoped>
.fact-list {
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

.fact-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.confidence-text {
  font-size: 12px;
  color: #606266;
  margin-left: 8px;
}

.fact-detail {
  padding: 10px 0;
}

.detail-section {
  margin-top: 24px;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.code-block {
  background: #f5f7fa;
  padding: 12px 16px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
