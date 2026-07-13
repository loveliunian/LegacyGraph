<template>
  <div class="merge-candidates-panel">
    <!-- 顶部工具栏 -->
    <div class="panel-toolbar">
      <el-select
        v-model="selectedNodeType"
        placeholder="选择节点类型"
        size="small"
        style="width: 180px;"
        @change="loadCandidates">
        <el-option
          v-for="t in nodeTypeOptions"
          :key="t.value"
          :label="t.label"
          :value="t.value" />
      </el-select>
      <el-button
        size="small"
        :loading="loading"
        @click="loadCandidates">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
      <span class="candidate-count">共 {{ candidates.length }} 对候选</span>
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!loading && candidates.length === 0"
      description="暂无合并候选"
      :image-size="80" />

    <!-- 候选列表 -->
    <div
      v-loading="loading"
      class="candidate-list">
      <div
        v-for="(c, idx) in candidates"
        :key="`${c.nodeAId}-${c.nodeBId}`"
        class="candidate-card"
        :class="{ active: activeIdx === idx }"
        @click="activeIdx = idx">
        <div class="candidate-summary">
          <span class="node-pair">
            <span class="node-name">{{ getNodeLabel(c.nodeAId) }}</span>
            <el-icon class="merge-arrow"><Right /></el-icon>
            <span class="node-name">{{ getNodeLabel(c.nodeBId) }}</span>
          </span>
          <el-tag
            size="small"
            :type="scoreTagType(c.similarityScore)">
            {{ (c.similarityScore * 100).toFixed(1) }}%
          </el-tag>
        </div>
        <!-- 6 维评分条 -->
        <div class="score-bars">
          <div
            v-for="dim in scoreDims"
            :key="dim.key"
            class="score-bar">
            <span class="dim-label">{{ dim.label }}</span>
            <el-progress
              :percentage="Number(((c[dim.key as keyof MergeCandidate] as number) * 100).toFixed(0))"
              :stroke-width="6"
              :color="dim.color"
              :show-text="false" />
            <span class="dim-value">{{ Number((c[dim.key as keyof MergeCandidate] as number)).toFixed(2) }}</span>
          </div>
        </div>
        <!-- 操作按钮 -->
        <div
          v-if="activeIdx === idx"
          class="candidate-actions"
          @click.stop>
          <el-button
            type="success"
            size="small"
            :loading="actingPair === `${c.nodeAId}|${c.nodeBId}`"
            @click="onAutoMerge(c)">
            <el-icon><CircleCheck /></el-icon>
            一键合并
          </el-button>
          <el-button
            type="warning"
            size="small"
            :loading="decidingPair === `${c.nodeAId}|${c.nodeBId}`"
            @click="onDecide(c)">
            <el-icon><View /></el-icon>
            LLM 审核
          </el-button>
          <el-button
            type="danger"
            size="small"
            @click="onReject(c, idx)">
            <el-icon><CircleClose /></el-icon>
            拒绝
          </el-button>
        </div>
      </div>
    </div>

    <!-- 6 维评分雷达图（双栏对比） -->
    <div
      v-if="activeCandidate"
      class="radar-section">
      <h4>6 维评分对比</h4>
      <div class="radar-wrapper">
        <svg
          :viewBox="`0 0 ${svgSize} ${svgSize}`"
          class="radar-svg">
          <!-- 背景网格 -->
          <polygon
            v-for="ring in rings"
            :key="ring"
            :points="ringPoints(ring)"
            fill="none"
            stroke="#dcdfe6"
            stroke-width="1" />
          <!-- 轴线 -->
          <line
            v-for="(p, i) in axisEndPoints"
            :key="'axis-' + i"
            :x1="center"
            :y1="center"
            :x2="p.x"
            :y2="p.y"
            stroke="#dcdfe6"
            stroke-width="1" />
          <!-- 数据多边形 -->
          <polygon
            :points="dataPoints(activeCandidate)"
            fill="rgba(64, 158, 255, 0.25)"
            stroke="#409eff"
            stroke-width="2" />
          <!-- 数据点 -->
          <circle
            v-for="(p, i) in dataPointList(activeCandidate)"
            :key="'pt-' + i"
            :cx="p.x"
            :cy="p.y"
            r="3"
            fill="#409eff" />
          <!-- 维度标签 -->
          <text
            v-for="(p, i) in labelPoints"
            :key="'lbl-' + i"
            :x="p.x"
            :y="p.y"
            :text-anchor="p.anchor"
            class="dim-text">{{ scoreDims[i].label }}</text>
        </svg>
      </div>
      <div class="radar-legend">
        <span class="legend-item"><span class="legend-color" style="background:#409eff" />综合评分：{{ (activeCandidate.similarityScore * 100).toFixed(1) }}%</span>
      </div>
    </div>

    <!-- LLM 决策结果 -->
    <el-dialog
      v-model="decisionDialogVisible"
      title="LLM 合并决策"
      width="480px"
      destroy-on-close>
      <div v-if="lastDecision">
        <el-result
          :icon="decisionIcon(lastDecision.decision)"
          :title="decisionLabel(lastDecision.decision)"
          :sub-title="`综合评分：${(Number(lastDecision.score) * 100).toFixed(1)}%`">
          <template #extra>
            <ul class="reason-list">
              <li
                v-for="(r, i) in lastDecision.reasons"
                :key="i">
                {{ r }}
              </li>
            </ul>
            <el-button
              v-if="lastDecision.decision !== 'REJECT'"
              type="success"
              size="small"
              :loading="actingPair === lastDecisionPairKey"
              @click="onExecuteFromDecision">
              确认执行合并
            </el-button>
          </template>
        </el-result>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Right, CircleCheck, View, CircleClose } from '@element-plus/icons-vue'
