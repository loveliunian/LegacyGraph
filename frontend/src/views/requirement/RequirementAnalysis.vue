<template>
  <div class="requirement-analysis">
    <!-- 输入区 -->
    <el-card class="input-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">
            <el-icon><EditPen /></el-icon>
            需求分析
          </span>
          <el-button
            type="primary"
            :loading="analyzing"
            :disabled="!requirementText.trim()"
            @click="handleAnalyze">
            <el-icon><MagicStick /></el-icon>
            分析需求
          </el-button>
        </div>
      </template>

      <el-input
        v-model="requirementText"
        type="textarea"
        :rows="6"
        placeholder="请输入需求文本，系统将自动抽取结构化条目、目标与待确认问题..."
        maxlength="8000"
        show-word-limit
      />
    </el-card>

    <!-- 分析结果 -->
    <template v-if="analysis">
      <!-- 目标 -->
      <el-card class="result-card">
        <template #header>
          <span class="card-title">
            <el-icon><Aim /></el-icon>
            需求目标
          </span>
        </template>
        <div class="goal-text">{{ analysis.goal || '未识别到明确目标' }}</div>
      </el-card>

      <!-- 待确认问题（openQuestions 非空时高亮提示） -->
      <el-alert
        v-if="openQuestions.length > 0"
        type="warning"
        show-icon
        :closable="false"
        class="open-questions-alert">
        <template #title>
          存在 {{ openQuestions.length }} 个待确认问题，请先澄清后再生成方案
        </template>
        <div class="open-questions-content">
          <div
            v-for="(q, idx) in openQuestions"
            :key="idx"
            class="open-question-item">
            <div class="open-question-text">{{ idx + 1 }}. {{ q }}</div>
            <el-input
              v-model="openQuestionAnswers[q]"
              type="textarea"
              :rows="2"
              placeholder="请输入你的回答..." />
          </div>
          <div class="clarify-action">
            <el-button
              type="primary"
              size="small"
              :loading="clarifying"
              :disabled="!savedRequirement"
              @click="handleClarify">
              提交补充
            </el-button>
          </div>
        </div>
      </el-alert>

      <!-- 结构化条目 -->
      <el-card class="result-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              <el-icon><List /></el-icon>
              结构化条目
              <el-tag size="small" round>{{ items.length }}</el-tag>
            </span>
          </div>
        </template>

        <el-collapse v-if="items.length > 0">
          <el-collapse-item
            v-for="(item, idx) in items"
            :key="item.code || idx"
            :name="item.code || String(idx)">
            <template #title>
              <div class="item-title">
                <el-tag size="small" type="info" effect="plain">{{ item.code || `#${idx + 1}` }}</el-tag>
                <span class="item-text">{{ item.text }}</span>
              </div>
            </template>
            <div class="item-detail">
              <div class="detail-row">
                <span class="detail-label">描述：</span>
                <span class="detail-value">{{ item.text }}</span>
              </div>
              <div
                v-if="item.acceptanceCriteria && item.acceptanceCriteria.length"
                class="detail-row">
                <span class="detail-label">验收标准：</span>
                <ul class="detail-list">
                  <li
                    v-for="(c, i) in item.acceptanceCriteria"
                    :key="i">
                    {{ c }}
                  </li>
                </ul>
              </div>
              <div
                v-if="item.constraints && item.constraints.length"
                class="detail-row">
                <span class="detail-label">约束条件：</span>
                <ul class="detail-list">
                  <li
                    v-for="(c, i) in item.constraints"
                    :key="i">
                    {{ c }}
                  </li>
                </ul>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else description="未识别到结构化条目" />
      </el-card>

      <!-- 受影响节点 -->
      <el-card class="result-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              <el-icon><Connection /></el-icon>
              受影响节点
              <el-tag
                v-if="impact"
                size="small"
                round>{{ impactedNodes.length }}</el-tag>
            </span>
            <el-button
              type="primary"
              plain
              size="small"
              :loading="impacting"
              :disabled="!savedRequirement"
              @click="handleImpact">
              <el-icon><DataAnalysis /></el-icon>
              {{ impact ? '重新分析影响' : '分析影响' }}
            </el-button>
          </div>
        </template>

        <el-empty
          v-if="!impact"
          description="保存需求后可进行影响分析" />
        <template v-else>
          <el-table
            :data="impactedNodes"
            border
            stripe
            style="width: 100%">
            <el-table-column
              prop="nodeName"
              label="节点名称"
              min-width="180" />
            <el-table-column
              prop="nodeType"
              label="节点类型"
              width="140">
              <template #default="{ row }">
                <el-tag size="small">{{ row.nodeType || '-' }}</el-tag>
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
              min-width="160">
              <template #default="{ row }">
                <span class="mono-text">{{ row.symbolName || '-' }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div
            v-if="paths.length > 0"
            class="paths-section">
            <div class="section-subtitle">影响路径</div>
            <div
              v-for="(p, i) in paths"
              :key="i"
              class="path-item">
              <el-tag size="small" type="info">路径 {{ i + 1 }}</el-tag>
              <span class="path-nodes">
                {{ (p.nodeNames && p.nodeNames.length ? p.nodeNames : p.nodes || []).join(' → ') || '-' }}
              </span>
            </div>
          </div>
        </template>
      </el-card>

      <!-- 操作区 -->
      <div class="action-bar">
        <el-button
          :loading="saving"
          :disabled="!analysis"
          @click="handleSave">
          <el-icon><DocumentChecked /></el-icon>
          {{ savedRequirement ? '重新保存需求' : '保存需求' }}
        </el-button>
        <el-tooltip
          :content="generateDisabledReason"
          :disabled="!isGenerateDisabled"
          placement="top">
          <span>
            <el-button
              type="primary"
              :loading="generating"
              :disabled="isGenerateDisabled"
              @click="handleGenerate">
              <el-icon><MagicStick /></el-icon>
              生成方案
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </template>

    <!-- 空状态 -->
    <el-empty
      v-else
      description="输入需求文本后点击「分析需求」开始"
      class="placeholder-empty" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  EditPen,
  MagicStick,
  Aim,
  List,
  Connection,
  DataAnalysis,
  DocumentChecked,
} from '@element-plus/icons-vue'
import { requirementApi, solutionApi } from '@/api'
import type {
  RequirementAnalysis,
  SavedRequirement,
  ImpactAnalysis,
  ImpactedNode,
  ImpactPath,
} from '@/api'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => route.params.projectId as string)

