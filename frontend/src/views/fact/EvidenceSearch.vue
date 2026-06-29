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
      @size-change="doSearch"
      @current-change="doSearch"
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
          <h4>关联节点 ({{ selectedEvidence.relatedNodeCount || 0 }})</h4>
          <el-tag v-for="node in selectedEvidence.relatedNodeIdList" :key="node" size="small" style="margin-right: 8px; margin-bottom: 8px;">
            {{ node }}
          </el-tag>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { factApi } from '@/api'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

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
  total: 0
})

const evidenceList = ref<any[]>([])

const formatTime = (time: string) => {
  if (!time) return '-'
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getEvidenceTypeColor = (type: string): string => {
  const colorMap: Record<string, string> = {
    FILE_LINE: 'primary',
    SQL_STATEMENT: 'success',
    DB_SCHEMA: 'warning',
    DOC_PARAGRAPH: 'info',
    API_DOC: '',
    TEST_RESULT: 'success',
    AI_REASONING: 'danger'
  }
  return colorMap[type] || 'info'
}

const getEvidenceTypeText = (type: string): string => {
  const textMap: Record<string, string> = {
    FILE_LINE: '代码行',
    SQL_STATEMENT: 'SQL语句',
    DB_SCHEMA: '数据库模式',
    DOC_PARAGRAPH: '文档段落',
    API_DOC: 'API文档',
    TEST_RESULT: '测试结果',
    AI_REASONING: 'AI推理'
  }
  return textMap[type] || type
}

const doSearch = async () => {
  if (!projectId) return
  loading.value = true
  try {
    const params: any = {
      pageNum: pagination.value.page,
      pageSize: pagination.value.pageSize,
    }
    if (filters.value.evidenceType) {
      params.evidenceType = filters.value.evidenceType
    }
    if (filters.value.keyword) {
      params.keyword = filters.value.keyword
    }
    const data: any = await factApi.searchEvidence(projectId, params)
    if (data) {
      if (data.list) {
        evidenceList.value = data.list
        pagination.value.total = data.total || data.list.length
      } else if (Array.isArray(data)) {
        evidenceList.value = data
        pagination.value.total = data.length
      } else {
        evidenceList.value = []
        pagination.value.total = 0
      }
    }
  } catch (e) {
    console.error('搜索证据失败', e)
    evidenceList.value = []
  } finally {
    loading.value = false
  }
}

const viewContent = async (row: any) => {
  selectedEvidence.value = row
  contentVisible.value = true
  // 如果有 ID，尝试加载完整证据详情
  if (row.id && projectId) {
    try {
      const detail: any = await factApi.getEvidence(projectId, row.id)
      if (detail) {
        selectedEvidence.value = { ...row, ...detail }
      }
    } catch (e) {
      console.error('加载证据详情失败', e)
    }
  }
}

const viewRelated = (row: any) => {
  if (row.sourceName) {
    router.push(`/projects/${projectId}/graph/unified?versionId=&keyword=${encodeURIComponent(row.sourceName)}`)
  } else {
    router.push(`/projects/${projectId}/graph/unified`)
  }
}

onMounted(() => {
  if (projectId) {
    doSearch()
  }
})
</script>

<style scoped>
.evidence-search {
  padding: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
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
}

.code-block {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  color: #303133;
  max-height: 500px;
  overflow: auto;
}

.content-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}
</style>
