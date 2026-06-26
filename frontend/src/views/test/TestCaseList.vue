<template>
  <div class="test-case-list">
    <div class="page-header">
      <h3>测试用例</h3>
      <div class="header-actions">
        <el-select v-model="filters.caseType" placeholder="用例类型" size="small" clearable style="width: 140px;">
          <el-option label="API测试" value="API" />
          <el-option label="端到端测试" value="E2E" />
          <el-option label="数据库断言" value="DB_ASSERTION" />
          <el-option label="业务规则测试" value="BUSINESS_RULE" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" size="small" clearable style="width: 120px;">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已禁用" value="DISABLED" />
        </el-select>
        <el-button type="primary" size="small" @click="showGenerateDialog">
          <el-icon><MagicStick /></el-icon>
          AI 生成用例
        </el-button>
      </div>
    </div>

    <el-table :data="caseList" v-loading="loading" border stripe>
      <el-table-column prop="caseNo" label="用例编号" width="140" />
      <el-table-column prop="caseName" label="用例名称" min-width="250" show-overflow-tooltip />
      <el-table-column prop="caseType" label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="getCaseTypeColor(row.caseType)">{{ getCaseTypeText(row.caseType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="featureName" label="关联功能" min-width="150" show-overflow-tooltip />
      <el-table-column prop="apiPath" label="API路径" min-width="200" show-overflow-tooltip />
      <el-table-column prop="assertionCount" label="断言数" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.assertionCount }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="generateType" label="生成方式" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.generateType === 'AI_GENERATED' ? 'warning' : 'info'">
            {{ row.generateType === 'AI_GENERATED' ? 'AI生成' : '手动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">{{ getStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastRunStatus" label="最近运行" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.lastRunStatus" size="small" :type="row.lastRunStatus === 'PASSED' ? 'success' : 'danger'">
            {{ row.lastRunStatus === 'PASSED' ? '通过' : '失败' }}
          </el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="lastRunTime" label="运行时间" width="180">
        <template #default="{ row }">
          <span v-if="row.lastRunTime">{{ formatTime(row.lastRunTime) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">查看</el-button>
          <el-button type="success" link size="small" @click="runCase(row)">运行</el-button>
          <el-button type="warning" link size="small" @click="editCase(row)">编辑</el-button>
          <el-button type="danger" link size="small" @click="deleteCase(row)">删除</el-button>
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

    <el-dialog v-model="generateDialogVisible" title="AI 生成测试用例" width="700px">
      <el-form label-width="120px">
        <el-form-item label="生成范围">
          <el-checkbox-group v-model="generateForm.scopes">
            <el-checkbox label="order_module">订单模块</el-checkbox>
            <el-checkbox label="user_module">用户模块</el-checkbox>
            <el-checkbox label="payment_module">支付模块</el-checkbox>
            <el-checkbox label="product_module">商品模块</el-checkbox>
            <el-checkbox label="inventory_module">库存模块</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="用例类型">
          <el-checkbox-group v-model="generateForm.types">
            <el-checkbox label="API">API测试</el-checkbox>
            <el-checkbox label="E2E">端到端测试</el-checkbox>
            <el-checkbox label="DB_ASSERTION">数据库断言</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="生成数量">
          <el-input-number v-model="generateForm.count" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input v-model="generateForm.remark" type="textarea" :rows="3" placeholder="请输入需要特别关注的业务场景..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="generateCases" :loading="generating">开始生成</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="用例详情" size="60%">
      <div v-if="selectedCase" class="case-detail">
        <el-descriptions border :column="2">
          <el-descriptions-item label="用例编号">{{ selectedCase.caseNo }}</el-descriptions-item>
          <el-descriptions-item label="用例名称">{{ selectedCase.caseName }}</el-descriptions-item>
          <el-descriptions-item label="用例类型">{{ getCaseTypeText(selectedCase.caseType) }}</el-descriptions-item>
          <el-descriptions-item label="关联功能">{{ selectedCase.featureName }}</el-descriptions-item>
          <el-descriptions-item label="API路径">{{ selectedCase.apiPath || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ getStatusText(selectedCase.status) }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section" style="margin-top: 24px;">
          <h4>测试步骤</h4>
          <el-timeline>
            <el-timeline-item
              v-for="(step, index) in selectedCase.steps"
              :key="index"
              :timestamp="`步骤 ${index + 1}`"
            >
              {{ step }}
            </el-timeline-item>
          </el-timeline>
        </div>

        <div class="detail-section" style="margin-top: 24px;">
          <h4>断言列表 ({{ selectedCase.assertionCount }})</h4>
          <ul class="assertion-list">
            <li v-for="(assertion, index) in selectedCase.assertions" :key="index">{{ assertion }}</li>
          </ul>
        </div>

        <div class="detail-section" style="margin-top: 24px;">
          <h4>关联节点</h4>
          <el-tag v-for="node in selectedCase.relatedNodeIds" :key="node" size="small" style="margin-right: 8px; margin-bottom: 8px;">
            {{ node }}
          </el-tag>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const loading = ref(false)
const generateDialogVisible = ref(false)
const detailVisible = ref(false)
const selectedCase = ref<any>(null)
const generating = ref(false)

const filters = ref({
  caseType: '',
  status: ''
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 156
})

const generateForm = ref({
  scopes: ['order_module'],
  types: ['API', 'E2E'],
  count: 10,
  remark: ''
})

const caseList = ref([
  {
    id: '1',
    caseNo: 'TC-ORDER-001',
    caseName: '创建订单成功流程测试',
    caseType: 'E2E',
    featureName: '订单创建',
    apiPath: 'POST /api/order/create',
    assertionCount: 5,
    generateType: 'AI_GENERATED',
    status: 'CONFIRMED',
    lastRunStatus: 'PASSED',
    lastRunTime: new Date(Date.now() - 3600000).toISOString(),
    steps: [
      '用户登录系统',
      '选择商品加入购物车',
      '提交订单信息',
      '完成支付',
      '验证订单状态为已创建'
    ],
    assertions: [
      '订单ID不为空',
      '订单状态为CREATED',
      '库存数量正确扣减',
      '用户账户余额正确减少',
      '创建通知已发送'
    ],
    relatedNodeIds: ['OrderController', 'OrderService', 't_order', '库存扣减流程']
  },
  {
    id: '2',
    caseNo: 'TC-ORDER-002',
    caseName: '库存不足时创建订单失败测试',
    caseType: 'API',
    featureName: '订单创建',
    apiPath: 'POST /api/order/create',
    assertionCount: 3,
    generateType: 'AI_GENERATED',
    status: 'CONFIRMED',
    lastRunStatus: 'PASSED',
    lastRunTime: new Date(Date.now() - 7200000).toISOString(),
    steps: [
      '设置商品库存为0',
      '用户提交订单',
      '验证返回错误信息'
    ],
    assertions: [
      '返回错误码INVALID_STOCK',
      '订单未被创建',
      '库存未发生变化'
    ],
    relatedNodeIds: ['OrderService', 'InventoryService', 't_inventory']
  },
  {
    id: '3',
    caseNo: 'TC-PAY-001',
    caseName: '支付回调通知测试',
    caseType: 'API',
    featureName: '支付回调',
    apiPath: 'POST /api/payment/notify',
    assertionCount: 4,
    generateType: 'MANUAL',
    status: 'CONFIRMED',
    lastRunStatus: 'FAILED',
    lastRunTime: new Date(Date.now() - 86400000).toISOString(),
    steps: [
      '模拟第三方支付回调',
      '验证签名正确',
      '更新订单状态',
      '发送支付成功通知'
    ],
    assertions: [
      '签名验证通过',
      '订单状态更新为PAID',
      '通知记录已创建',
      '幂等性验证'
    ],
    relatedNodeIds: ['PaymentController', 'PaymentService', 't_payment']
  },
  {
    id: '4',
    caseNo: 'TC-USER-001',
    caseName: '用户注册数据库断言测试',
    caseType: 'DB_ASSERTION',
    featureName: '用户注册',
    apiPath: null,
    assertionCount: 4,
    generateType: 'AI_GENERATED',
    status: 'DRAFT',
    lastRunStatus: null,
    lastRunTime: null,
    steps: [
      '提交用户注册信息',
      '查询数据库验证数据'
    ],
    assertions: [
      't_user表新增一条记录',
      'username字段正确',
      'email字段正确',
      'password字段已加密'
    ],
    relatedNodeIds: ['UserController', 'UserService', 't_user']
  }
])

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm')
}

const getCaseTypeText = (type: string) => {
  const map: Record<string, string> = {
    API: 'API测试',
    E2E: '端到端测试',
    DB_ASSERTION: '数据库断言',
    BUSINESS_RULE: '业务规则测试'
  }
  return map[type] || type
}

const getCaseTypeColor = (type: string) => {
  const map: Record<string, string> = {
    API: 'primary',
    E2E: 'success',
    DB_ASSERTION: 'warning',
    BUSINESS_RULE: 'danger'
  }
  return map[type] || 'info'
}

const getStatusText = (status: string) => {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    CONFIRMED: '已确认',
    DISABLED: '已禁用'
  }
  return map[status] || status
}

const viewDetail = (row: any) => {
  selectedCase.value = row
  detailVisible.value = true
}

const runCase = (row: any) => {
  ElMessage.info(`运行用例: ${row.caseName}`)
}

const editCase = (row: any) => {
  ElMessage.info(`编辑用例: ${row.caseName}`)
}

const deleteCase = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除用例 ${row.caseNo} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    ElMessage.success('删除成功')
  } catch {
    // cancelled
  }
}

const showGenerateDialog = () => {
  generateDialogVisible.value = true
}

const generateCases = async () => {
  generating.value = true
  try {
    await new Promise(resolve => setTimeout(resolve, 2000))
    ElMessage.success(`已生成 ${generateForm.value.count} 个测试用例`)
    generateDialogVisible.value = false
  } catch (error) {
    ElMessage.error('生成失败')
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.test-case-list {
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

.text-gray {
  color: #909399;
}

.case-detail {
  padding: 10px 0;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.assertion-list {
  margin: 0;
  padding-left: 20px;
}

.assertion-list li {
  margin-bottom: 8px;
  color: #606266;
}
</style>
