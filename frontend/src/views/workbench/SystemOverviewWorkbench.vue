<template>
  <div class="system-overview-workbench">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>系统关系总览 — 业务 / 功能 / 代码 / 数据</span>
          <el-button type="primary" :loading="ingesting" @click="handleIngest">
            导入事实底座
          </el-button>
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
        <el-button type="primary" @click="handlePathQuery">查询路径</el-button>
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
        <li v-for="(p, i) in overview?.corePaths ?? []" :key="i">{{ p }}</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { getSystemOverview, ingestBuiltins, type SystemOverview, type LayerMapping } from '@/api/system-overview.api'

const route = useRoute()
const projectId = (route.params.projectId as string) || 'self'
const overview = ref<SystemOverview>()
const ingesting = ref(false)

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
        m.businessDomain.includes(pathFrom.value) ||
        m.capability.includes(pathFrom.value) ||
        m.feature.includes(pathFrom.value) ||
        m.controller.includes(pathFrom.value) ||
        m.codeModule.includes(pathFrom.value)
      
      const matchTo = !pathTo.value ||
        m.dataTables.some(t => t.includes(pathTo.value)) ||
        m.apiPath.includes(pathTo.value)
      
      return matchFrom && matchTo
    })
  }
  
  return filtered
})

// 域变更处理
function handleDomainChange(domain: string) {
  selectedDomain.value = domain
}

// 路径查询处理
function handlePathQuery() {
  if (!pathFrom.value && !pathTo.value) {
    ElMessage.warning('请输入起点或终点')
    return
  }
  ElMessage.success(`查询路径：${pathFrom.value || '任意'} → ${pathTo.value || '任意'}`)
}

// 下钻处理
function handleDrillDown(row: LayerMapping) {
  ElMessage.info(`下钻到：${row.controller} - ${row.apiPath}`)
  // TODO: 跳转到图谱可视化或详情页面
}

async function loadOverview() {
  try {
    overview.value = await getSystemOverview(projectId)
  } catch {
    ElMessage.error('加载系统关系总览失败')
  }
}

async function handleIngest() {
  ingesting.value = true
  try {
    const r = await ingestBuiltins(projectId)
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
