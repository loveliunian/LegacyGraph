<template>
  <div class="database-list">
    <div class="page-header">
      <h3>数据库连接配置</h3>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        添加连接
      </el-button>
    </div>

    <el-table :data="dbList" v-loading="loading" border stripe>
      <el-table-column prop="connectionName" label="连接名称" width="180">
        <template #default="{ row }">
          <div class="db-name">
        <el-icon><Coin /></el-icon>
            <span>{{ row.connectionName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="dbType" label="数据库类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" type="primary">{{ row.dbType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="地址" width="200">
        <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
      </el-table-column>
      <el-table-column prop="database" label="数据库名" width="150" />
      <el-table-column prop="schema" label="Schema" width="120" />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column label="表数量" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.tableCount" size="small" type="success">{{ row.tableCount }}</el-tag>
          <span v-else class="text-gray">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column label="最近扫描" width="180">
        <template #default="{ row }">
          <span v-if="row.lastScanTime">{{ formatTime(row.lastScanTime) }}</span>
          <span v-else class="text-gray">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="testConnection(row)">测试连接</el-button>
          <el-button type="success" link size="small" @click="scanSchema(row)">扫描表结构</el-button>
          <el-button type="danger" link size="small" @click="deleteDb(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper" v-if="total > 0">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="() => loadDbList(1)"
      />
    </div>

    <el-empty v-if="dbList.length === 0" description="暂无数据库配置" />

    <el-dialog v-model="createDialogVisible" title="添加数据库连接" width="600px">
      <el-form :model="dbForm" label-width="120px">
        <el-form-item label="连接名称" required>
          <el-input v-model="dbForm.connectionName" placeholder="请输入连接名称" />
        </el-form-item>
        <el-form-item label="数据库类型" required>
          <el-select v-model="dbForm.dbType" placeholder="选择数据库类型">
            <el-option label="PostgreSQL" value="POSTGRESQL" />
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="Oracle" value="ORACLE" />
            <el-option label="SQL Server" value="SQL_SERVER" />
          </el-select>
        </el-form-item>
        <el-form-item label="Host" required>
          <el-input v-model="dbForm.host" placeholder="localhost / 127.0.0.1" />
        </el-form-item>
        <el-form-item label="Port" required>
          <el-input-number v-model="dbForm.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="数据库名" required>
          <el-input v-model="dbForm.database" placeholder="postgres" />
        </el-form-item>
        <el-form-item label="Schema">
          <el-input v-model="dbForm.schema" placeholder="public" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="dbForm.username" placeholder="postgres" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="dbForm.password" type="password" placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="包含表">
          <el-input v-model="dbForm.includeTables" type="textarea" placeholder="%user%, %order%" />
        </el-form-item>
        <el-form-item label="排除表">
          <el-input v-model="dbForm.excludeTables" type="textarea" placeholder="flyway_%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createDb">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Coin } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { sourceApi } from '@/api/source.api'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const createDialogVisible = ref(false)
const dbList = ref<any[]>([])

const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const dbForm = reactive({
  connectionName: '',
  dbType: 'POSTGRESQL' as 'POSTGRESQL' | 'MYSQL' | 'ORACLE' | 'SQL_SERVER',
  host: 'localhost',
  port: 5432,
  database: '',
  schema: 'public',
  username: '',
  password: '',
  includeTables: '',
  excludeTables: ''
})

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    SUCCESS: 'success',
    FAILED: 'danger',
    UNKNOWN: 'info'
  }
  return map[status] || 'info'
}

const getStatusText = (status: string) => dictLabel('db_status', status)

const showCreateDialog = () => {
  Object.assign(dbForm, {
    connectionName: '',
    dbType: 'POSTGRESQL',
    host: 'localhost',
    port: 5432,
    database: '',
    schema: 'public',
    username: '',
    password: '',
    includeTables: '',
    excludeTables: ''
  })
  createDialogVisible.value = true
}

const createDb = async () => {
  if (!dbForm.connectionName || !dbForm.host || !dbForm.database || !dbForm.username) {
    ElMessage.warning('请填写必填项')
    return
  }
  try {
    const payload = {
      connectionName: dbForm.connectionName,
      dbType: dbForm.dbType,
      host: dbForm.host,
      port: dbForm.port,
      databaseName: dbForm.database,
      schemaName: dbForm.schema || undefined,
      username: dbForm.username,
      password: dbForm.password,
      includeTables: dbForm.includeTables || undefined,
      excludeTables: dbForm.excludeTables || undefined
    }
    await sourceApi.createDbConnection(projectId, payload)
    ElMessage.success('添加成功')
    createDialogVisible.value = false
    await loadDbList()
  } catch {
    // 错误消息已由响应拦截器统一展示
  }
}

const testConnection = async (row: any) => {
  try {
    ElMessage.info('正在测试连接...')
    if (row.id) {
      const res = await sourceApi.testDbConnection(projectId, row.id)
      if (res?.success) {
        row.status = 'SUCCESS'
        ElMessage.success('连接成功')
      } else {
        row.status = 'FAILED'
        ElMessage.error('连接失败: ' + (res?.message || ''))
      }
    }
  } catch {
    row.status = 'FAILED'
    // 错误消息已由响应拦截器统一展示
  }
}

const scanSchema = async (row: any) => {
  try {
    ElMessage.info('开始扫描表结构...')
    // 调用后端扫描表结构接口
    if (row.id) {
      const res = await sourceApi.scanDbSchema(projectId, row.id)
      row.tableCount = res?.tableCount || 0
    }
    row.lastScanTime = new Date().toISOString()
    ElMessage.success(`扫描完成，共发现 ${row.tableCount} 张表`)
  } catch {
    // 错误消息已由响应拦截器统一展示
  }
}

const deleteDb = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除连接 ${row.connectionName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await sourceApi.deleteDbConnection(projectId, row.id)
    ElMessage.success('删除成功')
    await loadDbList()
  } catch {
    // cancelled
  }
}

const loadDbList = async (page?: number) => {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const res = await sourceApi.listDbConnections(projectId, { pageNum: pageNum.value, pageSize: pageSize.value })
    dbList.value = res.list
    total.value = res.total
  } catch {
    // 错误消息已由响应拦截器统一展示
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadDbList(page)
}

onMounted(async () => {
  preloadDicts(['db_status'])
  await loadDbList()
})
</script>

<style scoped>
.database-list {
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

.db-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.db-name .el-icon {
  font-size: 18px;
  color: #f5576c;
}

.text-gray {
  color: #909399;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
