<template>
  <div class="knowledge-workbench">
    <div class="page-header">
      <h3>
        <el-icon><Collection /></el-icon>
        知识工作台
      </h3>
      <p class="header-desc">查看证据化知识断言（Claim）与知识缺口（GapTask），并对缺口进行解决</p>
    </div>

    <el-tabs v-model="activeTab" class="kb-tabs">
      <!-- 知识断言 -->
      <el-tab-pane label="知识断言" name="claims">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="版本ID">
            <el-input v-model="claimQuery.versionId" placeholder="可选" clearable />
          </el-form-item>
          <el-form-item label="主体类型">
            <el-input v-model="claimQuery.subjectType" placeholder="如 Feature" clearable />
          </el-form-item>
          <el-form-item label="谓词">
            <el-input v-model="claimQuery.predicate" placeholder="如 CALLS" clearable />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="claimQuery.status" placeholder="全部" clearable style="width: 160px">
              <el-option label="待确认" value="PENDING_CONFIRM" />
              <el-option label="已确认" value="CONFIRMED" />
              <el-option label="已拒绝" value="REJECTED" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源">
            <el-input v-model="claimQuery.sourceType" placeholder="如 DOC_AI" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="claimsLoading" @click="loadClaims">
              <el-icon><Search /></el-icon>
              查询
            </el-button>
          </el-form-item>
        </el-form>

        <el-table v-loading="claimsLoading" :data="claims" border stripe empty-text="暂无知识断言">
          <el-table-column prop="subjectType" label="主体类型" width="120" />
          <el-table-column prop="subjectKey" label="主体键" min-width="180" show-overflow-tooltip />
          <el-table-column prop="predicate" label="谓词" width="140" />
          <el-table-column prop="objectType" label="客体类型" width="120" />
          <el-table-column prop="objectKey" label="客体键" min-width="180" show-overflow-tooltip />
          <el-table-column label="置信度" width="100" align="center">
            <template #default="{ row }">
              <span v-if="row.confidence != null">{{ (row.confidence * 100).toFixed(0) }}%</span>
              <span v-else class="text-gray">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="getClaimStatusType(row.status)" size="small">{{ row.status || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceType" label="来源" width="110" />
          <el-table-column prop="createdAt" label="创建时间" width="170">
            <template #default="{ row }">
              <span v-if="row.createdAt">{{ formatTime(row.createdAt) }}</span>
              <span v-else class="text-gray">-</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="claimPageNum"
            v-model:page-size="claimPageSize"
            :total="claimTotal"
            :page-sizes="[20, 50, 100, 200]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleClaimPageSizeChange"
            @current-change="handleClaimPageChange"
          />
        </div>
      </el-tab-pane>

      <!-- 知识缺口 -->
      <el-tab-pane label="知识缺口" name="gaps">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="版本ID">
            <el-input v-model="gapQuery.versionId" placeholder="可选" clearable />
          </el-form-item>
          <el-form-item label="缺口类型">
            <el-input v-model="gapQuery.gapType" placeholder="如 ORPHAN_NODE" clearable />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="gapQuery.status" placeholder="全部" clearable style="width: 160px">
              <el-option label="待处理" value="OPEN" />
              <el-option label="已解决" value="RESOLVED" />
              <el-option label="已忽略" value="IGNORED" />
            </el-select>
          </el-form-item>
          <el-form-item label="严重度">
            <el-select v-model="gapQuery.severity" placeholder="全部" clearable style="width: 140px">
              <el-option label="高" value="HIGH" />
              <el-option label="中" value="MEDIUM" />
              <el-option label="低" value="LOW" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="gapsLoading" @click="loadGaps">
              <el-icon><Search /></el-icon>
              查询
            </el-button>
          </el-form-item>
        </el-form>

        <el-table v-loading="gapsLoading" :data="gaps" border stripe empty-text="暂无知识缺口">
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="gapType" label="类型" width="150" />
          <el-table-column label="严重度" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="getSeverityType(row.severity)" size="small">{{ row.severity || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'RESOLVED' ? 'success' : 'warning'" size="small">{{ row.status || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="subjectType" label="主体类型" width="120" />
          <el-table-column label="优先级" width="90" align="center">
            <template #default="{ row }">
              <span v-if="row.priorityScore != null">{{ Number(row.priorityScore).toFixed(2) }}</span>
              <span v-else class="text-gray">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="suggestedAction" label="建议动作" min-width="200" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="170">
            <template #default="{ row }">
              <span v-if="row.createdAt">{{ formatTime(row.createdAt) }}</span>
              <span v-else class="text-gray">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status !== 'RESOLVED'"
                type="success"
                link
                size="small"
                :loading="row._resolving"
                @click="handleResolve(row)">
                解决
              </el-button>
              <span v-else class="text-gray">已解决</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="gapPageNum"
            v-model:page-size="gapPageSize"
            :total="gapTotal"
            :page-sizes="[20, 50, 100, 200]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleGapPageSizeChange"
            @current-change="handleGapPageChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, Search } from '@element-plus/icons-vue'
import { knowledgeApi } from '@/api/knowledge.api'
import type { KnowledgeClaim, GapTaskView, KnowledgeClaimQuery, GapTaskQuery, PageResult } from '@/api/knowledge.api'
import dayjs from 'dayjs'

type GapRow = GapTaskView & { _resolving?: boolean }

const route = useRoute()
const projectId = route.params.projectId as string

const activeTab = ref<'claims' | 'gaps'>('claims')

// 断言
const claimsLoading = ref(false)
const claims = ref<KnowledgeClaim[]>([])
const claimQuery = reactive<KnowledgeClaimQuery>({ versionId: '', subjectType: '', predicate: '', status: '', sourceType: '' })
const claimPageNum = ref(1)
const claimPageSize = ref(20)
const claimTotal = ref(0)

// 缺口
const gapsLoading = ref(false)
const gaps = ref<GapRow[]>([])
const gapQuery = reactive<GapTaskQuery>({ versionId: '', gapType: '', status: '', severity: '' })
const gapPageNum = ref(1)
const gapPageSize = ref(20)
const gapTotal = ref(0)

function formatTime(time: string): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

function getClaimStatusType(status?: string): string {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING_CONFIRM') return 'warning'
  return 'info'
}

function getSeverityType(severity?: string): string {
  if (severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  if (severity === 'LOW') return 'info'
  return 'info'
}

function cleanParams<T extends Record<string, any>>(p: T): Partial<T> {
  const out: any = {}
  for (const [k, v] of Object.entries(p)) {
    if (v !== '' && v != null) out[k] = v
  }
  return out
}

async function loadClaims() {
  claimsLoading.value = true
  try {
    const params = {
      ...cleanParams(claimQuery),
      pageNum: claimPageNum.value,
      pageSize: claimPageSize.value
    }
    const res: any = await knowledgeApi.listClaims(projectId, params)
    if (res && typeof res === 'object' && 'list' in res) {
      const pageResult = res as PageResult<KnowledgeClaim>
      claims.value = pageResult.list || []
      claimTotal.value = pageResult.total || 0
    } else {
      // 兼容旧接口
      claims.value = Array.isArray(res) ? res : []
      claimTotal.value = claims.value.length
    }
  } catch (err) {
    console.error('加载知识断言失败:', err)
    ElMessage.error('加载知识断言失败')
  } finally {
    claimsLoading.value = false
  }
}

async function loadGaps() {
  gapsLoading.value = true
  try {
    const params = {
      ...cleanParams(gapQuery),
      pageNum: gapPageNum.value,
      pageSize: gapPageSize.value
    }
    const res: any = await knowledgeApi.listGaps(projectId, params)
    let list: GapTaskView[] = []
    if (res && typeof res === 'object' && 'list' in res) {
      const pageResult = res as PageResult<GapTaskView>
      list = pageResult.list || []
      gapTotal.value = pageResult.total || 0
    } else {
      // 兼容旧接口
      list = Array.isArray(res) ? res : []
      gapTotal.value = list.length
    }
    gaps.value = list.map((g) => ({ ...g, _resolving: false }))
  } catch (err) {
    console.error('加载知识缺口失败:', err)
    ElMessage.error('加载知识缺口失败')
  } finally {
    gapsLoading.value = false
  }
}

function handleClaimPageChange(page: number) {
  claimPageNum.value = page
  loadClaims()
}

function handleClaimPageSizeChange(size: number) {
  claimPageSize.value = size
  claimPageNum.value = 1
  loadClaims()
}

function handleGapPageChange(page: number) {
  gapPageNum.value = page
  loadGaps()
}

function handleGapPageSizeChange(size: number) {
  gapPageSize.value = size
  gapPageNum.value = 1
  loadGaps()
}

async function handleResolve(gap: GapRow) {
  try {
    await ElMessageBox.confirm(`确定将缺口「${gap.title}」标记为已解决吗？`, '确认解决', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    gap._resolving = true
    await knowledgeApi.resolveGap(projectId, gap.id)
    ElMessage.success('已标记为解决')
    await loadGaps()
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('解决失败: ' + (err.message || '未知错误'))
    }
  } finally {
    gap._resolving = false
  }
}

onMounted(() => {
  loadClaims()
  loadGaps()
})
</script>

<style scoped>
.knowledge-workbench {
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

.kb-tabs {
  margin-top: 8px;
}

.filter-form {
  margin-bottom: 12px;
}

.text-gray {
  color: #c0c4cc;
}
</style>
