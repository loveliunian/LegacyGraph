<template>
  <div class="review-history">
    <div class="page-header">
      <h3>审核历史</h3>
      <div class="header-actions">
        <el-select v-model="filters.targetType" placeholder="审核对象" size="small" clearable style="width: 120px;">
          <el-option label="节点" value="NODE" />
          <el-option label="关系" value="EDGE" />
        </el-select>
        <el-select v-model="filters.status" placeholder="审核状态" size="small" clearable style="width: 120px;">
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已忽略" value="IGNORED" />
          <el-option label="已确认" value="CONFIRMED" />
        </el-select>
        <el-date-picker
          v-model="filters.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          size="small"
          style="width: 260px;"
        />
      </div>
    </div>

    <el-table :data="historyList" v-loading="loading" border stripe>
      <el-table-column prop="targetName" label="审核对象" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="target-name">
            <el-tag size="small" :type="row.targetType === 'NODE' ? 'primary' : 'success'">{{ row.targetType }}</el-tag>
            <span>{{ row.targetName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="graphType" label="图谱类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" v-if="row.graphType">{{ row.graphType }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="confidence" label="置信度" width="120">
        <template #default="{ row }">
          <el-progress :percentage="row.confidence * 100" :stroke-width="8" :show-text="false" />
          <span class="confidence-text">{{ (row.confidence * 100).toFixed(0) }}%</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="审核状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="审核意见" min-width="200" show-overflow-tooltip />
      <el-table-column prop="reviewedBy" label="审核人" width="120" />
      <el-table-column prop="reviewedAt" label="审核时间" width="180">
        <template #default="{ row }">{{ formatTime(row.reviewedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">查看详情</el-button>
          <el-button type="info" link size="small" @click="viewGraph(row)">查看图谱</el-button>
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

    <el-drawer v-model="detailVisible" title="审核详情" size="60%">
      <div v-if="selectedItem" class="review-detail">
        <el-descriptions border :column="2">
          <el-descriptions-item label="审核对象">{{ selectedItem.targetName }}</el-descriptions-item>
          <el-descriptions-item label="对象类型">{{ selectedItem.targetType }}</el-descriptions-item>
          <el-descriptions-item label="图谱类型">{{ selectedItem.graphType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress :percentage="selectedItem.confidence * 100" :stroke-width="10" />
          </el-descriptions-item>
          <el-descriptions-item label="审核状态">
            <el-tag size="small" :type="getStatusType(selectedItem.status)">{{ selectedItem.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审核人">{{ selectedItem.reviewedBy }}</el-descriptions-item>
          <el-descriptions-item label="审核时间" :span="2">{{ formatTime(selectedItem.reviewedAt) }}</el-descriptions-item>
          <el-descriptions-item label="审核意见" :span="2">{{ selectedItem.comment || '无' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section" style="margin-top: 24px;">
          <h4>证据列表 ({{ selectedItem.evidenceCount || 0 }})</h4>
          <el-table :data="evidenceList" size="small" border>
            <el-table-column prop="evidenceType" label="证据类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="source" label="来源" min-width="200" show-overflow-tooltip />
            <el-table-column prop="summary" label="摘要" min-width="300" show-overflow-tooltip />
          </el-table>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

const loading = ref(false)
const detailVisible = ref(false)
const selectedItem = ref<any>(null)

const filters = ref({
  targetType: '',
  status: '',
  dateRange: []
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 89
})

const historyList = ref([
  {
    id: '1',
    targetType: 'NODE',
    targetName: 'OrderController.createOrder -> OrderService.processOrder',
    graphType: 'CODE',
    confidence: 0.95,
    status: 'APPROVED',
    comment: '代码分析正确，调用关系明确',
    reviewedBy: '张三',
    reviewedAt: new Date(Date.now() - 3600000).toISOString(),
    evidenceCount: 3
  },
  {
    id: '2',
    targetType: 'NODE',
    targetName: 'OrderService.processOrder -> t_order表',
    graphType: 'DATA_LINEAGE',
    confidence: 0.88,
    status: 'CONFIRMED',
    comment: '数据流向正确，SQL分析无误',
    reviewedBy: '李四',
    reviewedAt: new Date(Date.now() - 7200000).toISOString(),
    evidenceCount: 2
  },
  {
    id: '3',
    targetType: 'EDGE',
    targetName: '创建订单 -> 扣减库存 调用关系',
    graphType: 'BUSINESS',
    confidence: 0.72,
    status: 'REJECTED',
    comment: '实际代码中扣减库存是异步消息触发，非直接调用',
    reviewedBy: '王五',
    reviewedAt: new Date(Date.now() - 86400000).toISOString(),
    evidenceCount: 1
  },
  {
    id: '4',
    targetType: 'NODE',
    targetName: '订单创建流程业务节点',
    graphType: 'BUSINESS',
    confidence: 0.68,
    status: 'IGNORED',
    comment: '该业务流程已重构，旧代码作废',
    reviewedBy: '赵六',
    reviewedAt: new Date(Date.now() - 172800000).toISOString(),
    evidenceCount: 2
  },
  {
    id: '5',
    targetType: 'EDGE',
    targetName: 'PaymentService.notify -> OrderService.updateStatus',
    graphType: 'CODE',
    confidence: 0.91,
    status: 'APPROVED',
    comment: '回调关系正确',
    reviewedBy: '张三',
    reviewedAt: new Date(Date.now() - 259200000).toISOString(),
    evidenceCount: 4
  }
])

const evidenceList = ref([
  { type: '代码行', source: 'OrderController.java:45', summary: '调用orderService.processOrder()' },
  { type: '代码行', source: 'OrderService.java:78', summary: '方法实现包含业务逻辑' },
  { type: 'SQL语句', source: 'OrderMapper.xml:23', summary: 'INSERT INTO t_order' }
])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getStatusType = (status: string) => {
  const map: Record<string, string> = {
    APPROVED: 'success',
    REJECTED: 'danger',
    IGNORED: 'info',
    CONFIRMED: 'success'
  }
  return map[status] || 'info'
}

const viewDetail = (row: any) => {
  selectedItem.value = row
  detailVisible.value = true
}

const viewGraph = (row: any) => {
  ElMessage.info(`查看 ${row.targetName} 的图谱位置`)
}
</script>

<style scoped>
.review-history {
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

.target-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.confidence-text {
  font-size: 12px;
  color: #606266;
  margin-left: 8px;
}

.text-gray {
  color: #909399;
}

.review-detail {
  padding: 10px 0;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}
</style>
