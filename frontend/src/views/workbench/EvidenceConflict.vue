<template>
  <div class="evidence-conflict">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>证据冲突处理</span>
          <el-tag
            type="warning"
            size="small">
            {{ conflicts.length }} 条待处理
          </el-tag>
        </div>
      </template>

      <el-alert
        v-if="conflicts.length === 0"
        title="暂无证据冲突"
        type="success"
        :closable="false"
        show-icon
      />

      <div
        v-for="conflict in conflicts"
        :key="conflict.id"
        class="conflict-item">
        <div class="conflict-header">
          <el-tag
            :type="severityTag(conflict.severity)"
            size="small">
            {{ conflict.severity }}
          </el-tag>
          <span class="conflict-title">{{ conflict.title }}</span>
          <el-tag
            v-if="conflict.resolved"
            type="success"
            size="small">
            已解决
          </el-tag>
          <el-tag
            v-else
            type="warning"
            size="small">
            待处理
          </el-tag>
        </div>

        <div class="conflict-body">
          <el-descriptions
            :column="1"
            size="small"
            border>
            <el-descriptions-item label="来源 A">
              <div class="evidence-source">
                <el-tag
                  size="small"
                  type="info">
                  {{ conflict.sourceA?.type || '-' }}
                </el-tag>
                <span>{{ conflict.sourceA?.content || '-' }}</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="来源 B">
              <div class="evidence-source">
                <el-tag
                  size="small"
                  type="info">
                  {{ conflict.sourceB?.type || '-' }}
                </el-tag>
                <span>{{ conflict.sourceB?.content || '-' }}</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item
              v-if="conflict.aiSuggestion"
              label="AI 建议">
              {{ conflict.aiSuggestion }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div
          v-if="!conflict.resolved"
          class="conflict-actions">
          <el-radio-group
            v-model="conflict.resolution"
            size="small"
            style="margin-right: 12px;">
            <el-radio-button label="ACCEPT_A">采纳 A</el-radio-button>
            <el-radio-button label="ACCEPT_B">采纳 B</el-radio-button>
            <el-radio-button label="MERGE">合并</el-radio-button>
            <el-radio-button label="DISMISS">忽略</el-radio-button>
          </el-radio-group>
          <el-button
            type="primary"
            size="small"
            :disabled="!conflict.resolution"
            @click="resolveConflict(conflict)">
            确认处理
          </el-button>
          <el-button
            size="small"
            @click="viewDetail(conflict)">
            详情
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      title="冲突详情"
      width="600px">
      <template v-if="currentConflict">
        <el-descriptions
          :column="2"
          border>
          <el-descriptions-item label="ID">{{ currentConflict.id }}</el-descriptions-item>
          <el-descriptions-item label="严重程度">
            <el-tag :type="severityTag(currentConflict.severity)">{{ currentConflict.severity }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item
            label="关联节点"
            :span="2">
            {{ currentConflict.nodeId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item
            label="创建时间"
            :span="2">
            {{ formatTime(currentConflict.createdAt) }}
          </el-descriptions-item>
        </el-descriptions>
        <div
          v-if="currentConflict.context"
          style="margin-top: 12px;">
          <h4>上下文</h4>
          <pre class="code-block">{{ currentConflict.context }}</pre>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { evidenceConflictApi, type EvidenceConflict } from '@/api/evidence-conflict.api'

const route = useRoute()
const projectId = route.params.projectId as string

const conflicts = ref<EvidenceConflict[]>([])
const detailVisible = ref(false)
const currentConflict = ref<EvidenceConflict | null>(null)

const severityTag = (s: string) => {
  const m: Record<string, string> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
  return m[s] || 'info'
}

const formatTime = (t: string) => t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'

async function loadConflicts() {
  try {
    conflicts.value = await evidenceConflictApi.list(projectId)
  } catch { conflicts.value = [] }
}

async function resolveConflict(conflict: EvidenceConflict) {
  if (!conflict.resolution) { ElMessage.warning('请选择处理方式'); return }
  try {
    const updated = await evidenceConflictApi.resolve(conflict.id, conflict.resolution)
    Object.assign(conflict, updated)
    ElMessage.success(`冲突已处理：${conflict.resolution}`)
  } catch {
    ElMessage.error('冲突处理失败')
  }
}

function viewDetail(conflict: EvidenceConflict) {
  currentConflict.value = conflict
  detailVisible.value = true
}

onMounted(() => { loadConflicts() })
</script>

<style scoped>
.evidence-conflict { padding: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.conflict-item { padding: 16px; border-bottom: 1px solid #ebeef5; }
.conflict-item:last-child { border-bottom: none; }
.conflict-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.conflict-title { font-weight: 600; flex: 1; }
.conflict-body { margin-bottom: 12px; }
.conflict-actions { display: flex; align-items: center; }
.evidence-source { display: flex; align-items: flex-start; gap: 8px; }
.code-block { background: #f5f7fa; padding: 12px; border-radius: 4px; overflow-x: auto; font-size: 13px; }
</style>
