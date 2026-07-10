<template>
  <div class="plugin-management">
    <div class="page-header">
      <div class="header-content">
        <h1 class="page-title">
          <el-icon><Setting /></el-icon>
          插件管理
        </h1>
        <p class="page-desc">管理系统已注册的扫描器、代理、工具和图谱视图插件</p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="filterType"
          placeholder="按类型筛选"
          clearable
          style="width: 200px">
          <el-option
            label="扫描器 (Scanner)"
            value="SCANNER" />
          <el-option
            label="代理 (Agent)"
            value="AGENT" />
          <el-option
            label="工具 (Tool)"
            value="TOOL" />
          <el-option
            label="图谱视图 (GraphView)"
            value="GRAPH_VIEW" />
        </el-select>
        <el-button @click="loadPlugins">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-card shadow="hover">
      <el-table
        v-loading="loading"
        :data="plugins"
        stripe
        style="width: 100%"
        @row-click="showDetail"
      >
        <el-table-column
          prop="id"
          label="ID"
          width="200" />
        <el-table-column
          prop="name"
          label="名称"
          width="180" />
        <el-table-column
          prop="type"
          label="类型"
          width="150">
          <template #default="{ row }">
            <el-tag :type="getTypeTag(row.type)">
              {{ getTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="version"
          label="版本"
          width="120" />
        <el-table-column
          label="状态"
          width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              @change="(val: boolean) => togglePlugin(row, val)" />
          </template>
        </el-table-column>
        <el-table-column
          prop="description"
          label="描述"
          show-overflow-tooltip />
      </el-table>

      <div
        v-if="!loading && plugins.length === 0"
        class="empty-state">
        <el-empty description="暂无插件" />
      </div>
    </el-card>

    <!-- 插件详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      :title="selectedPlugin?.name"
      width="600px"
    >
      <el-descriptions
        v-if="selectedPlugin"
        :column="2"
        border>
        <el-descriptions-item
          label="ID"
          :span="2">
          {{ selectedPlugin.id }}
        </el-descriptions-item>
        <el-descriptions-item label="名称">{{ selectedPlugin.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ selectedPlugin.version }}</el-descriptions-item>
        <el-descriptions-item
          label="类型"
          :span="2">
          <el-tag :type="getTypeTag(selectedPlugin.type)">
            {{ getTypeLabel(selectedPlugin.type) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item
          label="描述"
          :span="2">
          {{ selectedPlugin.description || '无描述' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="selectedPlugin.enabled ? 'success' : 'info'">
            {{ selectedPlugin.enabled ? '已启用' : '已禁用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item
          v-if="selectedPlugin.menuSection"
          label="菜单归属">
          {{ selectedPlugin.menuSection }}
        </el-descriptions-item>
        <el-descriptions-item
          v-if="selectedPlugin.metadata && Object.keys(selectedPlugin.metadata).length > 0"
          label="元数据"
          :span="2">
          <pre class="metadata-pre">{{ JSON.stringify(selectedPlugin.metadata, null, 2) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { Setting, Refresh } from '@element-plus/icons-vue'
import { pluginApi, type PluginDescriptor } from '@/api'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const plugins = ref<PluginDescriptor[]>([])
const filterType = ref<PluginDescriptor['type'] | undefined>()
const detailVisible = ref(false)
const selectedPlugin = ref<PluginDescriptor | null>(null)
const togglingId = ref<string | null>(null)

const getTypeTag = (type: PluginDescriptor['type']) => {
  const map = {
    SCANNER: 'success',
    AGENT: 'primary',
    TOOL: 'warning',
    GRAPH_VIEW: 'info'
  }
  return map[type] || 'info'
}

const getTypeLabel = (type: PluginDescriptor['type']) => {
  const map = {
    SCANNER: '扫描器',
    AGENT: '代理',
    TOOL: '工具',
    GRAPH_VIEW: '图谱视图'
  }
  return map[type] || type
}

const loadPlugins = async () => {
  loading.value = true
  try {
    plugins.value = await pluginApi.listAll(filterType.value ? { type: filterType.value } : undefined)
  } catch (error) {
    ElMessage.error('加载插件列表失败')
    console.error(error)
  } finally {
    loading.value = false
  }
}

const showDetail = (row: PluginDescriptor) => {
  selectedPlugin.value = row
  detailVisible.value = true
}

const togglePlugin = async (plugin: PluginDescriptor, enabled: boolean) => {
  togglingId.value = plugin.id
  try {
    const updated = enabled
      ? await pluginApi.enable(plugin.id)
      : await pluginApi.disable(plugin.id)
    const idx = plugins.value.findIndex(p => p.id === plugin.id)
    if (idx !== -1) {
      plugins.value[idx] = updated
    }
    ElMessage.success(`${plugin.name} 已${enabled ? '启用' : '禁用'}`)
  } catch (error) {
    ElMessage.error(`${enabled ? '启用' : '禁用'}失败`)
    console.error(error)
  } finally {
    togglingId.value = null
  }
}

watch(filterType, () => {
  loadPlugins()
})

onMounted(() => {
  loadPlugins()
})
</script>

<style scoped>
.plugin-management {
  padding: 20px;
}

.plugin-management .page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.plugin-management .page-header .header-content {
  flex: 1;
}

.plugin-management .page-header .header-content .page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 8px 0;
}

.plugin-management .page-header .header-content .page-desc {
  color: var(--el-text-color-secondary);
  margin: 0;
}

.plugin-management .page-header .header-actions {
  display: flex;
  gap: 12px;
}

.plugin-management .empty-state {
  padding: 40px 0;
}

.plugin-management .metadata-pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
