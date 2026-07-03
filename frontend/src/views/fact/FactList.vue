<template>
  <div class="fact-list">
    <div class="page-header">
      <h3>事实列表</h3>
      <div class="header-actions">
        <el-select
          v-model="filters.factType"
          placeholder="事实类型"
          size="small"
          clearable
          style="width: 140px;">
          <el-option
            label="API接口"
            value="API" />
          <el-option
            label="类定义"
            value="CLASS" />
          <el-option
            label="方法"
            value="METHOD" />
          <el-option
            label="SQL语句"
            value="SQL" />
          <el-option
            label="数据表"
            value="TABLE" />
          <el-option
            label="业务规则"
            value="RULE" />
        </el-select>
        <el-select
          v-model="filters.sourceType"
          placeholder="来源类型"
          size="small"
          clearable
          style="width: 120px;">
          <el-option
            label="代码"
            value="CODE" />
          <el-option
            label="数据库"
            value="DB" />
          <el-option
            label="文档"
            value="DOC" />
          <el-option
            label="AI归纳"
            value="AI" />
          <el-option
            label="测试"
            value="TEST" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          placeholder="搜索事实"
          size="small"
          style="width: 200px;"
          clearable>
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button
          type="primary"
          size="small"
          @click="loadData">
          搜索
        </el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="facts"
      border
      stripe>
      <el-table-column
        prop="factName"
        label="事实名称"
        min-width="200"
        show-overflow-tooltip>
        <template #default="{ row }">
          <div class="fact-name">
            <el-tag
              size="small"
              type="info">
              {{ row.factType }}
            </el-tag>
            <span>{{ row.factName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="sourceType"
        label="来源类型"
        width="100">
        <template #default="{ row }">
          <el-tag size="small">{{ row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="sourcePath"
        label="来源路径"
        min-width="200"
        show-overflow-tooltip />
      <el-table-column
        prop="sourceLine"
        label="行号"
        width="80" />
      <el-table-column
        prop="confidence"
        label="置信度"
        width="120">
        <template #default="{ row }">
          <el-progress
            :percentage="row.confidence * 100"
            :stroke-width="8"
            :show-text="false" />
          <span class="confidence-text">{{ (row.confidence * 100).toFixed(0) }}%</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="relatedNodeCount"
        label="关联节点"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-badge
            :value="row.relatedNodeCount"
            class="item" />
        </template>
      </el-table-column>
      <el-table-column
        prop="createdAt"
        label="创建时间"
        width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="200"
        fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="viewDetail(row)">
            详情
          </el-button>
          <el-button
            type="success"
            link
            size="small"
            @click="viewRelatedNodes(row)">
            关联图谱
          </el-button>
          <el-button
            type="info"
            link
            size="small"
            @click="viewEvidence(row)">
            证据
          </el-button>
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
      @size-change="loadData"
      @current-change="loadData"
    />

    <el-dialog
      v-model="detailVisible"
      title="事实详情"
      width="800px">
      <div
        v-if="selectedFact"
        class="fact-detail">
        <el-descriptions
          border
          :column="2">
          <el-descriptions-item label="事实名称">{{ selectedFact.factName }}</el-descriptions-item>
          <el-descriptions-item label="事实类型">{{ selectedFact.factType }}</el-descriptions-item>
          <el-descriptions-item label="来源类型">{{ selectedFact.sourceType }}</el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress
              :percentage="selectedFact.confidence * 100"
              :stroke-width="8" />
          </el-descriptions-item>
          <el-descriptions-item
            label="来源路径"
            :span="2">
            {{ selectedFact.sourcePath }}
          </el-descriptions-item>
          <el-descriptions-item label="行号">{{ selectedFact.sourceLine }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(selectedFact.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <div
          v-if="selectedFact.contentSummary"
          class="detail-section">
          <h4>内容摘要</h4>
          <div class="code-block">{{ selectedFact.contentSummary }}</div>
        </div>

        <div class="detail-section">
          <h4>关联节点 ({{ relatedNodes.length }})</h4>
          <el-table
            :data="relatedNodes"
            size="small"
            border>
            <el-table-column
              prop="nodeName"
              label="节点名称" />
            <el-table-column
              prop="nodeType"
              label="节点类型"
              width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ row.nodeType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="confidence"
              label="置信度"
              width="100">
              <template #default="{ row }">{{ (row.confidence * 100).toFixed(0) }}%</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { factApi } from '@/api'

const route = useRoute()
const router = useRouter()
const projectId = route.params.projectId as string

const loading = ref(false)
const detailVisible = ref(false)
const selectedFact = ref<any>(null)

const filters = ref({
  factType: '',
  sourceType: '',
  keyword: ''
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 0
})

const facts = ref<any[]>([])
const relatedNodes = ref<any[]>([])

const formatTime = (time: string) => {
  if (!time) return '-'
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const loadData = async () => {
  if (!projectId) return
  loading.value = true
  try {
    const data: any = await factApi.listFacts(projectId, {
      pageNum: pagination.value.page,
      pageSize: pagination.value.pageSize,
      factType: filters.value.factType || undefined,
      sourceType: filters.value.sourceType || undefined,
      minConfidence: undefined,
    })
    // factApi.listFacts 返回的是 PageResult<Fact> 格式
    // 但后端返回的数据结构可能不同，需要适配
    if (data) {
      if (data.list) {
        facts.value = data.list
        pagination.value.total = data.total || data.list.length
      } else if (Array.isArray(data)) {
        facts.value = data
        pagination.value.total = data.length
      } else {
        facts.value = []
        pagination.value.total = 0
      }
    }
  } catch (e) {
    console.error('加载事实列表失败', e)
    facts.value = []
  } finally {
    loading.value = false
  }
}

const viewDetail = async (row: any) => {
  selectedFact.value = row
  detailVisible.value = true
  // 尝试加载关联节点
  if (row.id && projectId) {
    try {
      const related = await factApi.getRelatedNodes(projectId, row.id)
      if (related && Array.isArray(related)) {
        relatedNodes.value = related.map((id: string, _idx: number) => ({
          nodeName: id,
          nodeType: 'UNKNOWN',
          confidence: 0.5,
        }))
      }
    } catch (e) {
      console.error('加载关联节点失败', e)
    }
  }
}

const viewRelatedNodes = (row: any) => {
  // 跳转到统一图谱，展示该事实关联的节点
  if (row.id) {
    router.push(`/projects/${projectId}/graph/unified?versionId=&keyword=${encodeURIComponent(row.factName || '')}`)
  } else {
    router.push(`/projects/${projectId}/graph/unified`)
  }
}

const viewEvidence = (row: any) => {
  // 打开证据检索页面，预填搜索关键词
  if (row.factName) {
    router.push(`/projects/${projectId}/evidence?keyword=${encodeURIComponent(row.factName)}`)
  } else {
    router.push(`/projects/${projectId}/evidence`)
  }
}

onMounted(() => {
  if (projectId) {
    loadData()
  }
})
</script>

<style scoped>
.fact-list {
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

.fact-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.confidence-text {
  font-size: 12px;
  color: #606266;
  margin-left: 8px;
}

.fact-detail {
  padding: 10px 0;
}

.detail-section {
  margin-top: 24px;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
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
}
</style>
