<template>
  <div class="evidence-panel">
    <el-tabs v-model="activeTab">
      <el-tab-pane
        label="代码证据"
        name="code">
        <el-timeline>
          <el-timeline-item
            v-for="item in codeEvidence"
            :key="item.id"
            :timestamp="formatTime(item.createdAt)"
          >
            <el-card
              shadow="hover"
              class="evidence-card">
              <template #header>
                <div class="card-header">
                  <span>{{ item.sourceName }}</span>
                  <el-tag size="small">{{ item.evidenceType }}</el-tag>
                </div>
              </template>
              <div
                v-if="item.location"
                class="location">
                位置: {{ item.location }}
              </div>
              <div class="content">{{ item.content || item.summary }}</div>
              <div class="actions">
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click="viewCode(item)">
                  查看源码
                </el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-if="codeEvidence.length === 0"
          description="暂无代码证据" />
      </el-tab-pane>

      <el-tab-pane
        label="文档证据"
        name="doc">
        <el-timeline>
          <el-timeline-item
            v-for="item in docEvidence"
            :key="item.id"
            :timestamp="formatTime(item.createdAt)"
          >
            <el-card
              shadow="hover"
              class="evidence-card">
              <template #header>
                <div class="card-header">
                  <span>{{ item.sourceName }}</span>
                  <el-tag size="small">{{ item.evidenceType }}</el-tag>
                </div>
              </template>
              <div class="content">{{ item.summary }}</div>
              <div class="actions">
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click="viewDoc(item)">
                  查看文档
                </el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-if="docEvidence.length === 0"
          description="暂无文档证据" />
      </el-tab-pane>

      <el-tab-pane
        label="数据库证据"
        name="db">
        <el-timeline>
          <el-timeline-item
            v-for="item in dbEvidence"
            :key="item.id"
            :timestamp="formatTime(item.createdAt)"
          >
            <el-card
              shadow="hover"
              class="evidence-card">
              <template #header>
                <div class="card-header">
                  <span>{{ item.sourceName }}</span>
                  <el-tag size="small">{{ item.evidenceType }}</el-tag>
                </div>
              </template>
              <div class="content">{{ item.summary }}</div>
              <div class="actions">
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click="viewDb(item)">
                  查看表结构
                </el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-if="dbEvidence.length === 0"
          description="暂无数据库证据" />
      </el-tab-pane>

      <el-tab-pane
        label="测试证据"
        name="test">
        <el-timeline>
          <el-timeline-item
            v-for="item in testEvidence"
            :key="item.id"
            :timestamp="formatTime(item.createdAt)"
          >
            <el-card
              shadow="hover"
              class="evidence-card">
              <template #header>
                <div class="card-header">
                  <span>{{ item.sourceName }}</span>
                  <el-tag
                    size="small"
                    type="success">
                    {{ item.evidenceType }}
                  </el-tag>
                </div>
              </template>
              <div class="content">{{ item.summary }}</div>
              <div class="actions">
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click="viewTest(item)">
                  查看测试结果
                </el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-if="testEvidence.length === 0"
          description="暂无测试证据" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import dayjs from 'dayjs'
import type { Evidence } from '@/types'

interface Props {
  evidence: Evidence[]
}

const props = defineProps<Props>()

const activeTab = ref('code')

const codeEvidence = computed(() => 
  props.evidence.filter(e => ['FILE_LINE', 'SQL_STATEMENT'].includes(e.evidenceType))
)

const docEvidence = computed(() =>
  props.evidence.filter(e => e.evidenceType === 'DOC_PARAGRAPH')
)

const dbEvidence = computed(() =>
  props.evidence.filter(e => e.evidenceType === 'DB_SCHEMA')
)

const testEvidence = computed(() =>
  props.evidence.filter(e => e.evidenceType === 'TEST_RESULT')
)

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const emit = defineEmits<{
  viewCode: [evidence: Evidence]
  viewDoc: [evidence: Evidence]
  viewDb: [evidence: Evidence]
  viewTest: [evidence: Evidence]
}>()

const viewCode = (evidence: Evidence) => {
  emit('viewCode', evidence)
}

const viewDoc = (evidence: Evidence) => {
  emit('viewDoc', evidence)
}

const viewDb = (evidence: Evidence) => {
  emit('viewDb', evidence)
}

const viewTest = (evidence: Evidence) => {
  emit('viewTest', evidence)
}
</script>

<style scoped>
.evidence-panel {
  padding: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.location {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.content {
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  margin-bottom: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
}

.evidence-card {
  margin-bottom: 16px;
}
</style>
