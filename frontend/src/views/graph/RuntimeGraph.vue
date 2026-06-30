<template>
  <div class="runtime-graph">
    <div class="page-header">
      <h3>运行链路图谱</h3>
      <div class="header-actions">
        <el-select v-model="selectedEnv" placeholder="选择环境" size="small" style="width: 120px;">
          <el-option label="生产环境" value="prod" />
          <el-option label="测试环境" value="test" />
          <el-option label="开发环境" value="dev" />
        </el-select>
        <el-button type="primary" size="small" @click="refreshTraces" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新链路
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!hasRealTrace"
      type="warning"
      :closable="false"
      show-icon
      title="运行时 trace 采集尚未接入"
      description="当前页面基于扫描版本与图谱节点近似呈现服务拓扑，非真实运行时调用链路。通过 /lg/projects/{projectId}/runtime/traces 上报 span 后将展示真实 trace。"
      style="margin-bottom: 16px;"
    />

    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="trace-card">
          <template #header>
            <span>最近请求链路</span>
          </template>
          <div class="trace-list">
            <div v-if="traces.length === 0 && !loading" class="trace-empty">
              <el-empty description="暂无链路数据" :image-size="60" />
            </div>
            <div
              v-for="trace in traces"
              :key="trace.id"
              class="trace-item"
              :class="{ active: selectedTrace === trace.id }"
              @click="selectTrace(trace.id)"
            >
              <div class="trace-header">
                <el-tag size="small" :type="trace.status === 'success' ? 'success' : 'danger'">
                  {{ trace.status === 'success' ? '成功' : '失败' }}
                </el-tag>
                <span class="trace-duration">{{ trace.duration }}ms</span>
              </div>
              <div class="trace-api">{{ trace.api }}</div>
              <div class="trace-meta">
                <span>{{ trace.time }}</span>
                <span>{{ trace.serviceCount }} 个服务</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="graph-card">
          <div class="graph-toolbar">
            <el-button-group>
              <el-button size="small" :type="viewType === 'topology' ? 'primary' : ''" @click="viewType = 'topology'">
                服务拓扑
              </el-button>
              <el-button size="small" :type="viewType === 'timeline' ? 'primary' : ''" @click="viewType = 'timeline'">
                调用时序
              </el-button>
            </el-button-group>
            <el-button type="primary" size="small" @click="showErrorAnalysis">
              错误分析
            </el-button>
          </div>
          <div class="graph-container">
            <div v-if="loading" class="graph-state">
              <el-icon :size="32" class="is-loading"><Refresh /></el-icon>
              <p>加载中...</p>
            </div>
            <div v-else-if="!selectedTrace" class="graph-placeholder">
              <div class="placeholder-content">
                <el-icon :size="64" color="#c0c4cc"><Connection /></el-icon>
                <p>运行链路可视化</p>
                <p class="placeholder-tip">选择左侧请求链路查看调用拓扑</p>
                <div class="stats" v-if="traces.length > 0">
                  <el-tag type="primary">服务数: {{ services.length }}</el-tag>
                  <el-tag type="success">实例数: {{ instanceCount }}</el-tag>
                  <el-tag type="danger">错误数: {{ errorCount }}</el-tag>
                </div>
              </div>
            </div>
            <div v-else class="trace-detail-view">
              <!-- 拓扑图：简化示意，VueFlow 渲染 -->
              <VueFlow
                v-if="traceNodes.length > 0"
                :nodes="traceNodes"
                :edges="traceEdges"
                fit-view
                class="trace-flow"
              >
                <template #node-custom="nodeProps">
                  <div
                    class="trace-service-node"
                    :class="{
                      'healthy': nodeProps.data.health === 'healthy',
                      'error': nodeProps.data.health === 'error'
                    }"
                  >
                    <div class="service-name">{{ nodeProps.data.label }}</div>
                    <div class="service-meta">{{ nodeProps.data.duration }}ms</div>
                  </div>
                </template>
              </VueFlow>
              <div v-else class="graph-placeholder">
                <p>该链路暂无详细拓扑数据</p>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="service-card">
          <template #header>
            <span>服务详情</span>
          </template>
          <div v-if="services.length === 0 && !loading" class="trace-empty">
            <el-empty description="暂无服务数据" :image-size="60" />
          </div>
          <div class="service-list" v-else>
            <div
              v-for="service in services"
              :key="service.id"
              class="service-item"
              :class="{ active: selectedService === service.id }"
              @click="selectService(service.id)"
            >
              <div class="service-header">
                <span class="service-name">{{ service.name }}</span>
                <el-tag size="small" :type="service.health === 'healthy' ? 'success' : 'danger'">
                  {{ service.health === 'healthy' ? '健康' : '异常' }}
                </el-tag>
              </div>
              <div class="service-stats">
                <span>QPS: {{ service.qps }}</span>
                <span>P99: {{ service.p99 }}ms</span>
                <span>错误率: {{ service.errorRate }}%</span>
              </div>
            </div>
          </div>
        </el-card>

        <el-card class="slow-card" style="margin-top: 16px;">
          <template #header>
            <span>慢请求 Top 5</span>
          </template>
          <div v-if="slowRequests.length === 0 && !loading" class="trace-empty">
            <el-empty description="暂无慢请求数据" :image-size="40" />
          </div>
          <div class="slow-list" v-else>
            <div class="slow-item" v-for="slow in slowRequests" :key="slow.id">
              <div class="slow-api">{{ slow.api }}</div>
              <div class="slow-info">
                <span class="slow-duration">{{ slow.avgDuration }}ms</span>
                <span class="slow-count">{{ slow.count }} 次</span>
              </div>
              <el-progress :percentage="slow.warningLevel * 20" :status="slow.warningLevel >= 4 ? 'exception' : 'warning'" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Connection } from '@element-plus/icons-vue'
