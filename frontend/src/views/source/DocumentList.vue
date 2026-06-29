<template>
  <div class="document-list">
    <div class="page-header">
      <h3>文档资料</h3>
      <el-upload
        :action="uploadUrl"
        :headers="uploadHeaders"
        :show-file-list="false"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :before-upload="beforeUpload"
      >
        <el-button type="primary">
          <el-icon><Upload /></el-icon>
          上传文档
        </el-button>
      </el-upload>
    </div>

    <el-table :data="docList" v-loading="loading" border stripe>
      <el-table-column prop="docName" label="文档名称" width="200">
        <template #default="{ row }">
          <div class="doc-name">
            <el-icon :class="getDocIconClass(row.docType)">
              <component :is="getDocIcon(row.docType)" />
            </el-icon>
            <span>{{ row.docName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="docType" label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ getDocTypeText(row.docType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fileType" label="文件类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.fileType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fileSize" label="文件大小" width="100">
        <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="uploader" label="上传人" width="120" />
      <el-table-column prop="uploadedAt" label="上传时间" width="180">
        <template #default="{ row }">{{ formatTime(row.uploadedAt) }}</template>
      </el-table-column>
      <el-table-column prop="parseStatus" label="解析状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getParseStatusType(row.parseStatus)">
            {{ row.parseStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="抽取事实" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.factCount" size="small" type="success">{{ row.factCount }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="preview(row)">预览</el-button>
          <el-button type="success" link size="small" @click="parseDoc(row)" :disabled="row.parseStatus === 'PARSING'">解析</el-button>
          <el-button type="danger" link size="small" @click="deleteDoc(row)">删除</el-button>
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
        @size-change="() => loadDocList(1)"
      />
    </div>

    <el-empty v-if="docList.length === 0" description="暂无文档资料" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Document, Guide, Files, Reading } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { sourceApi } from '@/api/source.api'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const docList = ref<any[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const uploadUrl = `/api/lg/projects/${projectId}/sources/documents/upload`
const uploadHeaders = {
  Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`
}

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const getDocTypeText = (type: string) => {
  const map: Record<string, string> = {
    PRODUCT: '产品文档',
    API: '接口文档',
    MANUAL: '操作手册',
    DB_DESIGN: '数据库设计',
    MIGRATION: '迁移文档'
  }
  return map[type] || type
}

const getDocIcon = (type: string) => {
  const map: Record<string, any> = {
    PRODUCT: Document,
    API: Guide,
    MANUAL: Files,
    DB_DESIGN: Reading,
    MIGRATION: Document
  }
  return map[type] || Document
}

const getDocIconClass = (type: string) => {
  const map: Record<string, string> = {
    PRODUCT: 'product',
    API: 'api',
    MANUAL: 'manual',
    DB_DESIGN: 'db',
    MIGRATION: 'migration'
  }
  return map[type] || ''
}

const getParseStatusType = (status: string): string => {
  const map: Record<string, string> = {
    PARSED: 'success',
    PARSING: 'warning',
    FAILED: 'danger',
    UPLOADED: 'info'
  }
  return map[status] || 'info'
}

const beforeUpload = (file: File) => {
  const allowedTypes = ['application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'text/markdown']
  if (!allowedTypes.includes(file.type) && !file.name.endsWith('.md') && !file.name.endsWith('.docx')) {
    ElMessage.error('只支持 PDF、DOCX、MD 格式')
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }
  return true
}

const handleUploadSuccess = (response: any) => {
  ElMessage.success('上传成功')
  loadDocList(1)
}

const handleUploadError = () => {
  ElMessage.error('上传失败')
}

const preview = (row: any) => {
  if (row.id) {
    const url = `/api/lg/projects/${projectId}/sources/documents/${row.id}/download`
    window.open(url, '_blank')
  } else {
    ElMessage.warning('无法预览：缺少文档ID')
  }
}

const parseDoc = async (row: any) => {
  try {
    row.parseStatus = 'PARSING'
    ElMessage.info('开始解析文档...')
    // 调用后端文档解析接口
    if (row.id) {
      const res = await sourceApi.parseDocument(projectId, row.id)
      row.factCount = res?.factCount || 0
      row.parseStatus = 'PARSED'
      ElMessage.success(`解析完成，共抽取 ${row.factCount} 个事实`)
    } else {
      row.parseStatus = 'PARSED'
      row.factCount = 0
      ElMessage.success('解析完成')
    }
  } catch (error) {
    row.parseStatus = 'FAILED'
    ElMessage.error('解析失败')
  }
}

const deleteDoc = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除文档 ${row.docName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await sourceApi.deleteDocument(projectId, row.id)
    ElMessage.success('删除成功')
    await loadDocList()
  } catch {
    // cancelled
  }
}

const loadDocList = async (page?: number) => {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const result = await sourceApi.listDocuments(projectId, { pageNum: pageNum.value, pageSize: pageSize.value })
    docList.value = result.list
    total.value = result.total
  } catch (error) {
    ElMessage.error('获取文档列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadDocList(page)
}

onMounted(async () => {
  await loadDocList()
})
</script>

<style scoped>
.document-list {
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

.doc-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.doc-name .el-icon {
  font-size: 18px;
}

.doc-name .el-icon.product {
  color: #409eff;
}

.doc-name .el-icon.api {
  color: #67c23a;
}

.doc-name .el-icon.manual {
  color: #e6a23c;
}

.doc-name .el-icon.db {
  color: #f5576c;
}

.doc-name .el-icon.migration {
  color: #909399;
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