const requirementText = ref('')
const analyzing = ref(false)
const saving = ref(false)
const impacting = ref(false)
const generating = ref(false)
const clarifying = ref(false)

const analysis = ref<RequirementAnalysis | null>(null)
const savedRequirement = ref<SavedRequirement | null>(null)
const impact = ref<ImpactAnalysis | null>(null)
const openQuestionAnswers = ref<Record<string, string>>({})

const items = computed(() => analysis.value?.items ?? [])
const openQuestions = computed(() => analysis.value?.openQuestions ?? [])
const impactedNodes = computed<ImpactedNode[]>(() => impact.value?.impactedNodes ?? [])
const paths = computed<ImpactPath[]>(() => impact.value?.paths ?? [])

/** 生成方案按钮禁用条件：存在待确认问题时禁用（任务 12.2） */
const isGenerateDisabled = computed(() => openQuestions.value.length > 0 || !analysis.value)

const generateDisabledReason = computed(() => {
  if (openQuestions.value.length > 0) {
    return `存在 ${openQuestions.value.length} 个待确认问题，请先澄清后再生成方案`
  }
  if (!analysis.value) return '请先分析需求'
  return ''
})

async function handleAnalyze() {
  if (!requirementText.value.trim()) return
  analyzing.value = true
  try {
    const data = await requirementApi.analyze(projectId.value, {
      text: requirementText.value,
    })
    analysis.value = data
    savedRequirement.value = null
    impact.value = null
    ElMessage.success('需求分析完成')
  } catch (e) {
    console.error(e)
  } finally {
    analyzing.value = false
  }
}