import { graphApi, traceApi } from '@/api'
import { loadScanVersions } from '@/utils/versionsCache'
import { useRoute } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)

const selectedEnv = ref('prod')
const viewType = ref('topology')
const selectedTrace = ref<string | null>(null)
const selectedService = ref<string | null>(null)
const loading = ref(false)

// 从后端加载的真实数据，初始为空
const traces = ref<any[]>([])
const services = ref<any[]>([])
const slowRequests = ref<any[]>([])

// 拓扑图数据
const traceNodes = ref<any[]>([])
const traceEdges = ref<any[]>([])

const instanceCount = ref(0)
const errorCount = ref(0)
// 是否已有真实上报的 trace 数据（决定是否显示"未接入"提示）
const hasRealTrace = ref(false)

/**
 * 从后端加载运行链路数据
 * 优先使用真实 trace 拓扑（traceApi）；无上报数据时回退到 scan-versions + 统一图谱近似
 */
async function loadTraces() {
  if (!projectId.value) return
  loading.value = true
  try {
    // 1) 优先加载真实运行时 trace 拓扑
    const topo = await traceApi.getTopology(projectId.value).catch(() => null) as any
    if (topo && topo.totalSpans > 0) {
      hasRealTrace.value = true
      services.value = (topo.services || []).map((s: any, idx: number) => ({
        id: s.name || `service-${idx}`,
        name: s.name,
        health: s.errorCount > 0 ? 'error' : 'healthy',
        qps: 0,
        p99: Math.round(s.avgDurationMs || 0),
        errorRate: s.spanCount > 0 ? +(s.errorCount / s.spanCount * 100).toFixed(1) : 0,
      }))
      instanceCount.value = topo.totalSpans
      errorCount.value = (topo.services || []).reduce((sum: number, s: any) => sum + (s.errorCount || 0), 0)

      // 服务拓扑边
      traceNodes.value = services.value.map((s, idx) => ({
        id: s.id,
        type: 'custom',
        position: { x: 100 + (idx % 4) * 200, y: 150 + Math.floor(idx / 4) * 150 },
        data: { label: s.name, health: s.health, duration: s.p99 },
      }))
      traceEdges.value = (topo.calls || []).map((c: any, idx: number) => ({
        id: `e${idx}`,
        source: c.from,
        target: c.to,
        label: `CALLS x${c.callCount}${c.errorCount > 0 ? ` (${c.errorCount} err)` : ''}`,
      }))

      // 链路列表
      const recent = await traceApi.listTraces(projectId.value, undefined, 50).catch(() => []) as any
      traces.value = (recent || []).map((t: any) => ({
        id: t.id,
        status: t.status === 'ERROR' ? 'error' : 'success',
        duration: t.durationMs || 0,
        api: t.operationName || t.serviceName,
        time: t.startedAt || t.createdAt || '',
        serviceCount: 1,
      }))
      selectInitialTrace()
      return
    }

    // 2) 回退：扫描版本 + 统一图谱近似
    hasRealTrace.value = false
    const versions = await loadScanVersions(projectId.value)
    if (versions && Array.isArray(versions) && versions.length > 0) {
      traces.value = versions.map((v: any, idx: number) => ({
        id: v.id || `trace-${idx}`,
        status: v.scanStatus === 'SUCCESS' || v.scanStatus === 'FINISHED' ? 'success' :
                v.scanStatus === 'FAILED' ? 'error' : 'success',
        duration: 0,
        api: v.branchName || v.versionNo || `扫描版本 ${idx + 1}`,
        time: v.createdAt || '',
        serviceCount: v.nodeCount || 0,
      }))
    }

    // 加载统一图谱获取节点用作服务拓扑
    const lastVersion = versions?.[0]
    if (lastVersion?.id) {
      const graphData = await graphApi.getUnifiedGraph(projectId.value, lastVersion.id, 0) as any
      if (graphData?.nodes) {
        // 从图谱节点中提取 "服务" 类节点
        const serviceNodes = (graphData.nodes || []).filter((n: any) =>
          n.type === 'Service' || n.type === 'service'
        )
        services.value = serviceNodes.map((n: any, idx: number) => ({
          id: n.id || idx,
          name: n.label || n.key || n.id || `service-${idx}`,
          health: n.status === 'approved' || n.status === 'CONFIRMED' ? 'healthy' : 'error',
          qps: 0,
          p99: 0,
          errorRate: 0,
        }))
        instanceCount.value = graphData.nodes.length
        errorCount.value = graphData.nodes.filter((n: any) =>
          n.status === 'REJECTED' || n.status === 'PENDING'
        ).length
      }
    }
    selectInitialTrace()
  } catch (error) {
    console.error('加载运行链路数据失败', error)
    ElMessage.warning('加载运行链路数据失败')
  } finally {
    loading.value = false
  }
}

