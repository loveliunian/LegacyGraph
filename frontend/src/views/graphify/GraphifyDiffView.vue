<template>
  <div class="graphify-diff-view">
    <div class="page-header">
      <h3>
        <el-icon><DataAnalysis /></el-icon>
        Graphify 版本差异
      </h3>
      <p class="header-desc">展示两个导入版本之间的节点和边变化</p>
    </div>

    <el-card class="version-selector" shadow="hover">
      <template #header>
        <span>选择对比版本</span>
      </template>
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
    </el-card>

    <!-- 差异摘要 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      class="summary-row">
      <el-col :span="6">
        <div class="stat-card success">
          <div class="stat-label">新增节点</div>
          <div class="stat-value">{{ diffResult.addedNodes?.length || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card danger">
          <div class="stat-label">移除节点</div>
          <div class="stat-value">{{ diffResult.removedNodes?.length || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card success">
          <div class="stat-label">新增边</div>
          <div class="stat-value">{{ diffResult.addedEdges?.length || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card danger">
          <div class="stat-label">移除边</div>
          <div class="stat-value">{{ diffResult.removedEdges?.length || 0 }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 节点变化 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="success-text">+ 新增节点</span>
              <el-tag type="success" size="small">{{ diffResult.addedNodes?.length || 0 }}</el-tag>
            </div>
          </template>
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
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="danger-text">- 移除节点</span>
              <el-tag type="danger" size="small">{{ diffResult.removedNodes?.length || 0 }}</el-tag>
            </div>
          </template>
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
        </el-card>
      </el-col>
    </el-row>

    <!-- 边变化 -->
    <el-row
      v-if="diffResult"
      :gutter="16"
      style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="success-text">+ 新增边</span>
              <el-tag type="success" size="small">{{ diffResult.addedEdges?.length || 0 }}</el-tag>
            </div>
          </template>
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
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="danger-text">- 移除边</span>
              <el-tag type="danger" size="small">{{ diffResult.removedEdges?.length || 0 }}</el-tag>
            </div>
          </template>
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
        </el-card>
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
  color: #303133;
}

.header-desc {
  margin: 0;
  font-size: 14px;
  color: #909399;
}

.version-selector {
  margin-bottom: 16px;
}

.summary-row {
  margin-top: 16px;
}

.stat-card {
  padding: 16px;
  border-radius: 8px;
  text-align: center;
  border: 1px solid #ebeef5;
}

.stat-card.success {
  border-color: #e1f3d8;
  background: #f0f9eb;
}

.stat-card.danger {
  border-color: #fde2e2;
  background: #fef0f0;
}

.stat-label {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
}

.stat-card.success .stat-value {
  color: #67c23a;
}

.stat-card.danger .stat-value {
  color: #f56c6c;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.success-text {
  color: #67c23a;
  font-weight: 600;
}

.danger-text {
  color: #f56c6c;
  font-weight: 600;
}
</style>
