<template>
  <div class="test-case-list">
    <div class="page-header">
      <h3>测试用例</h3>
      <div class="header-actions">
        <el-select
          v-model="filters.caseType"
          placeholder="用例类型"
          size="small"
          clearable
          style="width: 140px;">
          <el-option
            label="API测试"
            value="API" />
          <el-option
            label="端到端测试"
            value="E2E" />
          <el-option
            label="数据库断言"
            value="DB_ASSERTION" />
          <el-option
            label="业务规则测试"
            value="BUSINESS_RULE" />
        </el-select>
        <el-select
          v-model="filters.status"
          placeholder="状态"
          size="small"
          clearable
          style="width: 120px;">
          <el-option
            label="草稿"
            value="DRAFT" />
          <el-option
            label="已确认"
            value="CONFIRMED" />
          <el-option
            label="已禁用"
            value="DISABLED" />
        </el-select>
        <el-button
          type="primary"
          size="small"
          @click="showGenerateDialog">
          <el-icon><MagicStick /></el-icon>
          AI 生成用例
        </el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="caseList"
      border
      stripe>
      <el-table-column
        prop="caseNo"
        label="用例编号"
        width="140" />
      <el-table-column
        prop="caseName"
        label="用例名称"
        min-width="250"
        show-overflow-tooltip />
      <el-table-column
        prop="caseType"
        label="类型"
        width="120">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="getCaseTypeColor(row.caseType)">
            {{ getCaseTypeText(row.caseType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="featureName"
        label="关联功能"
        min-width="150"
        show-overflow-tooltip />
      <el-table-column
        prop="apiPath"
        label="API路径"
        min-width="200"
        show-overflow-tooltip />
      <el-table-column
        prop="assertionCount"
        label="断言数"
        width="100"
        align="center">
        <template #default="{ row }">
          <el-tag
            size="small"
            type="info">
            {{ row.assertionCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="generateType"
        label="生成方式"
        width="100">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.generateType === 'AI_GENERATED' ? 'warning' : 'info'">
            {{ row.generateType === 'AI_GENERATED' ? 'AI生成' : '手动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="status"
        label="状态"
        width="100">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="lastRunStatus"
        label="最近运行"
        width="100">
        <template #default="{ row }">
          <el-tag
            v-if="row.lastRunStatus"
            size="small"
            :type="row.lastRunStatus === 'PASSED' ? 'success' : 'danger'">
            {{ row.lastRunStatus === 'PASSED' ? '通过' : '失败' }}
          </el-tag>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="lastRunTime"
        label="运行时间"
        width="180">
        <template #default="{ row }">
          <span v-if="row.lastRunTime">{{ formatTime(row.lastRunTime) }}</span>
          <span
            v-else
            class="text-gray">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="250"
        fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="viewDetail(row)">
            查看
          </el-button>
          <el-button
            type="success"
            link
            size="small"
            @click="runCase(row)">
            运行
          </el-button>
          <el-button
            type="warning"
            link
            size="small"
            @click="editCase(row)">
            编辑
          </el-button>
          <el-button
            type="danger"
            link
            size="small"
            @click="deleteCase(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="pagination.page"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top: 20px; justify-content: flex-end;"
    />

    <el-dialog
      v-model="generateDialogVisible"
      title="AI 生成测试用例"
      width="700px">
      <el-form label-width="120px">
        <el-form-item label="扫描版本">
          <el-select
            v-model="generateForm.versionId"
            placeholder="选择扫描版本"
            style="width: 100%;"
            @focus="loadVersionsForGenerate">
            <el-option
              v-for="v in generateVersions"
              :key="v.id"
              :label="`${v.versionNumber || v.versionNo || v.id} (${v.nodeCount || 0} 节点)`"
              :value="v.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="节点类型">
          <el-checkbox-group v-model="generateForm.nodeTypes">
            <el-checkbox label="ApiEndpoint">API端点</el-checkbox>
            <el-checkbox label="Feature">功能模块</el-checkbox>
            <el-checkbox label="Controller">控制器</el-checkbox>
            <el-checkbox label="Service">服务类</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="用例类型">
          <el-checkbox-group v-model="generateForm.caseTypes">
            <el-checkbox value="API">API测试</el-checkbox>
            <el-checkbox value="E2E">端到端测试</el-checkbox>
            <el-checkbox value="DB_ASSERTION">数据库断言</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="生成数量">
          <el-input-number
            v-model="generateForm.count"
            :min="1"
            :max="100" />
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="generateForm.remark"
            type="textarea"
            :rows="3"
            placeholder="请输入需要特别关注的业务场景..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="generating"
          @click="generateCases">
          开始生成
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailVisible"
      title="用例详情"
      size="60%">
      <div
        v-if="selectedCase"
        class="case-detail">
        <el-descriptions
          border
          :column="2">
          <el-descriptions-item label="用例编号">{{ selectedCase.caseNo }}</el-descriptions-item>
          <el-descriptions-item label="用例名称">{{ selectedCase.caseName }}</el-descriptions-item>
          <el-descriptions-item label="用例类型">{{ getCaseTypeText(selectedCase.caseType) }}</el-descriptions-item>
          <el-descriptions-item label="关联功能">{{ selectedCase.featureName }}</el-descriptions-item>
          <el-descriptions-item label="API路径">{{ selectedCase.apiPath || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ getStatusText(selectedCase.status) }}</el-descriptions-item>
        </el-descriptions>

        <div
          class="detail-section"
          style="margin-top: 24px;">
          <h4>测试步骤</h4>
          <el-timeline>
            <el-timeline-item
              v-for="(step, index) in selectedCase.steps"
              :key="index"
              :timestamp="`步骤 ${index + 1}`"
            >
              {{ step }}
            </el-timeline-item>
          </el-timeline>
        </div>

        <div
          class="detail-section"
          style="margin-top: 24px;">
          <h4>断言列表 ({{ selectedCase.assertionCount }})</h4>
          <ul class="assertion-list">
            <li
              v-for="(assertion, index) in selectedCase.assertions"
              :key="index">
              {{ assertion }}
            </li>
          </ul>
        </div>

        <div
          class="detail-section"
          style="margin-top: 24px;">
          <h4>关联节点</h4>
          <el-tag
            v-for="node in selectedCase.relatedNodeIds"
            :key="node"
            size="small"
            style="margin-right: 8px; margin-bottom: 8px;">
            {{ node }}
          </el-tag>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
// ✅ F-H1: post(...test-cases/.../run) → testApi.run

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { testApi, scanApi } from '@/api'
import { useProjectStore } from '@/stores/project'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.projectId as string || projectStore.currentProjectId

const loading = ref(false)
const generateDialogVisible = ref(false)
const detailVisible = ref(false)
const selectedCase = ref<any>(null)
const generating = ref(false)
const generateVersions = ref<any[]>([])

const filters = ref({
  caseType: '',
  status: ''
})

const pagination = ref({
  page: 1,
  pageSize: 20,
  total: 0
})

const generateForm = ref({
  versionId: '',
  nodeTypes: ['ApiEndpoint'] as string[],
  caseTypes: ['API', 'E2E'] as string[],
  count: 10,
  remark: ''
})

const caseList = ref<any[]>([])

async function loadData() {
  if (!projectId) return
  loading.value = true
  try {
    const res = await testApi.list(projectId!, {
      pageNum: pagination.value.page,
      pageSize: pagination.value.pageSize
    })
    caseList.value = res.list || []
    pagination.value.total = res.total || 0
  } catch (err) {
    console.error('获取测试用例列表失败:', err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  preloadDicts(['test_case_type', 'test_case_status'])
  loadData()
})

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getCaseTypeText = (type: string) => dictLabel('test_case_type', type)

const getCaseTypeColor = (type: string) => {
  const map: Record<string, string> = {
    API: 'primary',
    E2E: 'success',
    DB_ASSERTION: 'warning',
    BUSINESS_RULE: 'danger'
  }
  return map[type] || 'info'
}

const getStatusText = (status: string) => dictLabel('test_case_status', status)

const viewDetail = (row: any) => {
  selectedCase.value = row
  detailVisible.value = true
}

const runCase = async (row: any) => {
  try {
    ElMessage.info(`正在运行用例: ${row.caseName || row.caseCode}`)
    // 调用后端单用例执行接口
    await testApi.run(projectId!, row.id, 'test')
    ElMessage.success('测试用例已提交执行')
    loadData()
  } catch (error) {
    ElMessage.error('运行失败')
  }
}

const editCase = (row: any) => {
  router.push(`/projects/${projectId}/test-cases/${row.id}/edit`)
}

const deleteCase = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除用例 ${row.caseNo || row.caseCode} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await testApi.delete(projectId!, row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch {
    // cancelled
  }
}

const showGenerateDialog = () => {
  generateDialogVisible.value = true
  loadVersionsForGenerate()
}

const loadVersionsForGenerate = async () => {
  if (!projectId || generateVersions.value.length > 0) return
  try {
    const res: any = await scanApi.list(projectId!, { pageNum: 1, pageSize: 100 })
    const list = res?.list || res || []
    generateVersions.value = list
    // 自动选择最新的版本
    if (list.length > 0 && !generateForm.value.versionId) {
      generateForm.value.versionId = list[0].id
    }
  } catch (err) {
    console.error('加载扫描版本失败:', err)
  }
}

const generateCases = async () => {
  if (!generateForm.value.versionId) {
    ElMessage.warning('请先选择扫描版本')
    return
  }
  if (generateForm.value.nodeTypes.length === 0) {
    ElMessage.warning('请至少选择一种节点类型')
    return
  }
  generating.value = true
  try {
    await testApi.generate(projectId!, {
      versionId: generateForm.value.versionId,
      scope: {
        nodeTypes: generateForm.value.nodeTypes,
        priority: ['high']
      }
    })
    ElMessage.success('测试用例生成请求已提交')
    generateDialogVisible.value = false
    loadData()
  } catch (error) {
    ElMessage.error('生成失败')
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.test-case-list {
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

.text-gray {
  color: #909399;
}

.case-detail {
  padding: 10px 0;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.assertion-list {
  margin: 0;
  padding-left: 20px;
}

.assertion-list li {
  margin-bottom: 8px;
  color: #606266;
}
</style>
