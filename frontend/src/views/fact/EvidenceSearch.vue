<template>
  <div class="evidence-search">
    <div class="page-header">
      <h3>证据检索</h3>
      <div class="header-actions">
        <el-select v-model="filters.evidenceType" placeholder="证据类型" size="small" clearable style="width: 140px;">
          <el-option label="代码行" value="FILE_LINE" />
          <el-option label="SQL语句" value="SQL_STATEMENT" />
          <el-option label="数据库模式" value="DB_SCHEMA" />
          <el-option label="文档段落" value="DOC_PARAGRAPH" />
          <el-option label="API文档" value="API_DOC" />
          <el-option label="测试结果" value="TEST_RESULT" />
          <el-option label="AI推理" value="AI_REASONING" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="搜索证据内容" size="small" style="width: 300px;" clearable>
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" size="small" @click="doSearch">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
      </div>
    </div>

    <el-table :data="evidenceList" v-loading="loading" border stripe>
      <el-table-column prop="evidenceType" label="证据类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="getEvidenceTypeColor(row.evidenceType)">
            {{ getEvidenceTypeText(row.evidenceType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceName" label="来源名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="location" label="位置" width="150" show-overflow-tooltip />
      <el-table-column prop="summary" label="摘要" min-width="300" show-overflow-tooltip />
      <el-table-column prop="relatedNodeCount" label="关联节点" width="100" align="center">
        <template #default="{ row }">
          <el-badge :value="row.relatedNodeCount" class="item" />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提取时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewContent(row)">查看原文</el-button>
          <el-button type="success" link size="small" @click="viewRelated(row)">关联图谱</el-button>
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

    <el-dialog v-model="contentVisible" title="证据原文" width="900px">
      <div v-if="selectedEvidence" class="evidence-content">
        <el-descriptions border :column="2" size="small">
          <el-descriptions-item label="证据类型">{{ getEvidenceTypeText(selectedEvidence.evidenceType) }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ selectedEvidence.sourceName }}</el-descriptions-item>
          <el-descriptions-item label="位置" :span="2">{{ selectedEvidence.location }}</el-descriptions-item>
        </el-descriptions>

        <div class="content-section" style="margin-top: 20px;">
          <h4>内容</h4>
          <div class="code-block">{{ selectedEvidence.content || selectedEvidence.summary }}</div>
        </div>

        <div class="content-section" style="margin-top: 20px;">
          <h4>关联节点 ({{ selectedEvidence.relatedNodeIds?.length || 0 }})</h4>
          <el-tag v-for="node in selectedEvidence.relatedNodeIds" :key="node" size="small" style="margin-right: 8px; margin-bottom: 8px;">
            {{ node }}
          </el-tag>
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
const contentVisible = ref(false)
const selectedEvidence = ref<any>(null)

const filters = ref({
  evidenceType: '',
  keyword: ''
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 234
})

const evidenceList = ref([
  {
    id: '1',
    evidenceType: 'FILE_LINE',
    sourceName: 'OrderController.java',
    location: 'com/example/controller/OrderController.java:45-67',
    summary: '创建订单方法，包含参数校验、调用Service、返回结果',
    content: '@PostMapping("/create")\npublic Result<Long> createOrder(@RequestBody OrderDTO dto) {\n    validateOrder(dto);\n    Long orderId = orderService.processOrder(dto);\n    return Result.success(orderId);\n}',
    relatedNodeCount: 3,
    relatedNodeIds: ['OrderController', 'OrderService', 'createOrder API'],
    createdAt: new Date(Date.now() - 3600000).toISOString()
  },
  {
    id: '2',
    evidenceType: 'SQL_STATEMENT',
    sourceName: 'OrderMapper.xml',
    location: 'com/example/mapper/OrderMapper.xml:23-35',
    summary: '根据用户ID查询订单列表的SQL语句',
    content: '<select id="listByUserId" resultType="Order">\n    SELECT * FROM t_order\n    WHERE user_id = #{userId}\n    ORDER BY created_at DESC\n</select>',
    relatedNodeCount: 2,
    relatedNodeIds: ['OrderMapper', 't_order'],
    createdAt: new Date(Date.now() - 7200000).toISOString()
  },
  {
    id: '3',
    evidenceType: 'DB_SCHEMA',
    sourceName: 'legacy_db.public',
    location: 'public.t_order',
    summary: '订单表结构定义，包含id、user_id、total_amount、status等字段',
    content: 'CREATE TABLE t_order (\n    id BIGINT PRIMARY KEY,\n    user_id BIGINT NOT NULL,\n    total_amount DECIMAL(10,2) NOT NULL,\n    status INT NOT NULL DEFAULT 0,\n    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP\n);',
    relatedNodeCount: 5,
    relatedNodeIds: ['t_order', 'Order实体', 'OrderMapper'],
    createdAt: new Date(Date.now() - 86400000).toISOString()
  },
  {
    id: '4',
    evidenceType: 'DOC_PARAGRAPH',
    sourceName: '业务流程.md',
    location: '第45-52行',
    summary: '订单创建成功后，需要同步扣减对应商品的库存，并发送通知',
    content: '### 订单创建流程\n\n1. 用户提交订单信息\n2. 系统校验订单数据合法性\n3. 系统扣减商品库存\n4. 系统生成订单记录\n5. 系统发送订单创建通知',
    relatedNodeCount: 4,
    relatedNodeIds: ['订单创建流程', '库存扣减', 'OrderService'],
    createdAt: new Date(Date.now() - 172800000).toISOString()
  },
  {
    id: '5',
    evidenceType: 'TEST_RESULT',
    sourceName: 'OrderServiceTest.java',
    location: 'testCreateOrder()',
    summary: '订单创建单元测试，测试正常流程和异常场景',
    content: '@Test\npublic void testCreateOrder() {\n    OrderDTO dto = new OrderDTO();\n    dto.setUserId(1L);\n    dto.setItems(Collections.singletonList(item));\n    Long orderId = orderService.processOrder(dto);\n    assertNotNull(orderId);\n    assertEquals(OrderStatus.CREATED, order.getStatus());\n}',
    relatedNodeCount: 2,
    relatedNodeIds: ['OrderService', '订单创建测试'],
    createdAt: new Date(Date.now() - 259200000).toISOString()
  },
  {
    id: '6',
    evidenceType: 'AI_REASONING',
    sourceName: 'AI归纳',
    location: '业务规则推断',
    summary: 'AI基于代码和文档归纳出订单与库存的强关联关系',
    content: '基于代码分析和文档分析，推断出订单创建过程中必须调用库存服务进行库存扣减，两个模块存在强数据依赖关系。',
    relatedNodeCount: 4,
    relatedNodeIds: ['订单模块', '库存模块', '数据依赖关系'],
    createdAt: new Date(Date.now() - 345600000).toISOString()
  }
])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getEvidenceTypeText = (type: string) => {
  const map: Record<string, string> = {
    FILE_LINE: '代码行',
    SQL_STATEMENT: 'SQL语句',
    DB_SCHEMA: '数据库模式',
    DOC_PARAGRAPH: '文档段落',
    API_DOC: 'API文档',
    TEST_RESULT: '测试结果',
    AI_REASONING: 'AI推理'
  }
  return map[type] || type
}

const getEvidenceTypeColor = (type: string) => {
  const map: Record<string, string> = {
    FILE_LINE: 'primary',
    SQL_STATEMENT: 'success',
    DB_SCHEMA: 'warning',
    DOC_PARAGRAPH: 'info',
    API_DOC: 'danger',
    TEST_RESULT: 'success',
    AI_REASONING: 'danger'
  }
  return map[type] || 'info'
}

const doSearch = () => {
  loading.value = true
  setTimeout(() => {
    loading.value = false
    ElMessage.success('搜索完成')
  }, 500)
}

const viewContent = (row: any) => {
  selectedEvidence.value = row
  contentVisible.value = true
}

const viewRelated = (row: any) => {
  ElMessage.info(`查看 ${row.sourceName} 的关联图谱`)
}
</script>

<style scoped>
.evidence-search {
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

.evidence-content {
  padding: 10px 0;
}

.content-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.code-block {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  white-space: pre-wrap;
  max-height: 400px;
  overflow-y: auto;
}
</style>
