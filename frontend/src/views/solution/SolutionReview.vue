<template>
  <div
    v-loading="loading"
    class="solution-review">
    <!-- 无项目上下文时的引导态 -->
    <el-empty
      v-if="!projectId && !loading"
      description="暂无项目上下文，请从项目详情页进入方案评审">
    </el-empty>

    <!-- 方案列表（推荐方案 / 备选方案） -->
    <el-card
      v-for="(sol, sIdx) in solutions"
      :key="sol.id || sIdx"
      class="solution-card">
      <template #header>
        <div class="card-header">
          <div class="card-title">
            <el-tag
              :type="sol._kind === '推荐方案' ? 'success' : 'info'"
              effect="dark"
              size="small">
              {{ sol._kind }}
            </el-tag>
            <span class="solution-title">{{ sol.title || `方案 ${sIdx + 1}` }}</span>
          </div>
          <div class="header-right">
            <el-tag
              v-if="sol.status"
              size="small">{{ sol.status }}</el-tag>
            <el-button
              type="primary"
              size="small"
              :loading="verifyingId === sol.id"
              :disabled="!sol.id"
              @click="handleVerify(sol)">
              <el-icon><CircleCheck /></el-icon>
              验证方案
            </el-button>
          </div>
        </div>
      </template>

      <!-- 摘要 -->
      <div
        v-if="sol.summary"
        class="solution-summary">
        {{ sol.summary }}
      </div>

      <!-- 方案级证据 -->
      <div
        v-if="sol.evidenceIds && sol.evidenceIds.length"
        class="evidence-block">
        <div class="section-subtitle">方案证据</div>
        <div class="evidence-chips">
          <div
            v-for="eid in sol.evidenceIds"
            :key="eid"
            class="evidence-chip">
            <div
              class="chip-head"
              @click="toggleEvidence(eid)">
              <span class="chip-id mono-text">{{ eid }}</span>
              <span class="chip-toggle">{{ expandedEvidence[eid] ? '收起' : '展开' }}</span>
            </div>
            <div
              v-if="expandedEvidence[eid]"
              class="chip-body">
              <span
                v-if="fetchingEvidence[eid] && !evidenceCache[eid]"
                class="chip-loading">加载中...</span>
              <template v-else-if="evidenceCache[eid]">
                <div class="chip-row">
                  <span class="chip-label">来源：</span>
                  <span>{{ evidenceCache[eid].sourceName || '-' }}</span>
                </div>
                <div class="chip-row">
                  <span class="chip-label">类型：</span>
                  <span>{{ evidenceCache[eid].evidenceType || '-' }}</span>
                </div>
                <div class="chip-content">
                  {{ evidenceCache[eid].content || evidenceCache[eid].summary || '-' }}
                </div>
              </template>
              <span
                v-else
                class="chip-empty">暂无证据详情</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 文件级步骤 -->
      <div class="section-subtitle">文件级实施步骤</div>
      <el-table
        v-if="sol.steps && sol.steps.length"
        :data="sol.steps"
        border
        stripe
        style="width: 100%">
        <el-table-column
          prop="title"
          label="步骤"
          min-width="160" />
        <el-table-column
          prop="actionType"
          label="操作"
          width="100"
          align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="actionTagType(row.actionType)">
              {{ row.actionType || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="filePath"
          label="文件路径"
          min-width="220">
          <template #default="{ row }">
            <span class="mono-text">{{ row.filePath || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="symbolName"
          label="符号"
          min-width="150">
          <template #default="{ row }">
            <span class="mono-text">{{ row.symbolName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="详情"
          width="90"
          align="center">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="openStep(sol.id || String(sIdx), row)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无实施步骤" />

      <!-- 步骤详情抽屉 -->
      <el-drawer
        v-model="stepDrawerVisible"
        :title="currentStep?.title || '步骤详情'"
        size="480px"
        direction="rtl">
        <div
          v-if="currentStep"
          class="step-detail">
          <div
            v-if="currentStep.description"
            class="detail-block">
            <div class="detail-label">描述</div>
            <div class="detail-value">{{ currentStep.description }}</div>
          </div>
          <div class="detail-grid">
            <div class="detail-block">
              <div class="detail-label">操作类型</div>
              <div class="detail-value">
                <el-tag size="small">{{ currentStep.actionType || '-' }}</el-tag>
              </div>
            </div>
            <div class="detail-block">
              <div class="detail-label">文件路径</div>
              <div class="detail-value mono-text">{{ currentStep.filePath || '-' }}</div>
            </div>
            <div class="detail-block">
              <div class="detail-label">符号名</div>
              <div class="detail-value mono-text">{{ currentStep.symbolName || '-' }}</div>
            </div>
          </div>
          <div
            v-if="currentStep.testDescription"
            class="detail-block">
            <div class="detail-label">测试说明</div>
            <div class="detail-value">{{ currentStep.testDescription }}</div>
          </div>
          <div
            v-if="currentStep.rollbackDescription"
            class="detail-block">
            <div class="detail-label">回滚说明</div>
            <div class="detail-value">{{ currentStep.rollbackDescription }}</div>
          </div>
          <div class="detail-block">
            <div class="code-snippet-header">
              <div class="detail-label">代码片段</div>
              <div class="code-snippet-actions">
                <el-tag
                  v-if="currentStep.codeLanguage"
                  size="small"
                  type="info"
                  effect="plain">
                  {{ currentStep.codeLanguage }}
                </el-tag>
                <el-button
                  v-if="currentStep.codeSnippet"
                  link
                  type="primary"
                  size="small"
                  @click="copyCode(currentStep.codeSnippet)">
                  <el-icon><DocumentCopy /></el-icon>
                  复制代码
                </el-button>
              </div>
            </div>
            <pre
              v-if="currentStep.codeSnippet"
              class="code-snippet"><code>{{ currentStep.codeSnippet }}</code></pre>
            <el-empty
              v-else
              :image-size="60"
              description="暂无代码片段" />
          </div>
          <div
            v-if="currentStep.evidenceIds && currentStep.evidenceIds.length"
            class="detail-block">
            <div class="detail-label">关联证据</div>
            <div class="evidence-chips">
              <div
                v-for="eid in currentStep.evidenceIds"
                :key="eid"
                class="evidence-chip">
                <div
                  class="chip-head"
                  @click="toggleEvidence(eid)">
                  <span class="chip-id mono-text">{{ eid }}</span>
                  <span class="chip-toggle">{{ expandedEvidence[eid] ? '收起' : '展开' }}</span>
                </div>
                <div
                  v-if="expandedEvidence[eid]"
                  class="chip-body">
                  <span
                    v-if="fetchingEvidence[eid] && !evidenceCache[eid]"
                    class="chip-loading">加载中...</span>
                  <template v-else-if="evidenceCache[eid]">
                    <div class="chip-row">
                      <span class="chip-label">来源：</span>
                      <span>{{ evidenceCache[eid].sourceName || '-' }}</span>
                    </div>
                    <div class="chip-row">
                      <span class="chip-label">类型：</span>
                      <span>{{ evidenceCache[eid].evidenceType || '-' }}</span>
                    </div>
                    <div class="chip-content">
                      {{ evidenceCache[eid].content || evidenceCache[eid].summary || '-' }}
                    </div>
                  </template>
                  <span
                    v-else
                    class="chip-empty">暂无证据详情</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-drawer>

      <!-- 校验错误 -->
      <el-alert
        v-if="getVerifyErrors(sol).length > 0"
        type="error"
        show-icon
        :closable="false"
        class="verify-alert">
        <template #title>方案校验未通过（{{ getVerifyErrors(sol).length }} 项错误）</template>
        <ul class="verify-error-list">
          <li
            v-for="(err, i) in getVerifyErrors(sol)"
            :key="i">
            {{ err }}
          </li>
        </ul>
      </el-alert>
      <el-alert
        v-else-if="sol.verificationPassed === true"
        type="success"
        show-icon
        :closable="false"
        class="verify-alert">
        <template #title>方案校验通过</template>
      </el-alert>
    </el-card>

    <el-empty
      v-if="!loading && solutions.length === 0"
      description="未找到方案">
      <el-button
        v-if="!solutionId && projectId"
        type="primary"
        @click="goToRequirements">
        前往需求分析
      </el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, DocumentCopy } from '@element-plus/icons-vue'
import { solutionApi, factApi } from '@/api'
import type { Solution, SolutionStep, SolutionVerifyResult } from '@/api'
import type { Evidence } from '@/types'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.projectId as string)
const solutionId = computed(() => route.params.solutionId as string)

const loading = ref(false)
const verifyingId = ref<string | null>(null)
const solution = ref<Solution | null>(null)
/** 按项目加载的方案列表（无 solutionId 时使用） */
const solutionList = ref<Solution[]>([])

/**
 * 归一化方案对象：后端返回 solutionId，前端统一映射为 id。
 */
function normalizeSolution(s: Solution): Solution {
  const raw = s as any
  return { ...s, id: raw.solutionId || raw.id }
}

/** 步骤详情抽屉 */
const stepDrawerVisible = ref(false)
const currentStep = ref<SolutionStep | null>(null)

/** 证据缓存：evidenceId -> Evidence 详情 */
const evidenceCache = ref<Record<string, Evidence>>({})
/** 正在拉取详情的证据 ID 集合 */
const fetchingEvidence = ref<Record<string, boolean>>({})
/** 已展开的证据 ID 集合 */
const expandedEvidence = ref<Record<string, boolean>>({})

interface DisplaySolution extends Solution {
  _kind: string
}

/** 把后端返回的方案归一化为展示列表（推荐 / 备选） */
const solutions = computed<DisplaySolution[]>(() => {
  // 按项目加载的方案列表
  if (solutionList.value.length > 0) {
    return solutionList.value.map((s, i) => ({
      ...s,
      _kind: i === 0 ? '推荐方案' : '备选方案'
    }))
  }

  const s = solution.value
  if (!s) return []
  const list: DisplaySolution[] = []
  const recommended = (s as any).recommended as Solution | undefined
  const alternatives = (s as any).alternatives as Solution[] | undefined
  const solutionArray = (s as any).solutions as Solution[] | undefined

  if (recommended || (alternatives && alternatives.length)) {
    if (recommended) list.push({ ...recommended, _kind: '推荐方案' })
    ;(alternatives || []).forEach(a => list.push({ ...a, _kind: '备选方案' }))
    return list
  }
  if (Array.isArray(solutionArray)) {
    solutionArray.forEach((a, i) =>
      list.push({ ...a, _kind: i === 0 ? '推荐方案' : '备选方案' })
    )
    return list
  }
  list.push({ ...s, _kind: '当前方案' })
  return list
})

function actionTagType(action?: string) {
  switch ((action || '').toUpperCase()) {
    case 'CREATE':
      return 'success'
    case 'UPDATE':
      return 'warning'
    case 'DELETE':
      return 'danger'
    default:
      return 'info'
  }
}

function getVerifyErrors(sol: Solution): string[] {
  return sol.verificationErrors || []
}

function openStep(_solId: string, step: SolutionStep) {
  currentStep.value = step
  stepDrawerVisible.value = true
}

async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('代码已复制到剪贴板')
  } catch (e) {
    console.error('copy code failed:', e)
    ElMessage.error('复制失败，请手动复制')
  }
}

async function handleVerify(sol: Solution) {
  if (!sol.id) return
  verifyingId.value = sol.id
  try {
    const res: SolutionVerifyResult = await solutionApi.verify(sol.id)
    // 合并校验结果回写到当前方案对象
    sol.verificationPassed = res.verificationPassed
    sol.verificationErrors = res.verificationErrors || sol.verificationErrors
    if (res.verificationPassed) {
      ElMessage.success('方案校验通过')
    } else {
      ElMessage.warning(`方案校验未通过，共 ${(res.verificationErrors || []).length} 项错误`)
    }
  } catch (e) {
    console.error(e)
  } finally {
    verifyingId.value = null
  }
}

/**
 * 加载数据：有 solutionId 时加载指定方案，否则按 projectId 加载方案列表。
 */
async function loadData() {
  // 切换模式时清理旧数据
  solution.value = null
  solutionList.value = []

  if (solutionId.value) {
    // 有 solutionId：加载指定方案详情
    loading.value = true
    try {
      const data = await solutionApi.get(solutionId.value)
      solution.value = normalizeSolution(data)
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  } else if (projectId.value) {
    // 有 projectId 但无 solutionId：加载项目方案列表
    loading.value = true
    try {
      const data = await solutionApi.listByProject(projectId.value)
      solutionList.value = (data || []).map(normalizeSolution)
    } catch (e) {
      console.error(e)
    } finally {
      loading.value = false
    }
  }
}

function goToRequirements() {
  router.push(`/projects/${projectId.value}/requirements`)
}

/** 拉取证据详情 */
async function fetchEvidence(evidenceId: string) {
  if (evidenceCache.value[evidenceId] || fetchingEvidence.value[evidenceId]) return
  fetchingEvidence.value[evidenceId] = true
  try {
    const data = await factApi.getEvidence(projectId.value, evidenceId)
    evidenceCache.value[evidenceId] = data
  } catch (e) {
    console.error('fetch evidence failed:', e)
  } finally {
    fetchingEvidence.value[evidenceId] = false
  }
}

function toggleEvidence(evidenceId: string) {
  const next = !expandedEvidence.value[evidenceId]
  expandedEvidence.value[evidenceId] = next
  if (next) fetchEvidence(evidenceId)
}

onMounted(loadData)

// 路由参数变化时重新加载（如从方案列表跳转到具体方案）
watch(solutionId, () => {
  loadData()
})
</script>

<style scoped>
.solution-review {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.solution-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.solution-summary {
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  padding: 10px 14px;
  border-radius: 6px;
  margin-bottom: 16px;
  white-space: pre-wrap;
}

.section-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin: 16px 0 10px;
}

.evidence-block {
  margin-bottom: 8px;
}

.mono-text {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.verify-alert {
  margin-top: 16px;
}

.verify-error-list {
  margin: 8px 0 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

/* 步骤详情抽屉 */
.step-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.detail-value {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

/* 代码片段 */
.code-snippet-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.code-snippet-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.code-snippet {
  margin: 6px 0 0;
  padding: 12px;
  background: var(--el-fill-color-darker);
  color: var(--el-text-color-primary);
  font-family: monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  max-height: 400px;
  overflow-y: auto;
}

.code-snippet code {
  font-family: inherit;
}
</style>

<!-- 证据卡片的非 scoped 样式（render 函数生成的 DOM 不受 scoped 限制） -->
<style>
.evidence-chips {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-chip {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
  background: var(--el-fill-color-lighter);
}

.chip-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s;
}

.chip-head:hover {
  background: var(--el-fill-color);
}

.chip-id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chip-toggle {
  font-size: 12px;
  color: var(--el-color-primary);
}

.chip-body {
  padding: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.chip-row {
  font-size: 12px;
  margin-bottom: 6px;
  color: var(--el-text-color-regular);
}

.chip-label {
  color: var(--el-text-color-secondary);
  margin-right: 4px;
}

.chip-content {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  margin-top: 4px;
}

.chip-loading,
.chip-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
