<template>
  <div class="agent-history">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Agent 运行历史</span>
          <div>
            <el-select
              v-model="filter.agentType"
              clearable
              placeholder="全部类型"
              size="small"
              style="width: 150px; margin-right: 8px;">
              <el-option
                v-for="t in AGENT_TYPES"
                :key="t"
                :label="t"
                :value="t" />
            </el-select>
            <el-select
              v-model="filter.status"
              clearable
              placeholder="全部状态"
              size="small"
              style="width: 120px; margin-right: 8px;">
              <el-option
                label="成功"
                value="SUCCESS" />
              <el-option
                label="失败"
                value="FAILED" />
              <el-option
                label="运行中"
                value="RUNNING" />
            </el-select>
            <el-button
              size="small"
              @click="loadHistory">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row
        :gutter="12"
        style="margin-bottom: 16px;">
        <el-col :span="6">
          <el-statistic
            title="总运行次数"
            :value="stats.total" />
        </el-col>
        <el-col :span="6">
          <el-statistic
            title="成功率"
            :value="stats.successRate"
            suffix="%" />
        </el-col>
        <el-col :span="6">
          <el-statistic
            title="平均耗时"
            :value="stats.avgDuration"
            suffix="s" />
        </el-col>
        <el-col :span="6">
          <el-statistic
            title="今日运行"
            :value="stats.todayCount" />
        </el-col>
      </el-row>

      <!-- 运行记录表 -->
      <el-table
        v-loading="loading"
        :data="history"
        border
        stripe
        size="small"
        empty-text="暂无运行记录">
        <el-table-column
          prop="id"
          label="ID"
          width="100"
          show-overflow-tooltip />
        <el-table-column
          prop="agentType"
          label="Agent 类型"
          width="130" />
        <el-table-column
          prop="status"
          label="状态"
          width="90"
          align="center">
          <template #default="{ row }">
            <el-tag
              :type="statusTag(row.status)"
              size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="耗时"
          width="80">
          <template #default="{ row }">
            {{ row.durationMs ? (row.durationMs / 1000).toFixed(1) + 's' : '-' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="input"
          label="输入摘要"
          min-width="200"
          show-overflow-tooltip />
        <el-table-column
          prop="createdAt"
          label="时间"
          width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          fixed="right">
          <template #default="{ row }">
            <el-button
              link
              size="small"
              type="primary"
              @click="viewDetail(row)">
              详情
            </el-button>
            <el-button
              v-if="row.status === 'FAILED'"
              link
              size="small"
              type="warning"
              @click="replay(row)">
              重放
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      title="运行详情"
      width="700px"
      destroy-on-close>
      <template v-if="currentRun">
        <el-descriptions
          :column="2"
          border
          size="small">
          <el-descriptions-item label="ID">{{ currentRun.id }}</el-descriptions-item>
          <el-descriptions-item label="Agent 类型">{{ currentRun.agentType }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag
              :type="statusTag(currentRun.status)"
              size="small">
              {{ currentRun.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ currentRun.durationMs ? (currentRun.durationMs / 1000).toFixed(1) + 's' : '-' }}</el-descriptions-item>
          <el-descriptions-item
            label="时间"
            :span="2">
            {{ formatTime(currentRun.createdAt) }}
          </el-descriptions-item>
        </el-descriptions>
        <div
          v-if="currentRun.input"
          style="margin-top: 12px;">
          <h4>输入</h4>
          <pre class="code-block">{{ JSON.stringify(currentRun.input, null, 2) }}</pre>
        </div>
        <div
          v-if="currentRun.output"
          style="margin-top: 12px;">
          <h4>输出</h4>
          <pre class="code-block">{{ typeof currentRun.output === 'string' ? currentRun.output : JSON.stringify(currentRun.output, null, 2) }}</pre>
        </div>
        <div
          v-if="currentRun.error"
          style="margin-top: 12px;">
          <h4>错误信息</h4>
          <el-alert
            :title="currentRun.error"
            type="error"
            :closable="false"
            show-icon />
        </div>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="currentRun?.status === 'FAILED'"
          type="warning"
          @click="replay(currentRun!)">
          重新运行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api'
import dayjs from 'dayjs'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const history = ref<any[]>([])
const detailVisible = ref(false)
const currentRun = ref<any>(null)

const AGENT_TYPES = ['sql', 'test', 'review', 'refactor', 'migration', 'pr', 'failure', 'change', 'report']

const filter = reactive({ agentType: '', status: '' })

const stats = computed(() => {
  const total = history.value.length
  const success = history.value.filter(r => r.status === 'SUCCESS').length
  const today = history.value.filter(r => dayjs(r.createdAt).isSame(dayjs(), 'day')).length
  const durations = history.value.filter(r => r.durationMs).map(r => r.durationMs)
  const avgMs = durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : 0
  return {
    total,
    successRate: total ? Math.round(success / total * 100) : 0,
    avgDuration: (avgMs / 1000).toFixed(1),
    todayCount: today,
  }
})

const statusTag = (s: string) => {
  const m: Record<string, string> = { SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning' }
  return m[s] || 'info'
}

const formatTime = (t: string) => t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'

async function loadHistory() {
  loading.value = true
  try {
    // 从后端 API 获取
    try {
      const res = await agentApi.getRunHistory(projectId, {
        agentType: filter.agentType || undefined,
        status: filter.status || undefined,
        limit: 100,
      })
      const runs = res?.list || []
      if (Array.isArray(runs) && runs.length > 0) {
        history.value = runs.map((run: any) => ({
          ...run,
          status: run.status || 'SUCCESS',
        }))
        return
      }
    } catch { /* fallback to empty */ }
    const stored = null
    let runs: any[] = stored ? JSON.parse(stored) : []
    if (filter.agentType) runs = runs.filter(r => r.agentType === filter.agentType)
    if (filter.status) runs = runs.filter(r => r.status === filter.status)
    history.value = runs
  } catch { history.value = [] }
  finally { loading.value = false }
}

function viewDetail(row: any) {
  currentRun.value = row
  detailVisible.value = true
}

async function replay(row: any) {
  try {
    await agentApi.run({ agentType: row.agentType, projectId, params: row.input })
    ElMessage.success('已重新提交')
    await loadHistory()
  } catch {
    ElMessage.error('重放失败')
  }
}

onMounted(() => { loadHistory() })
</script>

<style scoped>
.agent-history { padding: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.code-block { background: #f5f7fa; padding: 12px; border-radius: 4px; overflow-x: auto; font-size: 13px; max-height: 300px; overflow-y: auto; }
</style>
