<template>
  <div class="create-scan-dialog">
    <el-card class="step-card" shadow="never">
      <el-steps
        :active="currentStep"
        align-center
        finish-status="success">
        <el-step title="选择扫描范围" />
        <el-step title="选择扫描类型" />
        <el-step title="配置扫描参数" />
        <el-step title="确认并执行" />
      </el-steps>

      <div class="step-content">
        <!-- 步骤 1: 选择扫描范围 -->
        <div
          v-if="currentStep === 0"
          class="step-panel">
          <div class="section-header">
            <h4>选择代码仓库</h4>
            <el-button
              type="primary"
              link
              size="small"
              @click="toggleSelectAll('repo')">
              {{ repoIdsAllSelected ? '取消全选' : '全选' }}
            </el-button>
          </div>
          <el-checkbox-group v-model="scanForm.repoIds">
            <div class="checkbox-group">
              <el-checkbox
                v-for="repo in repoList"
                :key="repo.id"
                :label="repo.id">
                <div class="checkbox-item">
                  <span class="item-name">{{ repo.repoName }}</span>
                  <el-tag
                    size="small"
                    type="info">
                    {{ repoTypeText(repo.repoType) }}
                  </el-tag>
                </div>
              </el-checkbox>
            </div>
          </el-checkbox-group>

          <div
            class="section-header"
            style="margin-top: 24px;">
            <h4>选择数据库</h4>
            <el-button
              type="primary"
              link
              size="small"
              @click="toggleSelectAll('db')">
              {{ dbIdsAllSelected ? '取消全选' : '全选' }}
            </el-button>
          </div>
          <el-checkbox-group v-model="scanForm.dbIds">
            <div class="checkbox-group">
              <el-checkbox
                v-for="db in dbList"
                :key="db.id"
                :label="db.id">
                <div class="checkbox-item">
                  <span class="item-name">{{ db.connectionName }}</span>
                  <el-tag
                    size="small"
                    type="info">
                    {{ db.dbType }}
                  </el-tag>
                </div>
              </el-checkbox>
            </div>
          </el-checkbox-group>

          <div
            class="section-header"
            style="margin-top: 24px;">
            <h4>选择文档</h4>
            <el-button
              type="primary"
              link
              size="small"
              @click="toggleSelectAll('doc')">
              {{ docIdsAllSelected ? '取消全选' : '全选' }}
            </el-button>
          </div>
          <el-checkbox-group v-model="scanForm.docIds">
            <div class="checkbox-group">
              <el-checkbox
                v-for="doc in docList"
                :key="doc.id"
                :label="doc.id">
                <div class="checkbox-item">
                  <span class="item-name">{{ doc.docName }}</span>
                  <el-tag
                    size="small"
                    type="info">
                    {{ doc.fileType }}
                  </el-tag>
                </div>
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </div>

        <!-- 步骤 2: 选择扫描类型 -->
        <div
          v-if="currentStep === 1"
          class="step-panel">
          <h4>选择扫描类型</h4>
          <el-checkbox-group v-model="scanForm.scanTypes">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-checkbox value="CODE_SCAN">
                  <div class="scan-type-item">
                    <span class="type-name">代码扫描</span>
                    <span class="type-desc">解析 Controller、Service、Mapper、SQL 等，提取代码结构和调用关系</span>
                  </div>
                </el-checkbox>
              </el-col>
              <el-col :span="12">
                <el-checkbox value="DB_SCAN">
                  <div class="scan-type-item">
                    <span class="type-name">数据库扫描</span>
                    <span class="type-desc">扫描数据库表结构、字段、索引、约束，提取数据血缘关系</span>
                  </div>
                </el-checkbox>
              </el-col>
              <el-col :span="12">
                <el-checkbox value="DOC_PARSE">
                  <div class="scan-type-item">
                    <span class="type-name">文档解析</span>
                    <span class="type-desc">解析产品文档、API 文档，自动开启 AI 归纳提取业务规则、流程、接口定义</span>
                  </div>
                </el-checkbox>
              </el-col>
              <el-col :span="12">
                <el-checkbox value="GRAPHIFY_ANALYZE">
                  <div class="scan-type-item">
                    <span class="type-name">Graphify 分析</span>
                    <span class="type-desc">调用外部 Graphify 工具进行代码分析，生成调用图和依赖关系</span>
                  </div>
                </el-checkbox>
              </el-col>
            </el-row>
          </el-checkbox-group>
        </div>

        <!-- 步骤 3: 配置扫描参数 -->
        <div
          v-if="currentStep === 2"
          class="step-panel">
          <el-form
            :model="scanForm"
            label-width="160px"
            class="config-form">
            <el-form-item label="任务名称">
              <el-input
                v-model="scanForm.taskName"
                placeholder="例如: 2024-01-15 全量扫描" />
            </el-form-item>
            <el-form-item label="是否增量扫描">
              <el-switch v-model="scanForm.incremental" />
              <span class="form-tip">增量扫描只会处理新增或变更的文件，速度更快</span>
            </el-form-item>
            <el-form-item label="启用 AI 归纳">
              <el-switch v-model="scanForm.enableAi" />
              <span class="form-tip">使用 AI 对文档和代码进行语义分析，生成业务节点</span>
            </el-form-item>
            <el-form-item label="最低置信度阈值">
              <el-slider
                v-model="scanForm.minConfidence"
                :min="0"
                :max="1"
                :step="0.05" />
              <span class="form-tip">低于该置信度的推断结果将进入审核队列</span>
            </el-form-item>
            <el-form-item label="自动生成测试用例">
              <el-switch v-model="scanForm.autoGenerateTestCase" />
              <span class="form-tip">扫描完成后自动基于新图谱生成测试用例</span>
            </el-form-item>
          </el-form>
        </div>

        <!-- 步骤 4: 确认并执行 -->
        <div
          v-if="currentStep === 3"
          class="step-panel">
          <div class="confirm-section">
            <h4>扫描范围确认</h4>
            <el-descriptions
              border
              :column="2">
              <el-descriptions-item label="代码仓库">
                <el-tag
                  v-for="id in scanForm.repoIds"
                  :key="id"
                  size="small"
                  style="margin-right: 4px;">
                  {{ getRepoName(id) }}
                </el-tag>
                <span
                  v-if="scanForm.repoIds.length === 0"
                  class="text-gray">未选择</span>
              </el-descriptions-item>
              <el-descriptions-item label="数据库">
                <el-tag
                  v-for="id in scanForm.dbIds"
                  :key="id"
                  size="small"
                  style="margin-right: 4px;">
                  {{ getDbName(id) }}
                </el-tag>
                <span
                  v-if="scanForm.dbIds.length === 0"
                  class="text-gray">未选择</span>
              </el-descriptions-item>
              <el-descriptions-item label="文档">
                <el-tag
                  v-for="id in scanForm.docIds"
                  :key="id"
                  size="small"
                  style="margin-right: 4px;">
                  {{ getDocName(id) }}
                </el-tag>
                <span
                  v-if="scanForm.docIds.length === 0"
                  class="text-gray">未选择</span>
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <div class="confirm-section">
            <h4>扫描类型确认</h4>
            <el-descriptions
              border
              :column="2">
              <el-descriptions-item label="扫描类型">
                <el-tag
                  v-for="type in scanForm.scanTypes"
                  :key="type"
                  size="small"
                  type="success"
                  style="margin-right: 4px;">
                  {{ getScanTypeText(type) }}
                </el-tag>
                <span
                  v-if="scanForm.scanTypes.length === 0"
                  class="text-gray">未选择</span>
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <div class="confirm-section">
            <h4>扫描参数确认</h4>
            <el-descriptions
              border
              :column="2">
              <el-descriptions-item label="任务名称">{{ scanForm.taskName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="增量扫描">{{ scanForm.incremental ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item label="AI 归纳">{{ scanForm.enableAi ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item label="最低置信度">{{ (scanForm.minConfidence * 100).toFixed(0) }}%</el-descriptions-item>
              <el-descriptions-item label="自动生成测试">{{ scanForm.autoGenerateTestCase ? '是' : '否' }}</el-descriptions-item>
            </el-descriptions>
          </div>

          <el-alert
            title="预计执行时间: 约 5-10 分钟"
            type="info"
            :closable="false"
            style="margin-top: 24px;"
          />
        </div>
      </div>

      <div class="step-actions">
        <el-button
          v-if="currentStep > 0"
          @click="prevStep">
          上一步
        </el-button>
        <el-button
          v-if="currentStep < 3"
          type="primary"
          :disabled="!canProceed()"
          @click="nextStep">
          下一步
        </el-button>
        <el-button
          v-if="currentStep === 3"
          type="primary"
          :loading="submitting"
          @click="startScan">
          开始扫描
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { scanApi, sourceApi } from '@/api'
import { preloadDicts, dictLabel } from '@/utils/dict'

const props = defineProps<{
  projectId: string
}>()

const emit = defineEmits<{
  success: [versionId: string]
}>()

const currentStep = ref(0)
const submitting = ref(false)
const repoList = ref<any[]>([])
const dbList = ref<any[]>([])
const docList = ref<any[]>([])

const scanForm = reactive({
  taskName: '',
  repoIds: [] as string[],
  dbIds: [] as string[],
  docIds: [] as string[],
  scanTypes: [] as string[],
  baseDir: '',
  incremental: true,
  enableAi: true,
  minConfidence: 0.6,
  autoGenerateTestCase: true
})

const getRepoName = (id: string) => {
  const repo = repoList.value.find(r => r.id === id)
  return repo ? repo.repoName : id
}

const getDbName = (id: string) => {
  const db = dbList.value.find(d => d.id === id)
  return db ? db.connectionName : id
}

const getDocName = (id: string) => {
  const doc = docList.value.find(d => d.id === id)
  return doc ? doc.docName : id
}

const getScanTypeText = (type: string) => dictLabel('scan_type', type)

const repoTypeText = (type: string) => dictLabel('repo_type', type)

const canProceed = () => {
  if (currentStep.value === 0) {
    return scanForm.repoIds.length > 0 || scanForm.dbIds.length > 0 || scanForm.docIds.length > 0
  }
  if (currentStep.value === 1) {
    return scanForm.scanTypes.length > 0
  }
  if (currentStep.value === 2) {
    return !!scanForm.taskName
  }
  return true
}

const repoIdsAllSelected = computed(() =>
  repoList.value.length > 0 && scanForm.repoIds.length === repoList.value.length
)
const dbIdsAllSelected = computed(() =>
  dbList.value.length > 0 && scanForm.dbIds.length === dbList.value.length
)
const docIdsAllSelected = computed(() =>
  docList.value.length > 0 && scanForm.docIds.length === docList.value.length
)

const toggleSelectAll = (type: 'repo' | 'db' | 'doc') => {
  if (type === 'repo') {
    if (repoIdsAllSelected.value) {
      scanForm.repoIds = []
    } else {
      scanForm.repoIds = repoList.value.map(r => r.id)
    }
  } else if (type === 'db') {
    if (dbIdsAllSelected.value) {
      scanForm.dbIds = []
    } else {
      scanForm.dbIds = dbList.value.map(d => d.id)
    }
  } else if (type === 'doc') {
    if (docIdsAllSelected.value) {
      scanForm.docIds = []
    } else {
      scanForm.docIds = docList.value.map(d => d.id)
    }
  }
}

const prevStep = () => {
  if (currentStep.value > 0) {
    currentStep.value--
  }
}

const nextStep = () => {
  if (!canProceed()) {
    if (currentStep.value === 0) {
      ElMessage.warning('请至少选择一个扫描范围')
    } else if (currentStep.value === 1) {
      ElMessage.warning('请至少选择一个扫描类型')
    } else if (currentStep.value === 2) {
      ElMessage.warning('请填写任务名称')
    }
    return
  }
  if (currentStep.value < 3) {
    currentStep.value++
  }
}

const resetForm = () => {
  currentStep.value = 0
  const now = new Date()
  scanForm.taskName = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} 全量扫描`
  scanForm.repoIds = []
  scanForm.dbIds = []
  scanForm.docIds = []
  scanForm.scanTypes = []
  scanForm.baseDir = ''
  scanForm.incremental = true
  scanForm.enableAi = true
  scanForm.minConfidence = 0.6
  scanForm.autoGenerateTestCase = true
}

const startScan = async () => {
  submitting.value = true
  try {
    const hasFullstackRepo = repoList.value.some(
      r => scanForm.repoIds.includes(r.id) && r.repoType === 'FULLSTACK'
    )

    let effectiveScanTypes = [...scanForm.scanTypes]
    let addedTypes: string[] = []
    if (hasFullstackRepo) {
      if (!effectiveScanTypes.includes('DB_SCAN')) {
        effectiveScanTypes.push('DB_SCAN')
        addedTypes.push('DB_SCAN')
      }
      if (!effectiveScanTypes.includes('DOC_PARSE')) {
        effectiveScanTypes.push('DOC_PARSE')
        addedTypes.push('DOC_PARSE')
      }
      if (addedTypes.length > 0) {
        ElMessage.info(`检测到全栈项目，已自动启用: ${addedTypes.join(', ')} 扫描`)
      }
    }

    const res: any = await scanApi.create(props.projectId, {
      versionNo: scanForm.taskName.replace(/[\s-]/g, '_'),
      branchName: 'main',
      scanScope: JSON.stringify({
        scanTypes: effectiveScanTypes,
        repoIds: scanForm.repoIds,
        dbIds: scanForm.dbIds,
        docIds: scanForm.docIds,
        enableAi: scanForm.enableAi || hasFullstackRepo,
        autoGenerateTestCase: scanForm.autoGenerateTestCase,
        minConfidence: scanForm.minConfidence
      })
    })
    const versionId = typeof res === 'string' ? res : res.id || res
    await scanApi.start(props.projectId, versionId, scanForm.baseDir)
    ElMessage.success('扫描任务已创建并启动')
    emit('success', versionId)
    resetForm()
  } catch (error) {
    ElMessage.error('创建失败')
  } finally {
    submitting.value = false
  }
}

const loadSources = async () => {
  try {
    const [reposRes, dbsRes, docsRes] = await Promise.all([
      sourceApi.listCodeRepo(props.projectId, { pageNum: 1, pageSize: 100 }),
      sourceApi.listDbConnections(props.projectId, { pageNum: 1, pageSize: 100 }),
      sourceApi.listDocuments(props.projectId, { pageNum: 1, pageSize: 100 })
    ])
    repoList.value = (reposRes.list || reposRes || []).map((r: any) => ({
      id: r.id,
      repoName: r.repoName,
      repoType: r.repoType
    }))
    dbList.value = (dbsRes.list || dbsRes || []).map((d: any) => ({
      id: d.id,
      connectionName: d.connectionName,
      dbType: d.dbType
    }))
    docList.value = (docsRes.list || docsRes || []).map((d: any) => ({
      id: d.id,
      docName: d.docName,
      fileType: d.fileType || d.docType
    }))
  } catch (err) {
    console.error('获取数据源列表失败:', err)
  }
}

onMounted(async () => {
  preloadDicts(['repo_type', 'scan_type'])
  resetForm()
  await loadSources()
})
</script>

<style scoped>
.create-scan-dialog {
  padding: 0;
}

.step-card {
  border: none;
  box-shadow: none;
  padding: 0;
}

.step-content {
  min-height: 320px;
  padding: 24px 0;
}

.step-panel h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h4 {
  margin: 0;
}

.checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.checkbox-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-name {
  font-size: 14px;
  color: #303133;
}

.scan-type-item {
  padding: 8px 0;
}

.type-name {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 4px;
}

.type-desc {
  display: block;
  font-size: 12px;
  color: #909399;
}

.config-form {
  max-width: 700px;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.confirm-section {
  margin-bottom: 24px;
}

.confirm-section h4 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.step-actions {
  display: flex;
  justify-content: center;
  gap: 16px;
  padding-top: 24px;
  border-top: 1px solid #ebeef5;
}

.text-gray {
  color: #909399;
}
</style>
