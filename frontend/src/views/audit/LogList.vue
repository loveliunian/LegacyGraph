<template>
  <div class="audit-log-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>操作日志 <el-tag v-if="logCount !== null" size="small" type="info" style="margin-left: 8px">共 {{ logCount }} 条</el-tag></span>
          <div class="header-actions">
            <el-button type="danger" size="small" plain @click="handleClear">
              <el-icon><Delete /></el-icon>
              清空日志
            </el-button>
            <el-button type="primary" size="small" @click="handleExport">
              <el-icon><Download /></el-icon>
              导出日志
            </el-button>
          </div>
        </div>
      </template>

      <el-form :model="filters" inline class="search-form">
        <el-form-item label="操作类型">
          <el-select v-model="filters.actionType" placeholder="请选择" clearable style="width: 150px">
            <el-option label="全部" value="" />
            <el-option label="新增" value="create" />
            <el-option label="修改" value="update" />
            <el-option label="删除" value="delete" />
            <el-option label="查询" value="query" />
            <el-option label="导出" value="export" />
            <el-option label="登录" value="login" />
            <el-option label="登出" value="logout" />
          </el-select>
        </el-form-item>

        <el-form-item label="操作模块">
          <el-select v-model="filters.module" placeholder="请选择" clearable style="width: 150px">
            <el-option label="全部" value="" />
            <el-option label="代码图谱" value="graph" />
            <el-option label="扫描任务" value="scan" />
            <el-option label="报告管理" value="report" />
            <el-option label="系统设置" value="system" />
          </el-select>
        </el-form-item>

        <el-form-item label="操作人">
          <el-input v-model="filters.username" placeholder="请输入" clearable style="width: 150px" />
        </el-form-item>

        <el-form-item label="操作时间">
          <el-date-picker
            v-model="filters.dateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 300px"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        title="本次查询共找到 1,234 条记录"
        type="info"
        :closable="false"
        show-icon
        class="stat-alert"
      />

      <el-table
        v-loading="loading"
        :data="tableData"
        border
        stripe
        style="margin-top: 16px"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column prop="id" label="日志ID" width="100" />
        <el-table-column prop="module" label="操作模块" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="getModuleType(row.module)">
              {{ getModuleLabel(row.module) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="操作类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="getActionType(row.action)">
              {{ getActionLabel(row.action) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="操作描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column prop="ip" label="IP地址" width="140" />
        <el-table-column prop="status" label="操作结果" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'success' ? 'success' : 'danger'">
              {{ row.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="duration" label="耗时(ms)" width="100" align="right" />
        <el-table-column prop="createdAt" label="操作时间" width="180" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
      />
    </el-card>

    <el-dialog
      v-model="detailVisible"
      title="日志详情"
      width="700px"
      destroy-on-close
    >
      <el-descriptions v-if="currentLog" border :column="2">
        <el-descriptions-item label="日志ID" :span="2">
          {{ currentLog.id }}
        </el-descriptions-item>
        <el-descriptions-item label="操作模块">
          <el-tag size="small" :type="getModuleType(currentLog.module)">
            {{ getModuleLabel(currentLog.module) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作类型">
          <el-tag size="small" :type="getActionType(currentLog.action)">
            {{ getActionLabel(currentLog.action) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作描述" :span="2">
          {{ currentLog.description }}
        </el-descriptions-item>
        <el-descriptions-item label="操作人">
          {{ currentLog.username }}
        </el-descriptions-item>
        <el-descriptions-item label="操作结果">
          <el-tag size="small" :type="currentLog.status === 'success' ? 'success' : 'danger'">
            {{ currentLog.status === 'success' ? '成功' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="IP地址">
          {{ currentLog.ip }}
        </el-descriptions-item>
        <el-descriptions-item label="User-Agent" :span="2">
          {{ currentLog.userAgent }}
        </el-descriptions-item>
        <el-descriptions-item label="请求方法" label-width="100px">
          <el-tag size="small" type="info">{{ currentLog.method }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="请求URL">
          {{ currentLog.url }}
        </el-descriptions-item>
        <el-descriptions-item label="请求参数" :span="2">
          <pre class="code-pre">{{ currentLog.params }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="响应结果" :span="2" v-if="currentLog.result">
          <pre class="code-pre">{{ currentLog.result }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2" v-if="currentLog.errorMessage">
          <pre class="code-pre error">{{ currentLog.errorMessage }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ currentLog.duration }} ms
        </el-descriptions-item>
        <el-descriptions-item label="操作时间">
          {{ currentLog.createdAt }}
        </el-descriptions-item>
      </el-descriptions>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// TODO F-H1: 将直接 request 调用迁移到 api/ 模块

import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Delete } from '@element-plus/icons-vue'
import { exportData } from '@/utils/export'
import { get, del } from '@/utils/request'
import type { PageResult } from '@/types'

interface AuditLog {
  id: string
  module: string
  action: string
  description: string
  username: string
  ip: string
  status: string
  duration: number
  createdAt: string
  userAgent?: string
  method?: string
  url?: string
  params?: string
  result?: string
  errorMessage?: string
}

const loading = ref(false)
const detailVisible = ref(false)
const currentLog = ref<AuditLog | null>(null)
const logCount = ref<number | null>(null)

const filters = reactive({
  actionType: '',
  module: '',
  username: '',
  dateRange: [] as Date[]
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  total: 1234
})

const tableData = ref<AuditLog[]>([])

const moduleMap: Record<string, { label: string; type: string }> = {
  graph: { label: '代码图谱', type: 'primary' },
  scan: { label: '扫描任务', type: 'warning' },
  report: { label: '报告管理', type: 'success' },
  system: { label: '系统设置', type: 'info' },
  user: { label: '用户管理', type: 'danger' },
  default: { label: '其他', type: 'info' }
}

const actionMap: Record<string, { label: string; type: string }> = {
  create: { label: '新增', type: 'success' },
  update: { label: '修改', type: 'warning' },
  delete: { label: '删除', type: 'danger' },
  query: { label: '查询', type: 'info' },
  export: { label: '导出', type: 'primary' },
  login: { label: '登录', type: 'success' },
  logout: { label: '登出', type: 'info' },
  default: { label: '其他', type: 'info' }
}

function getModuleType(module: string): string {
  return moduleMap[module]?.type || moduleMap.default.type
}

function getModuleLabel(module: string): string {
  return moduleMap[module]?.label || moduleMap.default.label
}

function getActionType(action: string): string {
  return actionMap[action]?.type || actionMap.default.type
}

function getActionLabel(action: string): string {
  return actionMap[action]?.label || actionMap.default.label
}

async function loadData() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      pageNum: pagination.page,
      pageSize: pagination.pageSize
    }
    if (filters.actionType) params.actionType = filters.actionType
    if (filters.module) params.module = filters.module
    if (filters.username) params.username = filters.username
    if (filters.dateRange && filters.dateRange.length === 2) {
      params.startTime = filters.dateRange[0].toISOString()
      params.endTime = filters.dateRange[1].toISOString()
    }

    const res = await get<PageResult<AuditLog>>('/lg/audit/list', params)
    tableData.value = res.list
    pagination.total = res.total
  } catch (e) {
    ElMessage.error('获取操作日志失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.page = 1
  loadData()
}

function handleReset() {
  filters.actionType = ''
  filters.module = ''
  filters.username = ''
  filters.dateRange = []
  pagination.page = 1
  handleSearch()
}

function handleDetail(row: AuditLog) {
  currentLog.value = row
  detailVisible.value = true
}

async function handleExport() {
  try {
    await ElMessageBox.confirm('确定要导出选中的日志吗？', '确认导出', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'info'
    })

    const columns = [
      { key: 'id', title: '日志ID' },
      { key: 'module', title: '操作模块', formatter: (v: string) => getModuleLabel(v) },
      { key: 'action', title: '操作类型', formatter: (v: string) => getActionLabel(v) },
      { key: 'description', title: '操作描述' },
      { key: 'username', title: '操作人' },
      { key: 'ip', title: 'IP地址' },
      { key: 'status', title: '操作结果', formatter: (v: string) => v === 'success' ? '成功' : '失败' },
      { key: 'duration', title: '耗时(ms)' },
      { key: 'createdAt', title: '操作时间' }
    ]

    await exportData(tableData.value, columns, {
      filename: `操作日志_${new Date().toISOString().slice(0, 10)}`,
      format: 'excel'
    })
  } catch {
    ElMessage.info('已取消导出')
  }
}

async function loadStats() {
  try {
    const res = await get<{ count: number }>('/lg/audit/stats/count')
    logCount.value = res.count ?? null
  } catch {
    // 静默失败
  }
}

async function handleClear() {
  try {
    await ElMessageBox.confirm('确定要清空所有操作日志吗？此操作不可恢复！', '确认清空', {
      confirmButtonText: '确定清空',
      cancelButtonText: '取消',
      type: 'warning' as const
    })
    await del('/lg/audit/clear')
    ElMessage.success('日志已清空')
    logCount.value = 0
    await loadData()
  } catch {
    // cancelled
  }
}

onMounted(() => {
  loadData()
  loadStats()
})
</script>

<style scoped>
.audit-log-page {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.search-form {
  margin-bottom: 16px;
}

.stat-alert {
  margin-bottom: 16px;
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

.code-pre {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
}

.code-pre.error {
  background: #fef0f0;
  color: #f56c6c;
}
</style>
