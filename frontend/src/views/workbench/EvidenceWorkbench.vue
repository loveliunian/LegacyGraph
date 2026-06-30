<template>
  <div class="evidence-workbench">
    <div class="workbench-header">
      <h2>证据工作台</h2>
      <p class="desc">处理差异和风险：切片路径、漂移队列、证据审核</p>
    </div>

    <el-tabs v-model="activeTab" type="border-card">
      <!-- Feature Slice 视图 -->
      <el-tab-pane label="功能切片" name="slice">
        <FeatureSliceWorkbench :project-id="projectId" :version-id="currentVersion" />
      </el-tab-pane>

      <!-- 漂移队列 -->
      <el-tab-pane label="漂移队列" name="drift">
        <DriftQueue :project-id="projectId" :version-id="currentVersion" />
      </el-tab-pane>

      <!-- 图谱质量 -->
      <el-tab-pane label="图谱质量" name="quality">
        <QualityPanel :project-id="projectId" :version-id="currentVersion" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { get } from '@/utils/request'
import FeatureSliceWorkbench from './FeatureSliceWorkbench.vue'
import DriftQueue from './DriftQueue.vue'
import QualityPanel from './QualityPanel.vue'

const route = useRoute()
const projectId = route.params.projectId as string
const activeTab = ref('slice')
const currentVersion = ref('')
const versions = ref<any[]>([])

async function loadVersions() {
  try {
    const res: any = await get(`/lg/projects/${projectId}/scan-versions`)
    versions.value = Array.isArray(res) ? res : (res?.list || [])
    if (versions.value.length > 0 && !currentVersion.value) {
      currentVersion.value = versions.value[0].id
    }
  } catch { /* ignore */ }
}

onMounted(() => { loadVersions() })
</script>

<style scoped>
.evidence-workbench {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.workbench-header {
  margin-bottom: 16px;
}
.workbench-header h2 {
  margin: 0 0 4px 0;
  font-size: 20px;
  color: var(--el-text-color-primary);
}
.workbench-header .desc {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
