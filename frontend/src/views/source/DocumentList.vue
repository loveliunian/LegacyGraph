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
            {{ dictLabel('doc_parse_status', row.parseStatus) }}
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

    <!-- Markdown 预览弹窗 -->
    <el-dialog
      v-model="previewVisible"
      :title="previewTitle"
      width="80%"
      top="3vh"
      destroy-on-close
      class="md-preview-dialog"
    >
      <div v-if="previewLoading" class="preview-loading">
        <el-skeleton :rows="10" animated />
      </div>
      <div v-else-if="previewError" class="preview-error">
        <el-result icon="error" title="加载失败" :sub-title="previewError">
          <template #extra>
            <el-button type="primary" @click="retryPreview">重试</el-button>
          </template>
        </el-result>
      </div>
      <div v-else class="markdown-body" v-html="previewHtml" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Document, Guide, Files, Reading } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { sourceApi } from '@/api/source.api'
import { Marked } from 'marked'
import hljs from 'highlight.js'
import { preloadDicts, dictLabel } from '@/utils/dict'

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

// 预览弹窗状态
const previewVisible = ref(false)
const previewTitle = ref('')
const previewLoading = ref(false)
const previewError = ref('')
const previewHtml = ref('')
let lastPreviewRow: any = null

// 初始化 marked，配置代码高亮
const marked = new Marked()
marked.setOptions({
  gfm: true,
  breaks: true
})

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const getDocTypeText = (type: string) => dictLabel('doc_type', type)

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
    PARSE_FAILED: 'danger',
    UPLOADED: 'info',
    DISCOVERED: '',
    PENDING: 'info'
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

const handleUploadSuccess = (_response: any) => {
  ElMessage.success('上传成功')
  loadDocList(1)
}

const handleUploadError = () => {
  // 上传错误已由响应拦截器统一展示
}

/**
 * 判断是否为 Markdown 文件
 */
const isMarkdownFile = (row: any): boolean => {
  const fileType = (row.fileType || '').toUpperCase()
  if (fileType === 'MD') return true
  const docName = (row.docName || '').toLowerCase()
  return docName.endsWith('.md')
}

/**
 * 预览文档
 * - MD 文件：弹窗渲染 Markdown
 * - 其他文件（PDF/DOCX）：新窗口打开
 */
const preview = (row: any) => {
  if (!row.id) {
    ElMessage.warning('无法预览：缺少文档ID')
    return
  }

  if (isMarkdownFile(row)) {
    openMarkdownPreview(row)
  } else {
    const url = `/api/lg/projects/${projectId}/sources/documents/${row.id}/download`
    window.open(url, '_blank')
  }
}

/**
 * 打开 Markdown 预览弹窗
 */
const openMarkdownPreview = async (row: any) => {
  previewVisible.value = true
  previewTitle.value = row.docName || '文档预览'
  previewLoading.value = true
  previewError.value = ''
  previewHtml.value = ''
  lastPreviewRow = row

  try {
    const url = `/api/lg/projects/${projectId}/sources/documents/${row.id}/download`
    const response = await fetch(url)
    if (!response.ok) {
      throw new Error(`请求失败: ${response.status} ${response.statusText}`)
    }
    const markdown = await response.text()

    // 使用 marked 渲染，并手动对代码块做 highlight.js 高亮
    const renderer = new marked.Renderer()
    renderer.code = function ({ text, lang }: { text: string; lang?: string }) {
      const validLang = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
      const highlighted = hljs.highlight(text, { language: validLang }).value
      return `<pre><code class="hljs language-${validLang}">${highlighted}</code></pre>`
    }

    const html = marked.parse(markdown, { renderer }) as string
    previewHtml.value = html
  } catch (err: any) {
    previewError.value = err.message || '加载文档失败'
  } finally {
    previewLoading.value = false
  }
}

/**
 * 重试预览
 */
const retryPreview = () => {
  if (lastPreviewRow) {
    openMarkdownPreview(lastPreviewRow)
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
  } catch {
    row.parseStatus = 'FAILED'
    // 错误消息已由响应拦截器统一展示
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
  } catch {
    // 错误消息已由响应拦截器统一展示
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadDocList(page)
}

onMounted(async () => {
  preloadDicts(['doc_type', 'doc_parse_status'])
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

/* 预览弹窗样式 */
.preview-loading {
  padding: 20px 0;
}

.preview-error {
  padding: 20px 0;
}

/* Markdown 内容渲染样式 */
.markdown-body {
  padding: 16px;
  font-size: 15px;
  line-height: 1.75;
  color: #303133;
}

/* 标题样式 */
.markdown-body :deep(h1) {
  font-size: 28px;
  font-weight: 700;
  margin: 24px 0 16px;
  padding-bottom: 8px;
  border-bottom: 2px solid #e4e7ed;
  color: #1d1d1f;
}

.markdown-body :deep(h2) {
  font-size: 24px;
  font-weight: 600;
  margin: 20px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
  color: #1d1d1f;
}

.markdown-body :deep(h3) {
  font-size: 20px;
  font-weight: 600;
  margin: 18px 0 10px;
  color: #303133;
}

.markdown-body :deep(h4) {
  font-size: 17px;
  font-weight: 600;
  margin: 16px 0 8px;
  color: #303133;
}

.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  font-size: 15px;
  font-weight: 600;
  margin: 12px 0 6px;
  color: #606266;
}

/* 段落 */
.markdown-body :deep(p) {
  margin: 0 0 12px;
}

/* 链接 */
.markdown-body :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

/* 列表 */
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 24px;
  margin: 0 0 12px;
}

.markdown-body :deep(li) {
  margin-bottom: 4px;
}

/* 引用块 */
.markdown-body :deep(blockquote) {
  margin: 0 0 12px;
  padding: 8px 16px;
  border-left: 4px solid #5e5ce6;
  background: #f5f5ff;
  color: #606266;
}

.markdown-body :deep(blockquote p) {
  margin: 4px 0;
}

/* 代码块 */
.markdown-body :deep(pre) {
  margin: 12px 0;
  padding: 16px;
  border-radius: 8px;
  background: #1e1e1e;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #d4d4d4;
  background: transparent;
  padding: 0;
}

/* 行内代码 */
.markdown-body :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f0f0f5;
  color: #e83e8c;
}

.markdown-body :deep(pre code) {
  color: #d4d4d4;
  background: transparent;
  padding: 0;
}

/* 表格 */
.markdown-body :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 10px 14px;
  border: 1px solid #dcdfe6;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f5f7fa;
  font-weight: 600;
  color: #303133;
}

.markdown-body :deep(tr:hover) {
  background: #f5f7fa;
}

/* 图片 */
.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  margin: 12px 0;
}

/* 水平线 */
.markdown-body :deep(hr) {
  margin: 20px 0;
  border: none;
  border-top: 1px solid #ebeef5;
}

/* 任务列表 */
.markdown-body :deep(input[type='checkbox']) {
  margin-right: 6px;
}

/* 选中文本高亮 */
.markdown-body :deep(mark) {
  background: #fff3cd;
  color: #856404;
  padding: 1px 4px;
  border-radius: 2px;
}
</style>
