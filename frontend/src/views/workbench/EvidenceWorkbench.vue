<template>
  <div class="evidence-workbench">
    <div class="workbench-header">
      <div class="header-row">
        <div>
          <h2>证据工作台</h2>
          <p class="desc">处理差异和风险：切片路径、漂移队列、证据审核</p>
        </div>
        <!-- L-26: 版本选择器 -->
        <el-select
          v-model="currentVersion"
          placeholder="选择版本"
          style="width: 240px;"
          @change="onVersionChange">
          <el-option
            v-for="v in versions"
            :key="v.id"
            :label="formatVersionLabel(v)"
            :value="v.id"
          />
        </el-select>
      </div>
    </div>

    <el-tabs
      v-model="activeTab"
      type="border-card">
      <!-- Feature Slice 视图 -->
      <el-tab-pane
        label="功能切片"
        name="slice">
        <FeatureSliceWorkbench
          :project-id="projectId"
          :version-id="currentVersion" />
      </el-tab-pane>

      <!-- 漂移队列 -->
      <el-tab-pane
        label="漂移队列"
        name="drift">
        <DriftQueue
          :project-id="projectId"
          :version-id="currentVersion" />
      </el-tab-pane>

      <!-- 图谱质量 -->
      <el-tab-pane
        label="图谱质量"
        name="quality">
        <QualityPanel
          :project-id="projectId"
          :version-id="currentVersion" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { loadScanVersions, type ScanVersion } from '@/utils/versionsCache'
import { formatVersionLabel } from '@/utils/formatVersionLabel'
import FeatureSliceWorkbench from './FeatureSliceWorkbench.vue'
import DriftQueue from './DriftQueue.vue'
import QualityPanel from './QualityPanel.vue'

const route = useRoute()
const projectId = route.params.projectId as string
const activeTab = ref('slice')
const currentVersion = ref('')
const versions = ref<ScanVersion[]>([])

// L-19: 使用共享缓存加载版本列表
async function loadVersions() {
  await loadScanVersions(projectId, versions)
  if (versions.value.length > 0 && !currentVersion.value) {
    currentVersion.value = versions.value[0].id
  }
}

// L-26: 版本切换时子组件通过 watch(props.versionId) 自动重新加载
function onVersionChange() {
  // 子组件已绑定 :version-id="currentVersion"，切换后 watch 自动触发
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
.workbench-header .header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
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
