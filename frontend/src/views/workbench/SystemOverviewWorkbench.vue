<template>
  <div class="system-overview-workbench">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>系统关系总览 — 业务 / 功能 / 代码 / 数据</span>
          <div class="header-actions">
            <el-button :loading="exporting" @click="handleExport">导出 MD</el-button>
            <el-button type="primary" :loading="ingesting" @click="handleIngest">
              导入事实底座
            </el-button>
          </div>
        </div>
      </template>
      <div class="stats">
        <el-tag>业务域 {{ overview?.totalDomains ?? 0 }}</el-tag>
        <el-tag type="success">映射 {{ filteredMappings.length }}</el-tag>
        <el-tag type="warning">链路 {{ overview?.corePaths?.length ?? 0 }}</el-tag>
      </div>
      
      <!-- 域控件：业务域过滤 -->
      <div class="filter-bar">
        <el-select 
          v-model="selectedDomain" 
          placeholder="选择业务域" 
          clearable 
          style="width: 200px"
          @change="handleDomainChange"
        >
          <el-option 
            v-for="domain in domains" 
            :key="domain" 
            :label="domain" 
            :value="domain" 
          />
        </el-select>
      </div>
    </el-card>

    <el-card shadow="hover" class="mt">
      <template #header>四层映射总表</template>
      
      <!-- from/to 路径查询 -->
      <div class="path-query">
        <el-input 
          v-model="pathFrom" 
          placeholder="起点（如：用户管理）" 
          clearable 
          style="width: 180px"
        />
        <el-icon><Right /></el-icon>
        <el-input 
          v-model="pathTo" 
          placeholder="终点（如：数据库表）" 
          clearable 
          style="width: 180px"
        />
        <el-button type="primary" :loading="queryingPaths" @click="handlePathQuery">查询路径</el-button>
      </div>
      
      <el-table :data="filteredMappings" stripe size="small" max-height="520">
        <el-table-column prop="businessDomain" label="业务域" width="160" />
        <el-table-column prop="capability" label="业务能力" width="120" />
        <el-table-column prop="feature" label="功能" width="130" />
        <el-table-column prop="controller" label="Controller" width="180" />
        <el-table-column prop="apiPath" label="API" width="190" />
        <el-table-column prop="codeModule" label="代码" width="190" />
        <el-table-column label="数据表">
          <template #default="{ row }">
            <el-tag v-for="t in row.dataTables" :key="t" size="small" class="mr">{{ t }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="edgeType" label="关系" width="150" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleDrillDown(row)">
              下钻
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="hover" class="mt">
      <template #header>核心贯穿链路（业务→功能→代码→数据）</template>
      <ul class="paths">
        <li v-for="(p, i) in displayPaths" :key="i">{{ p }}</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import {
  exportSystemOverviewReport,
  getCorePaths,
  getSystemOverview,
  ingestBuiltins,
  type LayerMapping,
  type SystemOverview,
} from '@/api/system-overview.api'

const route = useRoute()
const router = useRouter()
const projectId = (route.params.projectId as string) || 'self'
const versionId = (route.query.versionId as string) || undefined
const overview = ref<SystemOverview>()
const ingesting = ref(false)
const exporting = ref(false)
const queryingPaths = ref(false)
const queriedPaths = ref<string[]>([])

// 域控件：业务域过滤
const selectedDomain = ref<string>('')
const pathFrom = ref<string>('')
const pathTo = ref<string>('')

// 计算属性：提取所有业务域
const domains = computed(() => {
  if (!overview.value?.mappings) return []
  return [...new Set(overview.value.mappings.map(m => m.businessDomain))]
})

// 计算属性：过滤后的映射
const filteredMappings = computed(() => {
  if (!overview.value?.mappings) return []
  let filtered = overview.value.mappings
  
  // 按业务域过滤
  if (selectedDomain.value) {
    filtered = filtered.filter(m => m.businessDomain === selectedDomain.value)
  }
  
  // 按路径查询过滤（起点和终点）
  if (pathFrom.value || pathTo.value) {
    filtered = filtered.filter(m => {
      const matchFrom = !pathFrom.value || 
        includesText(m.businessDomain, pathFrom.value) ||
        includesText(m.capability, pathFrom.value) ||
        includesText(m.feature, pathFrom.value) ||
        includesText(m.controller, pathFrom.value) ||
        includesText(m.codeModule, pathFrom.value)
      
      const matchTo = !pathTo.value ||
        m.dataTables?.some(t => t.includes(pathTo.value)) ||
        includesText(m.apiPath, pathTo.value)
      
      return matchFrom && matchTo
    })
  }
  
  return filtered
})

const displayPaths = computed(() => queriedPaths.value.length > 0 ? queriedPaths.value : overview.value?.corePaths ?? [])

function includesText(value: string | undefined | null, keyword: string) {
  return (value ?? '').includes(keyword)
}

// 域变更处理
function handleDomainChange(domain: string) {
  selectedDomain.value = domain
}

// 路径查询处理
async function handlePathQuery() {
  if (!pathFrom.value && !pathTo.value) {
    ElMessage.warning('请输入起点或终点')
    return
  }
  queryingPaths.value = true
  try {
    queriedPaths.value = await getCorePaths(projectId, versionId, pathFrom.value || undefined, pathTo.value || undefined)
    ElMessage.success(`查到 ${queriedPaths.value.length} 条路径`)
  } catch {
    ElMessage.error('查询路径失败')
  } finally {
    queryingPaths.value = false
  }
}

// 下钻处理
function handleDrillDown(row: LayerMapping) {
  router.push({
    name: 'UnifiedGraph',
    params: { projectId },
    query: {
      ...(versionId ? { versionId } : {}),
      focus: row.controller || row.apiPath || row.codeModule || row.businessDomain,
      table: row.dataTables?.[0],
    },
  })
}

async function loadOverview() {
  try {
    overview.value = await getSystemOverview(projectId, versionId)
    queriedPaths.value = []
  } catch {
    ElMessage.error('加载系统关系总览失败')
  }
}

async function handleExport() {
  exporting.value = true
  try {
    const data = await exportSystemOverviewReport(projectId, versionId || 'default', 'MD')
    const raw = data as unknown
    const payload = raw instanceof Blob ? raw : ((raw as { data?: unknown }).data ?? raw)
    const blob = payload instanceof Blob
      ? payload
      : new Blob([payload as BlobPart], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `system-overview-${projectId}-${versionId || 'default'}.md`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('系统关系总览报告已导出')
  } catch {
    ElMessage.error('导出失败')
  } finally {
    exporting.value = false
  }
}

async function handleIngest() {
  ingesting.value = true
  try {
    const r = await ingestBuiltins(projectId, versionId)
    ElMessage.success(`已导入：向量 ${r.vectorCount}，Claim ${r.claimCount}，FAQ ${r.faqCount}`)
    await loadOverview()
  } catch {
    ElMessage.error('导入失败')
  } finally {
    ingesting.value = false
  }
}

onMounted(loadOverview)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.stats {
  display: flex;
  gap: 12px;
}
.mt {
  margin-top: 12px;
}
.mr {
  margin-right: 4px;
}
.paths {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
}
.filter-bar {
  margin-top: 12px;
  display: flex;
  gap: 12px;
  align-items: center;
}
.path-query {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
</style>
