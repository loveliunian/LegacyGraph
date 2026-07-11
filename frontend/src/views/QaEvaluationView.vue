<template>
  <div class="qa-evaluation-view">
    <div class="page-header">
      <h2>QA 评测</h2>
      <div class="filter-bar">
        <select v-model="statusFilter" @change="loadCases">
          <option value="">全部用例</option>
          <option value="SMOKE">冒烟集 (SMOKE)</option>
          <option value="GOLDEN">黄金集 (GOLDEN)</option>
        </select>
        <input
          v-model="keywordFilter"
          class="search-input"
          placeholder="按问题/意图搜索"
          @input="applyClientFilter" />
        <button @click="loadAll">刷新</button>
      </div>
    </div>

    <!-- 评测用例列表（含多选） -->
    <section class="section">
      <div class="section-header">
        <h3>评测用例 ({{ filteredCases.length }} / {{ cases.length }})</h3>
        <div class="bulk-actions">
          <span class="selection-hint">已选 {{ selectedIds.size }} 个</span>
          <button :disabled="!pageCases.length" @click="selectAllOnPage">
            全选当前页
          </button>
          <button :disabled="!selectedIds.size" @click="clearSelection">
            清空选择
          </button>
          <button
            :disabled="selectedIds.size < 2"
            class="primary-btn"
            @click="openCompare">
            用例对比 ({{ selectedIds.size }})
          </button>
          <button
            :disabled="!selectedIds.size"
            class="primary-btn"
            @click="openBulkFeedback">
            批量评审 ({{ selectedIds.size }})
          </button>
        </div>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th class="checkbox-col">
              <input
                type="checkbox"
                :checked="isAllOnPageSelected"
                :indeterminate.prop="isSomeOnPageSelected && !isAllOnPageSelected"
                @change="toggleSelectAllOnPage" />
            </th>
            <th>问题</th>
            <th>意图</th>
            <th>状态</th>
            <th>应拒答</th>
            <th>期望实体</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in pageCases"
            :key="c.id"
            :class="{ selected: selectedIds.has(c.id) }">
            <td class="checkbox-col">
              <input
                type="checkbox"
                :checked="selectedIds.has(c.id)"
                @change="toggleSelect(c.id)" />
            </td>
            <td class="question-cell">{{ c.question }}</td>
            <td>{{ c.intent || '-' }}</td>
            <td><span class="status-tag" :class="c.status">{{ c.status }}</span></td>
            <td>{{ c.shouldAbstain ? '是' : '否' }}</td>
            <td>
              <span
                v-for="e in (c.expectedEntities || []).slice(0, 3)"
                :key="e"
                class="tag">{{ e }}</span>
              <span
                v-if="(c.expectedEntities || []).length > 3"
                class="tag more">+{{ (c.expectedEntities || []).length - 3 }}</span>
              <span v-if="!(c.expectedEntities || []).length" class="muted">无</span>
            </td>
            <td>{{ c.createdAt }}</td>
            <td>
              <button @click="goDetail(c.id)">查看详情</button>
            </td>
          </tr>
          <tr v-if="!pageCases.length">
            <td colspan="8" class="empty">
              {{ cases.length ? '当前过滤无匹配用例' : '暂无用例' }}
            </td>
          </tr>
        </tbody>
      </table>
      <!-- 简易分页（每页 20） -->
      <div v-if="filteredCases.length > pageSize" class="pagination">
        <button :disabled="pageIndex === 0" @click="pageIndex--">上一页</button>
        <span>第 {{ pageIndex + 1 }} / {{ totalPages }} 页</span>
        <button :disabled="pageIndex >= totalPages - 1" @click="pageIndex++">下一页</button>
      </div>
    </section>

    <!-- 历史评测运行 -->
    <section class="section">
      <h3>历史评测运行 ({{ evalRuns.length }})</h3>
      <table class="data-table">
        <thead>
          <tr>
            <th>版本</th>
            <th>评测时间</th>
            <th>用例数</th>
            <th>通过</th>
            <th>实体召回</th>
            <th>证据精度</th>
            <th>关键词覆盖</th>
            <th>拒答准确率</th>
            <th>门禁</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, idx) in evalRuns" :key="idx">
            <td>{{ r.versionId || '-' }}</td>
            <td>{{ r.evaluatedAt }}</td>
            <td>{{ r.totalCases }}</td>
            <td>{{ r.passedCases }}</td>
            <td>{{ pct(r.entityRecall) }}</td>
            <td>{{ pct(r.evidencePrecision) }}</td>
            <td>{{ pct(r.requiredKeywordCoverage) }}</td>
            <td>{{ pct(r.abstentionAccuracy) }}</td>
            <td><span :class="r.passed ? 'pass' : 'fail'">{{ r.passed ? '通过' : '未通过' }}</span></td>
          </tr>
          <tr v-if="!evalRuns.length">
            <td colspan="9" class="empty">暂无评测运行记录</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 用例对比模态框 -->
    <div v-if="showCompare" class="modal-mask" @click.self="closeCompare">
      <div class="modal">
        <div class="modal-header">
          <h3>用例对比</h3>
          <button class="close-btn" @click="closeCompare">×</button>
        </div>
        <div class="modal-body compare-grid">
          <div
            v-for="(c, idx) in compareCases"
            :key="c?.id || idx"
            class="compare-panel">
            <div v-if="!c" class="compare-empty">未选择</div>
            <template v-else>
              <div class="compare-title">
                用例 #{{ idx + 1 }} <span class="compare-id">({{ c.id }})</span>
              </div>
              <dl class="kv">
                <dt>问题</dt><dd>{{ c.question }}</dd>
                <dt>意图</dt><dd>{{ c.intent || '-' }}</dd>
                <dt>状态</dt><dd><span class="status-tag" :class="c.status">{{ c.status }}</span></dd>
                <dt>应拒答</dt><dd>{{ c.shouldAbstain ? '是' : '否' }}</dd>
                <dt>期望实体</dt>
                <dd>
                  <span
                    v-for="e in c.expectedEntities || []"
                    :key="e"
                    class="tag">{{ e }}</span>
                  <span v-if="!(c.expectedEntities || []).length" class="muted">无</span>
                </dd>
                <dt>期望关键词</dt>
                <dd>
                  <span
                    v-for="k in c.expectedKeywords || []"
                    :key="k"
                    class="tag">{{ k }}</span>
                  <span v-if="!(c.expectedKeywords || []).length" class="muted">无</span>
                </dd>
              </dl>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 批量评审模态框 -->
    <div v-if="showBulkFeedback" class="modal-mask" @click.self="closeBulkFeedback">
      <div class="modal">
        <div class="modal-header">
          <h3>批量评审（{{ bulkTargetIds.length }} 个用例）</h3>
          <button class="close-btn" @click="closeBulkFeedback">×</button>
        </div>
        <div class="modal-body">
          <div class="bulk-options">
            <label class="radio-label">
              <input type="radio" v-model="bulkType" value="POSITIVE" />
              有用（POSITIVE）
            </label>
            <label class="radio-label">
              <input type="radio" v-model="bulkType" value="NEGATIVE" />
              无用（NEGATIVE）
            </label>
            <label class="radio-label">
              <input type="radio" v-model="bulkType" value="NEUTRAL" />
              中立（NEUTRAL）
            </label>
          </div>
          <textarea
            v-model="bulkNote"
            class="bulk-note"
            rows="3"
            placeholder="批量备注（可选，每条 feedbackType 复用此备注）"
          />
          <div class="bulk-targets">
            <div
              v-for="id in bulkTargetIds"
              :key="id"
              class="bulk-target-row">
              <span class="bulk-target-q">{{ caseLabel(id) }}</span>
              <span
                v-if="bulkResult[id]"
                class="bulk-status"
                :class="bulkResult[id].ok ? 'pass' : 'fail'">
                {{ bulkResult[id].ok ? '✓ 已提交' : '× 失败' }}
              </span>
            </div>
          </div>
          <div class="bulk-actions-row">
            <button @click="closeBulkFeedback">取消</button>
            <button
              class="primary-btn"
              :disabled="!bulkTargetIds.length || bulkSubmitting"
              @click="submitBulk">
              {{ bulkSubmitting ? `提交中 (${bulkProgress}/${bulkTargetIds.length})` : '确认提交' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get, post } from '@/utils/request'

interface QaTestCase {
  id: string
  question: string
  intent?: string
  status: string
  shouldAbstain?: boolean
  expectedEntities?: string[]
  expectedKeywords?: string[]
  createdAt?: string
}

interface QaEvaluationResult {
  versionId?: string
  evaluatedAt?: string
  totalCases: number
  passedCases: number
  entityRecall: number
  evidencePrecision: number
  requiredKeywordCoverage: number
  abstentionAccuracy: number
  passed: boolean
}

const route = useRoute()
const router = useRouter()
const projectId = ref<string>(String(route.params.projectId || ''))

const statusFilter = ref<string>('')
const keywordFilter = ref<string>('')
const cases = ref<QaTestCase[]>([])
const evalRuns = ref<QaEvaluationResult[]>([])

// 多选 + 分页（G-08 §13.3）
const selectedIds = ref<Set<string>>(new Set())
const pageSize = 20
const pageIndex = ref(0)

const filteredCases = computed(() => {
  const kw = keywordFilter.value.trim().toLowerCase()
  if (!kw) return cases.value
  return cases.value.filter((c) => {
    const q = (c.question || '').toLowerCase()
    const intent = (c.intent || '').toLowerCase()
    return q.includes(kw) || intent.includes(kw)
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredCases.value.length / pageSize)))
const pageCases = computed(() => {
  const start = pageIndex.value * pageSize
  return filteredCases.value.slice(start, start + pageSize)
})

const isAllOnPageSelected = computed(() =>
  pageCases.value.length > 0 && pageCases.value.every((c) => selectedIds.value.has(c.id))
)
const isSomeOnPageSelected = computed(() =>
  pageCases.value.some((c) => selectedIds.value.has(c.id))
)

const loadCases = async () => {
  const params = statusFilter.value ? `?status=${statusFilter.value}` : ''
  const res = await get(`/lg/projects/${projectId.value}/qa/cases${params}`)
  cases.value = res || []
  pageIndex.value = 0
  // 重选清空（避免跨状态遗留）
  selectedIds.value = new Set()
}

const loadEvalRuns = async () => {
  const res = await get(`/lg/projects/${projectId.value}/qa/eval-runs`)
  evalRuns.value = res || []
}

const loadAll = () => {
  loadCases()
  loadEvalRuns()
}

const goDetail = (caseId: string) => {
  router.push({ name: 'QaCaseDetail', params: { projectId: projectId.value, caseId } })
}

const applyClientFilter = () => {
  pageIndex.value = 0
}

// ==================== 用例对比 ====================
const showCompare = ref(false)
const compareCases = ref<[QaTestCase | null, QaTestCase | null]>([null, null])

const openCompare = () => {
  const ids = Array.from(selectedIds.value)
  if (ids.length < 2) return
  const a = cases.value.find((c) => c.id === ids[0]) ?? null
  const b = cases.value.find((c) => c.id === ids[1]) ?? null
  compareCases.value = [a, b]
  showCompare.value = true
}

const closeCompare = () => {
  showCompare.value = false
}

// ==================== 批量评审 ====================
const showBulkFeedback = ref(false)
const bulkType = ref<'POSITIVE' | 'NEGATIVE' | 'NEUTRAL'>('POSITIVE')
const bulkNote = ref('')
const bulkSubmitting = ref(false)
const bulkProgress = ref(0)
const bulkResult = ref<Record<string, { ok: boolean; message?: string }>>({})
const bulkTargetIds = computed(() => Array.from(selectedIds.value))

const caseLabel = (id: string) => {
  const c = cases.value.find((x) => x.id === id)
  if (!c) return id
  const q = c.question || ''
  return q.length > 40 ? q.slice(0, 40) + '…' : q
}

const openBulkFeedback = () => {
  if (!selectedIds.value.size) return
  bulkResult.value = {}
  bulkProgress.value = 0
  bulkNote.value = ''
  showBulkFeedback.value = true
}

const closeBulkFeedback = () => {
  if (bulkSubmitting.value) return
  showBulkFeedback.value = false
}

const submitBulk = async () => {
  if (!bulkTargetIds.value.length || bulkSubmitting.value) return
  bulkSubmitting.value = true
  bulkProgress.value = 0
  bulkResult.value = {}
  // 顺序提交，避免并发打爆后端；如有需要可改为 Promise.all 限流并发
  for (const id of bulkTargetIds.value) {
    const target = cases.value.find((c) => c.id === id)
    const body = {
      question: target?.question ?? '',
      answerId: id,
      claimText: target?.question ?? '',
      feedbackType: bulkType.value,
      expectedEvidenceIds: (target?.expectedKeywords ?? []).slice(0, 5),
      feedbackText: bulkNote.value || undefined
    }
    try {
      await post(`/lg/projects/${projectId.value}/qa/feedback`, body)
      bulkResult.value[id] = { ok: true }
    } catch (err: any) {
      bulkResult.value[id] = { ok: false, message: err?.message || '提交失败' }
    }
    bulkProgress.value++
  }
  bulkSubmitting.value = false
  logBulkSummary()
  // 提交完成后保留模态显示结果 1.5s，再清空选择
  setTimeout(() => {
    showBulkFeedback.value = false
    selectedIds.value = new Set()
  }, 1500)
}

const logBulkSummary = () => {
  const ok = Object.values(bulkResult.value).filter((r) => r.ok).length
  const total = bulkTargetIds.value.length
  console.info(`[QaEvaluation bulk] submitted ${ok}/${total} feedback (${bulkType.value})`)
}

// ==================== 多选辅助 ====================
const toggleSelect = (id: string) => {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

const toggleSelectAllOnPage = () => {
  const next = new Set(selectedIds.value)
  if (isAllOnPageSelected.value) {
    pageCases.value.forEach((c) => next.delete(c.id))
  } else {
    pageCases.value.forEach((c) => next.add(c.id))
  }
  selectedIds.value = next
}

const selectAllOnPage = () => {
  const next = new Set(selectedIds.value)
  pageCases.value.forEach((c) => next.add(c.id))
  selectedIds.value = next
}

const clearSelection = () => {
  selectedIds.value = new Set()
}

const pct = (v: number) => (v == null ? '-' : (v * 100).toFixed(1) + '%')

onMounted(loadAll)
</script>

<style scoped>
.qa-evaluation-view { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
.filter-bar { display: flex; gap: 8px; align-items: center; }
.filter-bar select, .filter-bar .search-input { padding: 4px 8px; }
.search-input { width: 200px; }
.section { margin-bottom: 24px; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; flex-wrap: wrap; }
.section h3 { margin: 0; }
.bulk-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.selection-hint { font-size: 12px; color: #666; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th, .data-table td { border: 1px solid #e0e0e0; padding: 6px 8px; text-align: left; }
.data-table th { background: #f5f7fa; }
.data-table tr.selected { background: #e6f7ff; }
.checkbox-col { width: 32px; text-align: center; }
.question-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-tag { padding: 2px 6px; border-radius: 3px; font-size: 12px; }
.status-tag.SMOKE { background: #e6f7ff; color: #1890ff; }
.status-tag.GOLDEN { background: #fff7e6; color: #fa8c16; }
.tag { display: inline-block; background: #f5f5f5; padding: 1px 8px; margin: 1px 2px; border-radius: 3px; font-size: 12px; }
.tag.more { background: #d6e4ff; color: #1890ff; }
.muted { color: #999; font-size: 12px; }
.pagination { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 8px 0; }
.pass { color: #52c41a; font-weight: 600; }
.fail { color: #f5222d; font-weight: 600; }
.empty { text-align: center; color: #999; padding: 16px; }
button { cursor: pointer; padding: 4px 10px; border: 1px solid #d9d9d9; background: #fff; border-radius: 3px; }
button:disabled { cursor: not-allowed; opacity: 0.5; }
.primary-btn { background: #1890ff; color: #fff; border-color: #1890ff; }
.primary-btn:disabled { background: #ccc; border-color: #ccc; }

/* 模态框 */
.modal-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 6px; width: 720px; max-width: 92vw; max-height: 86vh; display: flex; flex-direction: column; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-bottom: 1px solid #eee; }
.modal-header h3 { margin: 0; }
.close-btn { background: transparent; border: 0; font-size: 20px; padding: 0 6px; }
.modal-body { padding: 16px; overflow: auto; }
.compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.compare-panel { background: #fafafa; border: 1px solid #eee; border-radius: 4px; padding: 12px; }
.compare-empty { color: #999; text-align: center; padding: 32px 0; }
.compare-title { font-weight: 600; margin-bottom: 8px; font-size: 14px; }
.compare-id { color: #999; font-weight: 400; font-size: 12px; }
.kv { margin: 0; }
.kv dt { font-weight: 600; color: #555; margin-top: 6px; }
.kv dd { margin: 4px 0 0 0; }

/* 批量评审 */
.bulk-options { display: flex; gap: 16px; margin-bottom: 8px; }
.radio-label { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; }
.bulk-note { width: 100%; padding: 6px 8px; border: 1px solid #d9d9d9; border-radius: 4px; font-family: inherit; resize: vertical; }
.bulk-targets { max-height: 240px; overflow: auto; border: 1px solid #eee; border-radius: 4px; margin: 8px 0; }
.bulk-target-row { display: flex; justify-content: space-between; padding: 6px 8px; border-bottom: 1px solid #f5f5f5; font-size: 13px; }
.bulk-status { font-weight: 600; }
.bulk-actions-row { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
</style>