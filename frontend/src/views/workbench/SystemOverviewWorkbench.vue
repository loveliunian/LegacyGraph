<template>
  <div class="system-overview-workbench">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>系统关系总览 — 业务 / 功能 / 代码 / 数据</span>
          <div class="header-actions">
            <el-button :loading="exporting" @click="handleExport">导出 MD</el-button>
            <el-button type="primary" :loading="ingesting" @click="handleIngest">
              {{ ingestLabel }}
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

    <el-card shadow="hover" class="mt" v-loading="reportsLoading">
      <template #header>
        <div class="card-header">
          <span>总结文档</span>
          <el-button :loading="reportGenerating" @click="handleGenerateDocument">
            生成/刷新文档
          </el-button>
        </div>
      </template>
      <el-table v-if="systemOverviewReports.length" :data="systemOverviewReports" stripe size="small">
        <el-table-column prop="reportName" label="文档名称" min-width="220" />
        <el-table-column prop="versionId" label="扫描版本" width="180" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="completedAt" label="完成时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              :loading="downloadingReportId === row.id"
              @click="handleDownloadDocument(row)"
            >
              下载 MD
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :loading="deletingReportId === row.id"
              @click="handleDeleteDocument(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无系统关系总结文档">
        <el-button type="primary" :loading="reportGenerating" @click="handleGenerateDocument">
          生成文档
        </el-button>
      </el-empty>
    </el-card>

    <el-card shadow="hover" class="mt" v-if="!showEmpty">
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

    <el-card shadow="hover" class="mt" v-if="!showEmpty">
      <template #header>核心贯穿链路（业务→功能→代码→数据）</template>
      <ul class="paths">
        <li v-for="(p, i) in displayPaths" :key="i">{{ p }}</li>
      </ul>
    </el-card>

    <!-- 空状态：当前项目尚无系统关系总览数据 -->
    <el-card shadow="hover" class="mt" v-if="showEmpty">
      <el-empty description="当前项目暂无系统关系总览数据">
        <template #description>
          <p>当前项目暂无系统关系总览数据。</p>
          <p class="empty-hint">请先完成代码/文档扫描并生成四层关系，或点击下方按钮基于当前项目扫描结果生成总览。</p>
        </template>
        <el-button type="primary" :loading="ingesting" @click="handleIngest">
          基于当前项目生成总览
        </el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import {
  downloadSystemOverviewDocument,
  exportSystemOverviewReport,
  generateSystemOverviewDocument,
  getCorePaths,
  getSystemOverview,
  ingestBuiltins,
  ingestFromGraph,
  type LayerMapping,
  type SystemOverview,
} from '@/api/system-overview.api'
import type { Report } from '@/api/report.api'
import { reportApi } from '@/api/report.api'

const route = useRoute()
const router = useRouter()
const projectId = (route.params.projectId as string) || 'self'
const versionId = (route.query.versionId as string) || undefined
const overview = ref<SystemOverview>()
const loaded = ref(false)
const ingesting = ref(false)
const exporting = ref(false)
const reportsLoading = ref(false)
const reportGenerating = ref(false)
const downloadingReportId = ref<string>()
const deletingReportId = ref<string>()
const queryingPaths = ref(false)
const queriedPaths = ref<string[]>([])
const systemOverviewReports = ref<Report[]>([])

// 域控件：业务域过滤
const selectedDomain = ref<string>('')
const pathFrom = ref<string>('')
const pathTo = ref<string>('')

// 计算属性：提取所有业务域
const domains = computed(() => {
  if (!overview.value?.mappings) return []
  return [...new Set(overview.value.mappings.map(m => m.businessDomain))]
})

// 加载完成且无四层映射数据时展示空状态引导
const showEmpty = computed(() => loaded.value && !(overview.value?.mappings?.length))

