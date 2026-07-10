<template>
  <div class="graphify-diff-view">
    <div class="page-header">
      <h3>
        <el-icon><DataAnalysis /></el-icon>
        Graphify 版本差异
      </h3>
      <p class="header-desc">展示两个导入版本之间的节点和边变化</p>
    </div>

    <section class="content-section">
      <h4 class="section-title">选择对比版本</h4>
      <el-form :inline="true">
        <el-form-item label="旧版本">
          <el-select
            v-model="oldVersionId"
            placeholder="选择旧版本"
            style="width: 280px"
            filterable>
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNo || v.id} - ${v.branchName || ''}`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="新版本">
          <el-select
            v-model="newVersionId"
            placeholder="选择新版本"
            style="width: 280px"
            filterable>
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`${v.versionNo || v.id} - ${v.branchName || ''}`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            :disabled="!oldVersionId || !newVersionId"
            @click="loadDiff">
            <el-icon><DataAnalysis /></el-icon>
            对比差异
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <!-- 差异摘要 -->
    <div
      v-if="diffResult"
      class="summary-row">
      <div class="stat-card success">
        <div class="stat-label">新增节点</div>
        <div class="stat-value">{{ diffResult.addedNodes?.length || 0 }}</div>
      </div>
      <div class="stat-card danger">
        <div class="stat-label">移除节点</div>
        <div class="stat-value">{{ diffResult.removedNodes?.length || 0 }}</div>
      </div>
      <div class="stat-card success">
        <div class="stat-label">新增边</div>
        <div class="stat-value">{{ diffResult.addedEdges?.length || 0 }}</div>
      </div>
      <div class="stat-card danger">
        <div class="stat-label">移除边</div>
        <div class="stat-value">{{ diffResult.removedEdges?.length || 0 }}</div>
      </div>
    </div>

    <!-- 节点变化 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      class="content-row">
      <el-col :span="12">
        <section class="content-section">
          <div class="section-header">
            <span class="success-text">+ 新增节点</span>
            <el-tag type="success" size="small">{{ diffResult.addedNodes?.length || 0 }}</el-tag>
          </div>
          <el-table
            :data="diffResult.addedNodes"
            border
            size="small"
            max-height="400"
            empty-text="无新增节点">
            <el-table-column prop="name" label="名称" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-col>
      <el-col :span="12">
        <section class="content-section">
          <div class="section-header">
            <span class="danger-text">- 移除节点</span>
            <el-tag type="danger" size="small">{{ diffResult.removedNodes?.length || 0 }}</el-tag>
          </div>
          <el-table
            :data="diffResult.removedNodes"
            border
            size="small"
            max-height="400"
            empty-text="无移除节点">
            <el-table-column prop="name" label="名称" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-col>
    </el-row>

    <!-- 边变化 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      class="content-row">
      <el-col :span="12">
        <section class="content-section">
          <div class="section-header">
            <span class="success-text">+ 新增边</span>
            <el-tag type="success" size="small">{{ diffResult.addedEdges?.length || 0 }}</el-tag>
          </div>
          <el-table
            :data="diffResult.addedEdges"
            border
            size="small"
            max-height="400"
            empty-text="无新增边">
            <el-table-column prop="from" label="From" show-overflow-tooltip />
            <el-table-column prop="to" label="To" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-col>
      <el-col :span="12">
        <section class="content-section">
          <div class="section-header">
            <span class="danger-text">- 移除边</span>
            <el-tag type="danger" size="small">{{ diffResult.removedEdges?.length || 0 }}</el-tag>
          </div>
          <el-table
            :data="diffResult.removedEdges"
            border
            size="small"
            max-height="400"
            empty-text="无移除边">
            <el-table-column prop="from" label="From" show-overflow-tooltip />
            <el-table-column prop="to" label="To" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-col>
    </el-row>

    <el-empty
      v-if="!diffResult && !loading"
      description="请选择两个版本进行对比" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { DataAnalysis } from '@element-plus/icons-vue'
import { graphApi } from '@/api'
import { graphifyApi } from '@/api/graphify.api'
import type { GraphifyDiffResult } from '@/api/graphify.api'

interface ScanVersion {
  id: string
  versionNo?: string
  branchName?: string
}

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const versions = ref<ScanVersion[]>([])
const oldVersionId = ref('')
const newVersionId = ref('')
const diffResult = ref<GraphifyDiffResult | null>(null)

async function loadVersions() {
  try {
    const res: any = await graphApi.getScanVersions(projectId)
    versions.value = res?.data?.list || res?.list || (Array.isArray(res) ? res : [])
  } catch (err) {
    console.error('加载版本列表失败:', err)
  }
}

async function loadDiff() {
  if (!oldVersionId.value || !newVersionId.value) {
    ElMessage.warning('请选择两个版本')
    return
  }
  if (oldVersionId.value === newVersionId.value) {
    ElMessage.warning('请选择不同的版本进行对比')
    return
  }
  loading.value = true
  try {
    const res: any = await graphifyApi.getDiff(projectId, oldVersionId.value, newVersionId.value)
    diffResult.value = res
    ElMessage.success('差异对比完成')
  } catch (err) {
    console.error('差异对比失败:', err)
    ElMessage.error('差异对比失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadVersions()
})
</script>

<style scoped>
.graphify-diff-view {
  padding: 16px;
}

.page-header {
  margin-bottom: 16px;
}

.page-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-desc {
  margin: 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.content-section {
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color-overlay);
  margin-bottom: 16px;
}

.section-title {
  margin: 0 0 16px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.content-row {
  margin-bottom: 16px;
}

.content-row .content-section {
  margin-bottom: 0;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  padding: 16px;
  border-radius: 8px;
  text-align: center;
  border: 1px solid var(--el-border-color);
}

.stat-card.success {
  border-color: var(--el-color-success-light-7);
  background: var(--el-color-success-light-9);
}

.stat-card.danger {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
}

.stat-card.success .stat-value {
  color: var(--el-color-success);
}

.stat-card.danger .stat-value {
  color: var(--el-color-danger);
}

.success-text {
  color: var(--el-color-success);
  font-weight: 600;
}

.danger-text {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
