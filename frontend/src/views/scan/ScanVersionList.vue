<template>
  <div class="scan-version-list">
    <div class="page-header">
      <h3>{{ t('menu.scanVersions') }}</h3>
    </div>

    <el-table :data="versionList" v-loading="loading" border stripe>
      <el-table-column prop="versionNumber" label="版本号" width="100" />
      <el-table-column prop="versionName" label="版本名称" width="200" />
      <el-table-column prop="scanType" label="扫描类型" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ getScanTypeText(row.scanType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="nodeCount" label="节点数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.nodeCount" size="small" type="primary">{{ row.nodeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="edgeCount" label="关系数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.edgeCount" size="small" type="warning">{{ row.edgeCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="factCount" label="事实数" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.factCount" size="small" type="success">{{ row.factCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="confidenceAvg" label="平均置信度" width="120">
        <template #default="{ row }">
          <span v-if="row.confidenceAvg">{{ (row.confidenceAvg * 100).toFixed(1) }}%</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180">
        <template #default="{ row }">
          <span v-if="row.createdAt">{{ formatTime(row.createdAt) }}</span>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="120" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">查看详情</el-button>
          <el-button type="warning" link size="small" @click="compareWithPrevious(row)">对比</el-button>
          <el-button type="danger" link size="small" @click="deleteVersion(row)">删除</el-button>
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
        @size-change="() => loadVersionList(1)"
      />
    </div>

    <el-empty v-if="versionList.length === 0 && !loading" description="暂无扫描版本" />

    <!-- 版本详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="版本详情" width="700px" append-to-body>
      <el-descriptions :column="2" border v-if="currentVersion">
        <el-descriptions-item label="版本号">{{ currentVersion.versionNumber }}</el-descriptions-item>
        <el-descriptions-item label="版本名称">{{ currentVersion.versionName }}</el-descriptions-item>
        <el-descriptions-item label="扫描类型">{{ getScanTypeText(currentVersion.scanType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="getStatusType(currentVersion.status)">{{ currentVersion.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="节点数">{{ currentVersion.nodeCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="关系数">{{ currentVersion.edgeCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="事实数">{{ currentVersion.factCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="平均置信度">{{ currentVersion.confidenceAvg ? (currentVersion.confidenceAvg * 100).toFixed(1) + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(currentVersion.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ currentVersion.createdBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ currentVersion.description || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="goToGraph(currentVersion)">查看图谱</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { t } from '@/locales'
import { get, del } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const versionList = ref<any[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const detailDialogVisible = ref(false)
const currentVersion = ref<any>(null)

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getScanTypeText = (type: string) => {
  const map: Record<string, string> = {
    CODE: '代码扫描',
    DATABASE: '数据库扫描',
    DOCUMENT: '文档扫描',
    FULL: '全量扫描'
  }
  return map[type] || type
}

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    PENDING: 'info',
    PROCESSING: 'warning',
    COMPLETED: 'success',
    FAILED: 'danger'
  }
  return map[status] || 'info'
}

const viewDetail = (row: any) => {
  currentVersion.value = row
  detailDialogVisible.value = true
}

const compareWithPrevious = (row: any) => {
  // 跳转到统一图谱比较两个版本
  detailDialogVisible.value = false
  router.push(`/projects/${projectId}/graph/unified?versionId=${row.id}`)
}

const deleteVersion = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除版本 ${row.versionNumber} - ${row.versionName} 吗？此操作不可恢复。`, '提示', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await del(`/lg/projects/${projectId}/scan-versions/${row.id}`)
    ElMessage.success('版本已删除')
    await loadVersionList()
  } catch {
    // cancelled
  }
}

const goToGraph = (version: any) => {
  detailDialogVisible.value = false
  router.push(`/projects/${projectId}/graph/unified?versionId=${version.id}`)
}

const loadVersionList = async (page?: number) => {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const res = await get(`/lg/projects/${projectId}/scan-versions`, {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    versionList.value = res.list || []
    total.value = res.total || 0
  } catch (err) {
    console.error('获取扫描版本列表失败:', err)
    ElMessage.error('获取扫描版本列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadVersionList(page)
}

onMounted(async () => {
  await loadVersionList()
})
</script>

<style scoped>
.scan-version-list {
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

.text-gray {
  color: #909399;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