async function handleSave() {
  if (!analysis.value) return
  saving.value = true
  try {
    const data = await requirementApi.save(projectId.value, {
      text: requirementText.value,
      goal: analysis.value.goal,
      items: analysis.value.items,
      openQuestions: analysis.value.openQuestions,
    })
    savedRequirement.value = data
    ElMessage.success('需求已保存并构建图谱')
  } catch (e) {
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function handleImpact() {
  const reqId = savedRequirement.value?.id
  if (!reqId) return
  impacting.value = true
  try {
    const data = await requirementApi.impact(projectId.value, reqId)
    impact.value = data
    ElMessage.success('影响分析完成')
  } catch (e) {
    console.error(e)
  } finally {
    impacting.value = false
  }
}

async function handleClarify() {
  const reqId = savedRequirement.value?.id
  if (!reqId) return
  clarifying.value = true
  try {
    const answers: Record<string, string> = {}
    for (const [question, answer] of Object.entries(openQuestionAnswers.value)) {
      if (answer.trim()) answers[question] = answer
    }
    const data = await requirementApi.clarify(projectId.value, reqId, answers) as any
    analysis.value = data?.analysis ?? data
    openQuestionAnswers.value = {}
    ElMessage.success('需求已重新分析')
  } catch (e) {
    console.error(e)
  } finally {
    clarifying.value = false
  }
}

async function handleGenerate() {
  if (isGenerateDisabled.value) return
  generating.value = true
  try {
    // 未保存则先保存以拿到 requirementId
    if (!savedRequirement.value) {
      await handleSave()
    }
    const requirementId = savedRequirement.value?.id
    const res = await solutionApi.generate(projectId.value, {
      projectId: projectId.value,
      requirementId,
      goal: analysis.value?.goal,
    })
    const solutionId = extractSolutionId(res)
    if (solutionId) {
      ElMessage.success('方案已生成')
      router.push(`/projects/${projectId.value}/solutions/${solutionId}`)
    } else {
      ElMessage.success('方案已生成')
    }
  } catch (e) {
    console.error(e)
  } finally {
    generating.value = false
  }
}

/** 从方案生成响应中提取首个方案 ID */
function extractSolutionId(res: unknown): string | undefined {
  if (!res) return undefined
  if (typeof res !== 'object') return undefined
  const r = res as Record<string, any>
  if (typeof r.id === 'string') return r.id
  if (r.recommended && typeof r.recommended.id === 'string') return r.recommended.id
  if (Array.isArray(r.solutions) && r.solutions[0]?.id) return r.solutions[0].id
  if (Array.isArray(r.alternatives) && r.alternatives[0]?.id) return r.alternatives[0].id
  if (Array.isArray(res) && (res as any[])[0]?.id) return (res as any[])[0].id
  return undefined
}
</script>

<style scoped>
.requirement-analysis {
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
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.goal-text {
  font-size: 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}

.open-questions-alert {
  margin: 0;
}

.open-questions-content {
  margin-top: 8px;
}

.open-question-item {
  margin-bottom: 12px;
}

.open-question-text {
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}

.clarify-action {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.item-title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  overflow: hidden;
}

.item-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary);
}

.item-detail {
  padding: 4px 0;
}

.detail-row {
  display: flex;
  margin-bottom: 10px;
  font-size: 13px;
  line-height: 1.6;
}

.detail-label {
  flex-shrink: 0;
  width: 80px;
  color: var(--el-text-color-secondary);
}

.detail-value {
  color: var(--el-text-color-regular);
}

.detail-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}

.detail-list li {
  margin-bottom: 4px;
}

.paths-section {
  margin-top: 16px;
}

.section-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 10px;
}

.path-item {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}

.path-nodes {
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.mono-text {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 8px 0;
}

.placeholder-empty {
  padding: 60px 0;
}
</style>