import { mergeApi, type MergeCandidate, type GraphMergeDecision } from '@/api/merge.api'

interface Props {
  projectId: string
  /** 已加载的图谱节点（id → label 映射），用于在面板中展示候选节点名称 */
  nodes?: Array<{ id: string; label: string }>
}

const props = withDefaults(defineProps<Props>(), {
  nodes: () => [],
})

const emit = defineEmits<{
  /** 合并执行成功后通知父组件刷新图谱 */
  (e: 'merged'): void
}>()

/** 业务域类型选项 */
const nodeTypeOptions = [
  { value: 'BusinessDomain', label: '业务域' },
  { value: 'BusinessProcess', label: '业务流程' },
  { value: 'BusinessObject', label: '业务对象' },
  { value: 'BusinessRule', label: '业务规则' },
  { value: 'Role', label: '角色' },
  { value: 'Feature', label: '功能点' },
  { value: 'ApiEndpoint', label: 'API 接口' },
  { value: 'Table', label: '数据库表' },
]

/** 6 维评分维度定义 */
const scoreDims = [
  { key: 'nameScore', label: '名称', color: '#67c23a' },
  { key: 'semanticScore', label: '语义', color: '#409eff' },
  { key: 'structScore', label: '结构', color: '#e6a23c' },
  { key: 'evidenceScore', label: '证据', color: '#f56c6c' },
  { key: 'runtimeCooccurrenceScore', label: '运行时', color: '#909399' },
  { key: 'historyScore', label: '历史', color: '#9c27b0' },
] as const

const selectedNodeType = ref('BusinessDomain')
const candidates = ref<MergeCandidate[]>([])
const activeIdx = ref(-1)
const loading = ref(false)
const actingPair = ref('')
const decidingPair = ref('')
const decisionDialogVisible = ref(false)
const lastDecision = ref<GraphMergeDecision | null>(null)

/** 雷达图配置 */
const svgSize = 240
const center = svgSize / 2
const radius = 80
const rings = [0.25, 0.5, 0.75, 1.0]

const activeCandidate = computed(() =>
  activeIdx.value >= 0 && activeIdx.value < candidates.value.length
    ? candidates.value[activeIdx.value]
    : null,
)

/** 节点 id → label 映射 */
const nodeLabelMap = computed(() => {
  const m = new Map<string, string>()
  for (const n of props.nodes) {
    m.set(n.id, n.label)
  }
  return m
})

function getNodeLabel(id: string): string {
  return nodeLabelMap.value.get(id) || id.slice(0, 8)
}

function scoreTagType(score: number) {
  if (score >= 0.85) return 'success'
  if (score >= 0.5) return 'warning'
  return 'info'
}

/** 雷达图坐标计算 */
function angleFor(i: number): number {
  return -Math.PI / 2 + (i * 2 * Math.PI) / scoreDims.length
}

function pointAt(i: number, r: number) {
  return {
    x: center + r * Math.cos(angleFor(i)),
    y: center + r * Math.sin(angleFor(i)),
  }
}

function ringPoints(ring: number): string {
  return scoreDims
    .map((_, i) => {
      const p = pointAt(i, radius * ring)
      return `${p.x},${p.y}`
    })
    .join(' ')
}

const axisEndPoints = computed(() =>
  scoreDims.map((_, i) => pointAt(i, radius)),
)

const labelPoints = computed(() =>
  scoreDims.map((_, i) => {
    const p = pointAt(i, radius + 14)
    const anchor = Math.abs(p.x - center) < 5 ? 'middle' : p.x > center ? 'start' : 'end'
    return { ...p, anchor }
  }),
)

function dataPoints(c: MergeCandidate): string {
  return dataPointList(c)
    .map((p) => `${p.x},${p.y}`)
    .join(' ')
}

