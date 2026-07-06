<template>
  <div class="cross-repo-impact">
    <div class="page-header">
      <h3>
        <el-icon><Share /></el-icon>
        跨仓库影响分析
      </h3>
      <p class="header-desc">展示跨仓库之间的依赖链路和影响传播路径</p>
    </div>

    <el-card shadow="hover" class="filter-card">
      <template #header>
        <div class="card-header">
          <span>筛选条件</span>
          <el-button
            type="primary"
            size="small"
            :loading="loading"
            @click="loadImpactChains">
            <el-icon><Refresh /></el-icon>
            加载
          </el-button>
        </div>
      </template>
      <el-form :inline="true">
        <el-form-item label="影响等级">
          <el-select v-model="filterLevel" placeholder="全部" clearable style="width: 150px">
            <el-option label="高" value="HIGH" />
            <el-option label="中" value="MEDIUM" />
            <el-option label="低" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="源仓库">
          <el-input v-model="filterSourceRepo" placeholder="搜索源仓库..." clearable style="width: 200px" />
        </el-form-item>
        <el-form-item label="目标仓库">
          <el-input v-model="filterTargetRepo" placeholder="搜索目标仓库..." clearable style="width: 200px" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 影响统计 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">总链路数</div>
          <div class="stat-value primary">{{ filteredChains.length }}</div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">高影响链路</div>
          <div class="stat-value danger">{{ highImpactCount }}</div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">涉及仓库</div>
          <div class="stat-value success">{{ repoCount }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 影响链路列表 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <span>影响链路</span>
      </template>

      <el-table
        v-loading="loading"
        :data="filteredChains"
        border
        stripe
        empty-text="暂无跨仓库影响链路"
        row-key="id"
        @row-click="expandChain">
        <el-table-column prop="sourceRepo" label="源仓库" width="180" show-overflow-tooltip />
        <el-table-column label="源节点" min-width="200">
          <template #default="{ row }">
            <div>
              <strong>{{ row.sourceNode?.name }}</strong>
              <el-tag size="small" style="margin-left: 8px">{{ row.sourceNode?.type }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目标节点" min-width="200">
          <template #default="{ row }">
            <div>
              <strong>{{ row.targetNode?.name }}</strong>
              <el-tag size="small" style="margin-left: 8px">{{ row.targetNode?.type }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="targetRepo" label="目标仓库" width="180" show-overflow-tooltip />
        <el-table-column prop="impactLevel" label="影响等级" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getImpactType(row.impactLevel)" size="small">
              {{ getImpactText(row.impactLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="链路长度" width="90" align="center">
          <template #default="{ row }">
            {{ row.chain?.length || 0 }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 链路详情展开 -->
    <el-drawer
      v-model="detailDrawerVisible"
      title="影响链路详情"
      size="50%"
      direction="rtl">
      <div v-if="selectedChain" class="chain-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="源仓库">{{ selectedChain.sourceRepo }}</el-descriptions-item>
          <el-descriptions-item label="目标仓库">{{ selectedChain.targetRepo }}</el-descriptions-item>
          <el-descriptions-item label="源节点">{{ selectedChain.sourceNode?.name }}</el-descriptions-item>
          <el-descriptions-item label="目标节点">{{ selectedChain.targetNode?.name }}</el-descriptions-item>
          <el-descriptions-item label="影响等级">
            <el-tag :type="getImpactType(selectedChain.impactLevel)" size="small">
              {{ getImpactText(selectedChain.impactLevel) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="链路长度">{{ selectedChain.chain?.length || 0 }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 24px 0 12px">传播路径</h4>
        <el-steps direction="vertical" :active="(selectedChain.chain?.length || 1) - 1" finish-status="success">
          <el-step
            v-for="(step, idx) in selectedChain.chain"
            :key="idx"
            :title="step.name"
            :description="`${step.type} | ${step.repo}`"
          />
        </el-steps>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Share, Refresh } from '@element-plus/icons-vue'
import { graphifyApi } from '@/api/graphify.api'
import type { CrossRepoImpactChain } from '@/api/graphify.api'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const chains = ref<CrossRepoImpactChain[]>([])
const filterLevel = ref('')
const filterSourceRepo = ref('')
const filterTargetRepo = ref('')
const detailDrawerVisible = ref(false)
const selectedChain = ref<CrossRepoImpactChain | null>(null)

const filteredChains = computed(() => {
  return chains.value.filter((c) => {
    if (filterLevel.value && c.impactLevel !== filterLevel.value) return false
    if (filterSourceRepo.value && !c.sourceRepo?.toLowerCase().includes(filterSourceRepo.value.toLowerCase())) return false
    if (filterTargetRepo.value && !c.targetRepo?.toLowerCase().includes(filterTargetRepo.value.toLowerCase())) return false
    return true
  })
})

const highImpactCount = computed(() => filteredChains.value.filter(c => c.impactLevel === 'HIGH').length)

const repoCount = computed(() => {
  const repos = new Set<string>()
  filteredChains.value.forEach(c => {
    if (c.sourceRepo) repos.add(c.sourceRepo)
    if (c.targetRepo) repos.add(c.targetRepo)
  })
  return repos.size
})

function getImpactType(level: string): string {
  const map: Record<string, string> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
  return map[level] || 'info'
}

function getImpactText(level: string): string {
  const map: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
  return map[level] || level
}

function expandChain(row: CrossRepoImpactChain) {
  selectedChain.value = row
  detailDrawerVisible.value = true
}

async function loadImpactChains() {
  loading.value = true
  try {
    const res: any = await graphifyApi.getCrossRepoImpact(projectId)
    chains.value = res?.list || res?.data?.list || (Array.isArray(res) ? res : [])
  } catch (err) {
    console.error('加载跨仓库影响链路失败:', err)
    ElMessage.error('加载影响链路失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadImpactChains()
})
</script>

<style scoped>
.cross-repo-impact {
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

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-card {
  padding: 16px;
  border-radius: 8px;
  text-align: center;
  border: 1px solid #ebeef5;
  background: #fff;
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

.stat-value.primary {
  color: #409eff;
}

.stat-value.danger {
  color: #f56c6c;
}

.stat-value.success {
  color: #67c23a;
}

.chain-detail {
  padding: 16px;
}
</style>
