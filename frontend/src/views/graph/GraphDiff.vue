<template>
  <div class="graph-diff-page">
    <!-- 页面头部：标题 + 内联差异摘要 + 对比操作 -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-title-row">
          <h3>图谱版本对比</h3>
          <div
            v-if="diffResult"
            class="inline-stats">
            <span class="stat-item">
              <span class="stat-num success">{{ diffResult.addedNodes?.length || 0 }}</span>
              <span class="stat-text">新增节点</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num danger">{{ diffResult.removedNodes?.length || 0 }}</span>
              <span class="stat-text">删除节点</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num success">{{ diffResult.addedEdges?.length || 0 }}</span>
              <span class="stat-text">新增边</span>
            </span>
            <span class="stat-sep">|</span>
            <span class="stat-item">
              <span class="stat-num danger">{{ diffResult.removedEdges?.length || 0 }}</span>
              <span class="stat-text">删除边</span>
            </span>
          </div>
        </div>
        <p class="header-desc">跨版本节点/边差异分析</p>
      </div>
    </div>

    <!-- 版本选择表单：去除 el-card，div 布局 -->
    <div class="diff-form">
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
    </div>

    <!-- 差异结果：去除 el-card，使用 panel-section div 布局 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      class="diff-row">
      <el-col :span="12">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">新增节点</span>
            <el-tag
              type="success"
              size="small">
              {{ diffResult.addedNodes?.length || 0 }}
            </el-tag>
          </div>
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
        </div>
      </el-col>
      <el-col :span="12">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">删除节点</span>
            <el-tag
              type="danger"
              size="small">
              {{ diffResult.removedNodes?.length || 0 }}
            </el-tag>
          </div>
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
        </div>
      </el-col>
    </el-row>

    <el-row
      v-if="diffResult"
      :gutter="16"
      class="diff-row">
      <el-col :span="12">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">新增边</span>
            <el-tag
              type="success"
              size="small">
              {{ diffResult.addedEdges?.length || 0 }}
            </el-tag>
          </div>
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
        </div>
      </el-col>
      <el-col :span="12">
        <div class="panel-section">
          <div class="panel-header">
            <span class="panel-title">删除边</span>
            <el-tag
              type="danger"
              size="small">
              {{ diffResult.removedEdges?.length || 0 }}
            </el-tag>
          </div>
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
        </div>
      </el-col>
    </el-row>
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
.graph-diff-page {
  padding: 16px;
}

/* ===== 页面头部 ===== */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.header-title-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.header-left h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-desc {
  margin: 6px 0 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* ===== 行内统计 ===== */
.inline-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.inline-stats .stat-item {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}

.stat-num {
  font-size: 16px;
  font-weight: 600;
  line-height: 1;
}

.stat-num.success {
  color: var(--el-color-success);
}

.stat-num.danger {
  color: var(--el-color-danger);
}

.stat-text {
  color: var(--el-text-color-secondary);
}

.stat-sep {
  color: var(--el-border-color);
}

/* ===== 版本选择表单 ===== */
.diff-form {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-light);
  margin-bottom: 16px;
}

/* ===== 差异结果面板 ===== */
.diff-row {
  margin-bottom: 16px;
}

.panel-section {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-section :deep(.el-table) {
  border: none;
}
</style>
