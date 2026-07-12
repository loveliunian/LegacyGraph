<template>
  <div class="cross-repo-impact">
    <div class="page-header">
      <h3>
        <el-icon><Share /></el-icon>
        跨仓库影响分析
      </h3>
      <p class="header-desc">展示跨仓库之间的依赖链路和影响传播路径</p>
    </div>

    <el-tabs v-model="activeTab" class="cross-repo-tabs">
      <el-tab-pane label="影响链路" name="chains">
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
      </el-tab-pane>

      <!-- H26: 差异对比 Tab -->
      <el-tab-pane label="差异对比" name="diff">
        <el-card shadow="hover" class="diff-card">
          <template #header>
            <div class="card-header">
              <span>Graphify vs LegacyGraph 节点对齐</span>
              <el-button
                type="primary"
                size="small"
                :loading="diffLoading"
                @click="loadDiff">
                <el-icon><Refresh /></el-icon>
                对比
              </el-button>
            </div>
          </template>
          <el-form :inline="true" style="margin-bottom: 12px">
            <el-form-item label="Graph JSON 路径">
              <el-input
                v-model="diffGraphJsonPath"
                placeholder="/path/to/graphify-graph.json"
                style="width: 400px" />
            </el-form-item>
            <el-form-item label="版本 ID">
              <el-input
                v-model="diffVersionId"
                placeholder="可选"
                style="width: 200px" />
            </el-form-item>
          </el-form>

          <el-empty v-if="!diffReport" description="点击对比按钮开始差异分析" />

          <template v-else>
            <el-row :gutter="16">
              <el-col :span="6">
                <div class="diff-stat">
                  <div class="diff-stat-label">对齐率</div>
                  <div class="diff-stat-value primary">
                    {{ (diffReport.alignmentRate * 100).toFixed(1) }}%
                  </div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="diff-stat">
                  <div class="diff-stat-label">Graphify 节点</div>
                  <div class="diff-stat-value">{{ diffReport.totalInGraphify }}</div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="diff-stat">
                  <div class="diff-stat-label">Legacy 节点</div>
                  <div class="diff-stat-value">{{ diffReport.totalInLegacy }}</div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="diff-stat">
                  <div class="diff-stat-label">已匹配</div>
                  <div class="diff-stat-value success">{{ diffReport.matched }}</div>
                </div>
              </el-col>
            </el-row>

            <el-row :gutter="16" style="margin-top: 16px">
              <el-col :span="12">
                <h4 class="diff-section-title">Graphify 缺失（Legacy 有）</h4>
                <el-table
                  :data="diffReport.missingInGraphify"
                  border
                  stripe
                  max-height="400"
                  empty-text="无缺失">
                  <el-table-column prop="nodeKey" label="nodeKey" width="200" show-overflow-tooltip />
                  <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
                  <el-table-column prop="type" label="类型" width="120" />
                </el-table>
              </el-col>
              <el-col :span="12">
                <h4 class="diff-section-title">Legacy 缺失（Graphify 有）</h4>
                <el-table
                  :data="diffReport.missingInLegacy"
                  border
                  stripe
                  max-height="400"
                  empty-text="无缺失">
                  <el-table-column prop="nodeKey" label="nodeKey" width="200" show-overflow-tooltip />
                  <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
                  <el-table-column prop="type" label="类型" width="120" />
                </el-table>
              </el-col>
            </el-row>
          </template>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Share, Refresh } from '@element-plus/icons-vue'
import { graphifyApi } from '@/api/graphify.api'
import type { CrossRepoImpactChain, CrossRepoDiffReport } from '@/api/graphify.api'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const chains = ref<CrossRepoImpactChain[]>([])
const filterLevel = ref('')
const filterSourceRepo = ref('')
const filterTargetRepo = ref('')
const detailDrawerVisible = ref(false)
const selectedChain = ref<CrossRepoImpactChain | null>(null)

// H26: 差异对比 Tab 状态
const activeTab = ref('chains')
const diffLoading = ref(false)
const diffReport = ref<CrossRepoDiffReport | null>(null)
const diffGraphJsonPath = ref('')
const diffVersionId = ref('')

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

// H26: 加载差异对比
async function loadDiff() {
  if (!diffGraphJsonPath.value) {
    ElMessage.warning('请输入 Graph JSON 路径')
    return
  }
  diffLoading.value = true
  try {
    const res: any = await graphifyApi.getCrossRepoDiff(
      projectId,
      diffGraphJsonPath.value,
      diffVersionId.value || undefined
    )
    diffReport.value = res?.data || res
  } catch (err) {
    console.error('加载差异对比失败:', err)
    ElMessage.error('加载差异对比失败')
  } finally {
    diffLoading.value = false
  }
}
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

/* H26: 差异对比样式 */
.cross-repo-tabs {
  margin-top: 16px;
}

.diff-card .card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.diff-stat {
  text-align: center;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
}

.diff-stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.diff-stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.diff-stat-value.primary {
  color: #409eff;
}

.diff-stat-value.success {
  color: #67c23a;
}

.diff-section-title {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #606266;
}
</style>