function dataPointList(c: MergeCandidate) {
  return scoreDims.map((dim, i) => {
    const val = Number(c[dim.key as keyof MergeCandidate]) || 0
    return pointAt(i, radius * Math.min(1, Math.max(0, val)))
  })
}

/** 加载合并候选 */
async function loadCandidates() {
  if (!props.projectId) return
  loading.value = true
  try {
    candidates.value = await mergeApi.listCandidates(props.projectId, selectedNodeType.value)
    activeIdx.value = candidates.value.length > 0 ? 0 : -1
  } catch (e: any) {
    ElMessage.error('加载合并候选失败：' + (e?.message || ''))
    candidates.value = []
  } finally {
    loading.value = false
  }
}

/** 一键合并（按 AUTO_MERGE 阈值执行） */
async function onAutoMerge(c: MergeCandidate) {
  const pairKey = `${c.nodeAId}|${c.nodeBId}`
  try {
    await ElMessageBox.confirm(
      `确认将 "${getNodeLabel(c.nodeBId)}" 合并到 "${getNodeLabel(c.nodeAId)}" 吗？被合并节点将被删除，边重定向到目标节点。`,
      '合并确认',
      { type: 'warning' },
    )
  } catch {
    return // 用户取消
  }
  actingPair.value = pairKey
  try {
    await mergeApi.executeMerge(props.projectId, c.nodeAId, c.nodeBId)
    ElMessage.success('合并成功')
    await loadCandidates()
    emit('merged')
  } catch (e: any) {
    ElMessage.error('合并失败：' + (e?.message || ''))
  } finally {
    actingPair.value = ''
  }
}

/** LLM 决策（中间档候选送审） */
async function onDecide(c: MergeCandidate) {
  const pairKey = `${c.nodeAId}|${c.nodeBId}`
  decidingPair.value = pairKey
  try {
    lastDecision.value = await mergeApi.decideMerge(props.projectId, c)
    decisionDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error('LLM 决策失败：' + (e?.message || ''))
  } finally {
    decidingPair.value = ''
  }
}

/** 从 LLM 决策结果直接执行合并 */
async function onExecuteFromDecision() {
  if (!lastDecision.value) return
  const c = candidates.value.find(
    (x) => x.nodeAId === lastDecision.value!.candidateA && x.nodeBId === lastDecision.value!.candidateB,
  )
  if (!c) return
  decisionDialogVisible.value = false
  await onAutoMerge(c)
}

/** 拒绝候选（仅从前端列表移除，不调后端） */
function onReject(_c: MergeCandidate, idx: number) {
  candidates.value.splice(idx, 1)
  if (activeIdx.value >= candidates.value.length) {
    activeIdx.value = candidates.value.length - 1
  }
  ElMessage.info('已从候选列表移除')
}

function decisionIcon(d: string) {
  if (d === 'AUTO_MERGE') return 'success'
  if (d === 'REVIEW') return 'warning'
  return 'info'
}

function decisionLabel(d: string) {
  if (d === 'AUTO_MERGE') return '建议自动合并'
  if (d === 'REVIEW') return '建议人工审核'
  return '建议拒绝合并'
}

const lastDecisionPairKey = computed(() =>
  lastDecision.value ? `${lastDecision.value.candidateA}|${lastDecision.value.candidateB}` : '',
)

watch(
  () => props.projectId,
  (v) => {
    if (v) loadCandidates()
  },
)

onMounted(() => {
  if (props.projectId) loadCandidates()
})
</script>

<style scoped>
.merge-candidates-panel {
  padding: 12px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.candidate-count {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}

.candidate-list {
  max-height: 480px;
  overflow-y: auto;
}

.candidate-card {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.candidate-card:hover {
  border-color: #c6e2ff;
}

.candidate-card.active {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15);
}

.candidate-summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.node-pair {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.node-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
}

.merge-arrow {
  color: #909399;
  font-size: 12px;
}

.score-bars {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px 12px;
}

.score-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.dim-label {
  width: 32px;
  color: #909399;
  flex-shrink: 0;
}

.dim-value {
  width: 28px;
  color: #606266;
  text-align: right;
  flex-shrink: 0;
}

.candidate-actions {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
  display: flex;
  gap: 8px;
}

.radar-section {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}

.radar-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #303133;
}

.radar-wrapper {
  display: flex;
  justify-content: center;
}

.radar-svg {
  width: 240px;
  height: 240px;
}

.dim-text {
  font-size: 11px;
  fill: #606266;
}

.radar-legend {
  text-align: center;
  margin-top: 6px;
  font-size: 12px;
  color: #606266;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.legend-color {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

.reason-list {
  text-align: left;
  margin: 8px 0 12px;
  padding-left: 18px;
  font-size: 12px;
  color: #606266;
}
</style>
