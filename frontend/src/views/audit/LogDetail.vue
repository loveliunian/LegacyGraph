<template>
  <div class="log-detail-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <span>日志详情</span>
          <div class="header-actions">
            <el-button :icon="Download" @click="exportLog">导出</el-button>
          </div>
        </div>
      </template>

      <div class="log-detail" v-if="logDetail">
        <div class="detail-section">
          <h4>基本信息</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="操作ID">{{ logDetail.id }}</el-descriptions-item>
            <el-descriptions-item label="操作类型">
              <el-tag :type="getOperationTypeColor(logDetail.operationType)" size="small">
                {{ logDetail.operationType }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="操作描述">{{ logDetail.description }}</el-descriptions-item>
            <el-descriptions-item label="操作时间">{{ logDetail.createTime }}</el-descriptions-item>
            <el-descriptions-item label="操作人">
              <el-avatar :size="24" style="margin-right: 8px">
                {{ logDetail.operator?.charAt(0) }}
              </el-avatar>
              {{ logDetail.operator }}
            </el-descriptions-item>
            <el-descriptions-item label="IP地址">{{ logDetail.ip }}</el-descriptions-item>
            <el-descriptions-item label="执行耗时">
              <el-tag :type="logDetail.duration > 1000 ? 'warning' : 'info'" size="small">
                {{ logDetail.duration }}ms
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="操作状态">
              <el-tag :type="logDetail.success ? 'success' : 'danger'" size="small">
                {{ logDetail.success ? '成功' : '失败' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <el-divider />

        <div class="detail-section">
          <h4>请求信息</h4>
          <div class="code-wrapper">
            <div class="request-line">
              <span class="method-badge" :class="logDetail.method?.toLowerCase()">
                {{ logDetail.method }}
              </span>
              <span class="request-url">{{ logDetail.url }}</span>
            </div>
            <CodePreview
              :code="formatJson(logDetail.requestParams)"
              :language="'json'"
              :show-header="false"
            />
          </div>
        </div>

        <el-divider />

        <div class="detail-section">
          <h4>响应信息</h4>
          <div class="code-wrapper">
            <div class="status-line">
              <span class="status-badge" :class="logDetail.success ? 'success' : 'error'">
                {{ logDetail.httpStatus || 200 }}
              </span>
              <span class="status-text">{{ logDetail.success ? 'OK' : 'Error' }}</span>
            </div>
            <CodePreview
              :code="formatJson(logDetail.responseBody)"
              :language="'json'"
              :show-header="false"
            />
          </div>
        </div>

        <el-divider v-if="logDetail.errorMessage" />

        <div class="detail-section" v-if="logDetail.errorMessage">
          <h4>错误信息</h4>
          <el-alert
            title="异常堆栈" type="error" :closable="false" show-icon>
            <pre class="error-stack">{{ logDetail.errorMessage }}</pre>
          </el-alert>
        </div>
      </div>

      <el-empty v-else description="加载中..." />
    </el-card>
  </div>
</template>

<script setup lang="ts">

import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter, useRoute } from 'vue-router'
import { ArrowLeft, Download } from '@element-plus/icons-vue'
import CodePreview from '@/components/code/CodePreview.vue'
import { auditApi } from '@/api'

const router = useRouter()
const route = useRoute()

const logDetail = ref<any>(null)

function getOperationTypeColor(type: string): string {
  const colorMap: Record<string, string> = {
    CREATE: 'success',
    UPDATE: 'warning',
    DELETE: 'danger',
    QUERY: 'info',
    UPLOAD: 'primary',
    DOWNLOAD: 'success',
    EXPORT: 'success',
    IMPORT: 'primary',
    LOGIN: 'primary',
    LOGOUT: 'info'
  }
  return colorMap[type] || 'info'
}

function formatJson(data: any): string {
  if (!data) return '{}'
  if (typeof data === 'string') return data
  return JSON.stringify(data, null, 2)
}

function goBack() {
  router.back()
}

function exportLog() {
  if (!logDetail.value) return
  const dataStr = JSON.stringify(logDetail.value, null, 2)
  const blob = new Blob([dataStr], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `audit-log-${logDetail.value.id || Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出审计日志')
}

onMounted(async () => {
  const logId = route.params.id
  try {
    const data: any = await auditApi.getDetail(logId as string)
    logDetail.value = data
  } catch (err) {
    console.error('获取日志详情失败:', err)
  }
})
</script>

<style scoped>
.log-detail-page {
  padding: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-actions {
  margin-left: auto;
}

.log-detail {
  padding: 10px 0;
}

.detail-section {
  margin-bottom: 20px;
}

.detail-section h4 {
  margin: 0 0 16px 0;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.code-wrapper {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
}

.request-line,
.status-line {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
}

.method-badge,
.status-badge {
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  color: white;
}

.method-badge.get {
  background: #67c23a;
}

.method-badge.post {
  background: #409eff;
}

.method-badge.put {
  background: #e6a23c;
}

.method-badge.delete {
  background: #f56c6c;
}

.request-url {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #606266;
  word-break: break-all;
}

.status-badge.success {
  background: #67c23a;
}

.status-badge.error {
  background: #f56c6c;
}

.status-text {
  font-size: 13px;
  color: #606266;
}

.error-stack {
  margin: 12px 0 0 0;
  padding: 12px;
  background: #fef0f0;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #f56c6c;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}
</style>
