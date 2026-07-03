<template>
  <div class="graph-diff-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>图谱版本对比</span>
          <el-tag
            type="info"
            size="small">
            跨版本节点/边差异分析
          </el-tag>
        </div>
      </template>

      <el-form
        :inline="true"
        label-width="100px">
        <el-form-item label="基准版本">
          <el-select
            v-model="form.baseVersionId"
            placeholder="选择基准版本"
            style="width: 250px;">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNo} - ${v.branchName || ''}`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对比版本">
          <el-select
            v-model="form.targetVersionId"
            placeholder="选择对比版本"
            style="width: 250px;">
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNo} - ${v.branchName || ''}`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="handleDiff">
            对比
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 差异结果 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      style="margin-top: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>新增节点 ({{ diffResult.addedNodes?.length || 0 }})</span>
          </template>
          <el-table
            :data="diffResult.addedNodes"
            border
            size="small"
            max-height="400"
            empty-text="无">
            <el-table-column
              prop="name"
              label="节点名称" />
            <el-table-column
              prop="type"
              label="类型"
              width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>删除节点 ({{ diffResult.removedNodes?.length || 0 }})</span>
          </template>
          <el-table
            :data="diffResult.removedNodes"
            border
            size="small"
            max-height="400"
            empty-text="无">
            <el-table-column
              prop="name"
              label="节点名称" />
            <el-table-column
              prop="type"
              label="类型"
              width="120" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row
      v-if="diffResult"
      :gutter="16"
      style="margin-top: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>新增边 ({{ diffResult.addedEdges?.length || 0 }})</span>
          </template>
          <el-table
            :data="diffResult.addedEdges"
            border
            size="small"
            max-height="400"
            empty-text="无">
            <el-table-column
              prop="from"
              label="From" />
            <el-table-column
              prop="to"
              label="To" />
            <el-table-column
              prop="type"
              label="类型"
              width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>删除边 ({{ diffResult.removedEdges?.length || 0 }})</span>
          </template>
          <el-table
            :data="diffResult.removedEdges"
            border
            size="small"
            max-height="400"
            empty-text="无">
            <el-table-column
              prop="from"
              label="From" />
            <el-table-column
              prop="to"
              label="To" />
            <el-table-column
              prop="type"
              label="类型"
              width="120" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 统计摘要 -->
    <el-card
      v-if="diffResult"
      style="margin-top: 16px;">
      <template #header><span>差异摘要</span></template>
      <el-descriptions
        :column="4"
        border>
        <el-descriptions-item label="新增节点">
          <el-tag type="success">{{ diffResult.addedNodes?.length || 0 }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="删除节点">
          <el-tag type="danger">{{ diffResult.removedNodes?.length || 0 }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="新增边">
          <el-tag type="success">{{ diffResult.addedEdges?.length || 0 }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="删除边">
          <el-tag type="danger">{{ diffResult.removedEdges?.length || 0 }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { graphApi } from '@/api'
import type { DiffResult, ScanVersion } from '@/types'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const versions = ref<ScanVersion[]>([])
const diffResult = ref<DiffResult | null>(null)

const form = reactive({
  baseVersionId: '',
  targetVersionId: '',
})

async function loadVersions() {
  try {
    const res = await graphApi.getScanVersions(projectId)
    versions.value = res?.data?.list || res?.list || []
  } catch { /* ignore */ }
}

async function handleDiff() {
  if (!form.baseVersionId || !form.targetVersionId) {
    ElMessage.warning('请选择基准版本和对比版本')
    return
  }
  if (form.baseVersionId === form.targetVersionId) {
    ElMessage.warning('请选择不同的版本')
    return
  }
  loading.value = true
  try {
    const res = await graphApi.getGraphDiff(projectId, form.baseVersionId, form.targetVersionId)
    diffResult.value = res
    ElMessage.success('对比完成')
  } catch {
    ElMessage.error('对比失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => { loadVersions() })
</script>

<style scoped>
.graph-diff-page { padding: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