// self（LegacyGraph 自身）导入内置底座；真实项目基于扫描图谱生成总览
const ingestLabel = computed(() => (projectId === 'self' ? '导入事实底座' : '生成/更新总览'))

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
  } finally {
    loaded.value = true
  }
}

async function loadSystemOverviewReports() {
  reportsLoading.value = true
  try {
    const reports = await reportApi.listReports(projectId)
    const currentVersion = versionId || ''
    systemOverviewReports.value = reports
      .filter(report => report.reportType === 'SYSTEM_OVERVIEW')
      .filter(report => !currentVersion || report.versionId === currentVersion)
      .sort((a, b) => new Date(b.completedAt || b.generatedAt || 0).getTime()
        - new Date(a.completedAt || a.generatedAt || 0).getTime())
  } catch {
    ElMessage.error('加载总结文档失败')
  } finally {
    reportsLoading.value = false
  }
}

async function handleExport() {
  exporting.value = true
  try {
    const data = await exportSystemOverviewReport(projectId, versionId || 'default', 'MD')
    downloadPayload(data, `system-overview-${projectId}-${versionId || 'default'}.md`)
    ElMessage.success('系统关系总览报告已导出')
  } catch {
    ElMessage.error('导出失败')
  } finally {
    exporting.value = false
  }
}

async function handleGenerateDocument() {
  if (!overview.value?.mappings?.length) {
    ElMessage.warning('请先生成/更新总览，再生成总结文档')
    return
  }
  reportGenerating.value = true
  try {
    await generateSystemOverviewDocument(projectId, versionId)
    await loadSystemOverviewReports()
    ElMessage.success('总结文档已生成')
  } catch {
    ElMessage.error('生成总结文档失败')
  } finally {
    reportGenerating.value = false
  }
}

async function handleDownloadDocument(report: Report) {
  downloadingReportId.value = report.id
  try {
    const data = await downloadSystemOverviewDocument(projectId, report.id, 'MD')
    downloadPayload(data, `system-overview-${projectId}-${report.versionId || 'default'}.md`)
  } catch {
    ElMessage.error('下载总结文档失败')
  } finally {
    downloadingReportId.value = undefined
  }
}

async function handleDeleteDocument(report: Report) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${report.reportName || report.id}」？该报告文件与记录将被清除，且不可恢复。`,
      '删除总结文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  deletingReportId.value = report.id
  try {
    const ok = await reportApi.deleteReport(projectId, report.id)
    if (ok === false) {
      ElMessage.warning('该文档记录已不存在，已刷新列表')
    } else {
      ElMessage.success('已删除总结文档')
    }
    await loadSystemOverviewReports()
  } catch {
    ElMessage.error('删除总结文档失败')
  } finally {
    deletingReportId.value = undefined
  }
}

function downloadPayload(data: unknown, filename: string) {
  const payload = data instanceof Blob ? data : ((data as { data?: unknown })?.data ?? data)
  const blob = payload instanceof Blob
    ? payload
    : new Blob([payload as BlobPart], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function handleIngest() {
  ingesting.value = true
  try {
    // self（LegacyGraph 自身）用内置底座；真实项目基于当前项目扫描图谱生成
    const r = projectId === 'self'
      ? await ingestBuiltins(projectId, versionId)
      : await ingestFromGraph(projectId, versionId)
    if (r.claimCount > 0 || r.vectorCount > 0) {
      ElMessage.success(`已生成：向量 ${r.vectorCount}，Claim ${r.claimCount}，FAQ ${r.faqCount}`)
      await generateSystemOverviewDocument(projectId, versionId)
      await loadSystemOverviewReports()
    } else {
      ElMessage.warning('当前项目扫描图谱中未找到可用的 API 调用链，请先完成代码扫描')
    }
    await loadOverview()
  } catch {
    ElMessage.error('生成失败')
  } finally {
    ingesting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadOverview(), loadSystemOverviewReports()])
})
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
.empty-hint {
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
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