function selectInitialTrace() {
  if (selectedTrace.value) return
  if (traces.value.length > 0) {
    const traceId = String(traces.value[0].id)
    selectedTrace.value = traceId
    loadTraceDetail(traceId)
    return
  }
  if (services.value.length > 0) {
    const traceId = 'topology'
    selectedTrace.value = traceId
    loadTraceDetail(traceId)
  }
}

/**
 * 加载指定链路的详细拓扑
 */
function loadTraceDetail(traceId: string) {
  // 简化实现：从 services 数据生成拓扑节点
  traceNodes.value = services.value.slice(0, 5).map((s, idx) => ({
    id: s.id,
    type: 'custom',
    position: { x: 100 + idx * 200, y: 200 },
    data: {
      label: s.name,
      health: s.health,
      duration: s.p99 || 0,
    },
  }))
  traceEdges.value = []
  for (let i = 0; i < traceNodes.value.length - 1; i++) {
    traceEdges.value.push({
      id: `e${i}`,
      source: traceNodes.value[i].id,
      target: traceNodes.value[i + 1].id,
      label: 'CALLS',
    })
  }
}

const selectTrace = (id: string) => {
  selectedTrace.value = selectedTrace.value === id ? null : id
  if (selectedTrace.value) {
    loadTraceDetail(selectedTrace.value)
  } else {
    traceNodes.value = []
    traceEdges.value = []
  }
}

const selectService = (id: string) => {
  selectedService.value = selectedService.value === id ? null : id
}

const refreshTraces = async () => {
  await loadTraces()
  ElMessage.success('链路已刷新')
}

const showErrorAnalysis = () => {
  // 过滤出状态异常的服务节点进行分析
  const errorNodes = services.value.filter(s => s.health === 'error')
  if (errorNodes.length === 0) {
    ElMessage.info('当前无异常服务')
    return
  }
  ElMessage.warning(`发现 ${errorNodes.length} 个异常服务: ${errorNodes.map(s => s.name).join(', ')}`)
}

onMounted(async () => {
  await loadTraces()
})
</script>

<style scoped>
.runtime-graph {
  padding: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.trace-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 500px;
  overflow-y: auto;
}

.trace-empty {
  padding: 20px 0;
}

.trace-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid #ebeef5;
}

.trace-item:hover {
  background: #f5f7fa;
}

.trace-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.trace-duration {
  font-size: 12px;
  font-weight: 500;
  color: #303133;
}

.trace-api {
  font-size: 13px;
  color: #303133;
  font-family: monospace;
  margin-bottom: 4px;
}

.trace-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #909399;
}

.graph-card {
  height: 100%;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.graph-container {
  min-height: 500px;
  background: #fafafa;
  border-radius: 4px;
}

.graph-state {
  height: 500px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.graph-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-content {
  text-align: center;
  color: #909399;
}

.placeholder-content p {
  margin: 12px 0 4px 0;
  font-size: 16px;
}

.placeholder-tip {
  font-size: 12px !important;
}

.stats {
  margin-top: 20px;
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.trace-detail-view {
  height: 500px;
}

.trace-flow {
  width: 100%;
  height: 100%;
}

.trace-service-node {
  padding: 8px 14px;
  border-radius: 8px;
  background: #fff;
  border: 2px solid #ddd;
  text-align: center;
  min-width: 100px;
}

.trace-service-node.healthy {
  border-color: #67c23a;
  background: #f0f9eb;
}

.trace-service-node.error {
  border-color: #f56c6c;
  background: #fef0f0;
}

.service-name {
  font-weight: 600;
  font-size: 12px;
}

.service-meta {
  font-size: 10px;
  color: #999;
  margin-top: 2px;
}

.service-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.service-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid #ebeef5;
}

.service-item:hover {
  background: #f5f7fa;
}

.service-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.service-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.service-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  font-family: monospace;
}

.service-stats {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #909399;
}

.slow-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.slow-item {
  padding-bottom: 12px;
  border-bottom: 1px dashed #ebeef5;
}

.slow-api {
  font-size: 12px;
  color: #303133;
  font-family: monospace;
  margin-bottom: 4px;
}

.slow-info {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.slow-duration {
  font-size: 12px;
  font-weight: 500;
  color: #f56c6c;
}

.slow-count {
  font-size: 11px;
  color: #909399;
}
</style>
