<template>
  <div class="test-run-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>测试执行列表</span>
        </div>
      </template>

      <div class="filter-bar">
        <el-form
          inline
          :model="filterParams">
          <el-form-item label="状态">
            <el-select
              v-model="filterParams.status"
              placeholder="全部"
              clearable>
              <el-option
                label="等待中"
                value="SCHEDULED" />
              <el-option
                label="执行中"
                value="RUNNING" />
              <el-option
                label="已完成"
                value="COMPLETED" />
              <el-option
                label="失败"
                value="FAILED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              @click="loadData">
              搜索
            </el-button>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        border
        style="width: 100%">
        <el-table-column
          prop="id"
          label="执行ID"
          width="180" />
        <el-table-column
          prop="environment"
          label="环境"
          width="100" />
        <el-table-column
          prop="status"
          label="状态"
          width="100"
          align="center">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column
          prop="totalCases"
          label="总用例"
          width="80"
          align="right" />
        <el-table-column
          prop="passedCases"
          label="通过"
          width="80"
          align="right">
          <template #default="{ row }">
            <span :class="{ 'text-success': row.passedCases === row.totalCases }">
              {{ row.passedCases }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="startedAt"
          label="开始时间"
          width="180">
          <template #default="{ row }">{{ formatDate(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column
          label="覆盖率"
          width="120">
          <template #default="{ row }">
            <el-progress
              :percentage="getPercentage(row)"
              :color="getProgressColor(getPercentage(row))"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="220"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              @click="goToDetail(row.id)">
              查看详情
            </el-button>
            <el-button
              v-if="hasFailed(row)"
              link
              size="small"
              type="primary"
              @click="rerunFailed(row.id)"
            >
              重跑失败
            </el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              link
              size="small"
              type="danger"
              @click="cancelRun(row.id)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { testRunApi } from '@/api'
import StatusTag from '@/components/common/StatusTag.vue'
import type { TestRun } from '@/types'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)

const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const pagination = ref({
  pageNum: 1,
  pageSize: 20,
})

const filterParams = ref({
  status: undefined as string | undefined,
})

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return dateStr.replace('T', ' ').split('.')[0]
}

const getPercentage = (row: TestRun) => {
  if (!row.totalCases) return 0
  return Math.round((row.passedCases / row.totalCases) * 100)
}

const getProgressColor = (percentage: number) => {
  if (percentage < 50) return '#f56c6c'
  if (percentage < 80) return '#e6a23c'
  return '#67c23a'
}

function hasFailed(row: TestRun) {
  return row.failedCases > 0 && row.status === 'COMPLETED'
}

async function loadData() {
  loading.value = true
  try {
    const result = await testRunApi.listTestRuns(projectId.value, {
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      status: filterParams.value.status,
    })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    console.error(error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filterParams.value.status = undefined
  pagination.value.pageNum = 1
  loadData()
}

function handleSizeChange(size: number) {
  pagination.value.pageSize = size
  pagination.value.pageNum = 1
  loadData()
}

function handleCurrentChange(page: number) {
  pagination.value.pageNum = page
  loadData()
}

function goToDetail(runId: string) {
  router.push(`/projects/${projectId.value}/test-runs/${runId}`)
}

async function rerunFailed(runId: string) {
  try {
    await testRunApi.rerunFailed(projectId.value, runId)
    ElMessage.success('已重新触发失败用例执行')
    loadData()
  } catch (error) {
    console.error(error)
    ElMessage.error('重跑失败')
  }
}

async function cancelRun(runId: string) {
  try {
    await testRunApi.cancelRun(projectId.value, runId)
    ElMessage.success('测试运行已取消')
    loadData()
  } catch (error) {
    console.error(error)
    ElMessage.error('取消失败')
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.test-run-list {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-bar {
  margin-bottom: 16px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.text-success {
  color: #67c23a;
  font-weight: 500;
}
</style>
