<template>
  <div class="vector-search-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>向量语义检索</span>
          <el-tag
            type="info"
            size="small">
            基于 pgvector 的语义相似度检索
          </el-tag>
        </div>
      </template>

      <el-form
        :model="form"
        label-width="100px"
        @submit.prevent="handleSearch">
        <el-form-item label="查询文本">
          <el-input
            v-model="form.query"
            type="textarea"
            :rows="3"
            placeholder="输入要检索的自然语言描述，例如：用户登录验证逻辑"
          />
        </el-form-item>
        <el-form-item label="版本">
          <el-select
            v-model="form.versionId"
            placeholder="选择版本"
            style="width: 300px;">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="v.versionName || v.id"
              :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="块类型">
          <el-select
            v-model="form.chunkType"
            clearable
            placeholder="全部"
            style="width: 200px;">
            <el-option
              label="代码 (CODE)"
              value="CODE" />
            <el-option
              label="文档 (DOC)"
              value="DOC" />
            <el-option
              label="注释 (COMMENT)"
              value="COMMENT" />
          </el-select>
        </el-form-item>
        <el-form-item label="Top K">
          <el-slider
            v-model="form.topK"
            :min="1"
            :max="50"
            show-input />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="handleSearch">
            <el-icon><Search /></el-icon>
            检索
          </el-button>
          <el-button @click="handleFindSimilarNodes">查找相似节点</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 检索结果 -->
    <el-card
      v-if="results.length > 0"
      style="margin-top: 16px;">
      <template #header>
        <span>检索结果 ({{ results.length }} 条)</span>
      </template>
      <el-table
        :data="results"
        border
        stripe>
        <el-table-column
          prop="chunkType"
          label="类型"
          width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.chunkType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="sourceUri"
          label="来源"
          min-width="200"
          show-overflow-tooltip />
        <el-table-column
          prop="content"
          label="内容"
          min-width="300"
          show-overflow-tooltip />
        <el-table-column
          prop="embeddingModel"
          label="模型"
          width="150" />
        <el-table-column
          prop="createdAt"
          label="创建时间"
          width="170">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 相似节点对话框 -->
    <el-dialog
      v-model="similarDialogVisible"
      title="相似节点"
      width="600px">
      <el-form label-width="100px">
        <el-form-item label="节点名称">
          <el-input
            v-model="similarForm.nodeName"
            placeholder="输入节点名称" />
        </el-form-item>
        <el-form-item label="相似度阈值">
          <el-slider
            v-model="similarForm.threshold"
            :min="0"
            :max="1"
            :step="0.05"
            show-input />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="similarLoading"
            @click="doFindSimilarNodes">
            查找
          </el-button>
        </el-form-item>
      </el-form>
      <el-table
        v-if="similarNodes.length > 0"
        :data="similarNodes"
        border
        size="small"
        style="margin-top: 12px;">
        <el-table-column
          prop="name"
          label="节点名称" />
        <el-table-column
          prop="type"
          label="类型"
          width="120" />
        <el-table-column
          prop="file"
          label="文件"
          show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { vectorApi } from '@/api/vector.api'
import { graphApi } from '@/api'
import dayjs from 'dayjs'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const results = ref<any[]>([])
const similarDialogVisible = ref(false)
const similarLoading = ref(false)
const similarNodes = ref<any[]>([])

const versions = ref<any[]>([])
const form = reactive({
  query: '',
  chunkType: '',
  topK: 10,
  versionId: '',
})

onMounted(async () => {
  try {
    const res = await graphApi.getScanVersions(projectId)
    versions.value = res?.data?.list || res?.list || []
  } catch { /* ignore */ }
})

const similarForm = reactive({
  nodeName: '',
  threshold: 0.85,
})

const formatTime = (time: string) => time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'

async function handleSearch() {
  if (!form.query.trim()) { ElMessage.warning('请输入查询文本'); return }
  loading.value = true
  try {
    results.value = await vectorApi.semanticSearch(projectId, form.versionId, form.query, form.topK, form.chunkType) as any[]
  } catch {
    ElMessage.error('检索失败')
  } finally {
    loading.value = false
  }
}

function handleFindSimilarNodes() {
  similarDialogVisible.value = true
  similarNodes.value = []
}

async function doFindSimilarNodes() {
  if (!similarForm.nodeName.trim()) { ElMessage.warning('请输入节点名称'); return }
  similarLoading.value = true
  try {
    similarNodes.value = await vectorApi.findSimilarNodes(projectId, form.versionId, similarForm.nodeName, similarForm.threshold) as any[]
    ElMessage.success(`找到 ${similarNodes.value.length} 个相似节点`)
  } catch {
    ElMessage.error('查找失败')
  } finally {
    similarLoading.value = false
  }
}
</script>

<style scoped>
.vector-search-page { padding: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
