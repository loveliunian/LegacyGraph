<template>
  <div class="repo-list">
    <div class="page-header">
      <h3>代码仓库配置</h3>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        添加仓库
      </el-button>
    </div>

    <el-table :data="repoList" v-loading="loading" border stripe>
      <el-table-column prop="repoName" label="仓库名称" width="180">
        <template #default="{ row }">
          <div class="repo-name">
            <el-icon :class="row.repoType === 'BACKEND' ? 'backend' : row.repoType === 'FULLSTACK' ? 'fullstack' : 'frontend'">
              <FolderOpened />
            </el-icon>
            <span>{{ row.repoName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="repoType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.repoType === 'BACKEND' ? 'primary' : row.repoType === 'FRONTEND' ? 'success' : 'warning'">
            {{ row.repoType === 'BACKEND' ? '后端' : row.repoType === 'FRONTEND' ? '前端' : '全栈' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gitUrl" label="Git地址" show-overflow-tooltip />
      <el-table-column prop="branchName" label="分支" width="120" />
      <el-table-column label="最近拉取" width="180">
        <template #default="{ row }">
          <span v-if="row.lastPullTime">{{ formatTime(row.lastPullTime) }}</span>
          <span v-else class="text-gray">未拉取</span>
        </template>
      </el-table-column>
      <el-table-column label="最近扫描" width="180">
        <template #default="{ row }">
          <span v-if="row.lastScanTime">{{ formatTime(row.lastScanTime) }}</span>
          <span v-else class="text-gray">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="getStatusType(row.status)">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="pullRepo(row)">拉取</el-button>
          <el-button type="success" link size="small" @click="scanRepo(row)">扫描</el-button>
          <el-button type="warning" link size="small" @click="showEditDialog(row)">修改</el-button>
          <el-button type="danger" link size="small" @click="deleteRepo(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper" v-if="total > 0">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="() => loadRepoList(1)"
      />
    </div>

    <el-empty v-if="repoList.length === 0" description="暂无仓库配置" />

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '修改代码仓库' : '添加代码仓库'" width="620px">
      <el-form :model="repoForm" label-width="100px">
        <el-form-item label="仓库名称" required>
          <el-input v-model="repoForm.repoName" placeholder="请输入仓库名称" />
        </el-form-item>
        <el-form-item label="仓库类型" required>
          <el-radio-group v-model="repoForm.repoType">
            <el-radio label="BACKEND">后端</el-radio>
            <el-radio label="FRONTEND">前端</el-radio>
            <el-radio label="FULLSTACK">全栈</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Git地址" required>
          <div style="display: flex; gap: 8px; width: 100%;">
            <el-input v-model="repoForm.gitUrl" placeholder="https://github.com/xxx/xxx.git" style="flex: 1;" />
            <el-button type="success" :loading="testingUrl" @click="testRepoUrl">测试连接</el-button>
          </div>
        </el-form-item>
        <el-form-item label="分支" required>
          <el-input v-model="repoForm.branch" placeholder="main / master" />
        </el-form-item>
        <!-- 全栈项目：分别指定前后端子路径 -->
        <template v-if="repoForm.repoType === 'FULLSTACK'">
          <el-form-item label="后端路径">
            <el-input v-model="repoForm.backendSubPath" placeholder="backend / server / src" />
          </el-form-item>
          <el-form-item label="前端路径">
            <el-input v-model="repoForm.frontendSubPath" placeholder="frontend / web / client" />
          </el-form-item>
        </template>
        <el-form-item label="认证方式" required>
          <el-radio-group v-model="repoForm.authType">
            <el-radio label="NONE">无需认证</el-radio>
            <el-radio label="TOKEN">Token</el-radio>
            <el-radio label="USER_PASSWORD">用户名密码</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="repoForm.authType === 'TOKEN'" label="Token" required>
          <el-input v-model="repoForm.token" type="password" placeholder="请输入Token" />
        </el-form-item>
        <el-form-item v-if="repoForm.authType === 'USER_PASSWORD'" label="用户名" required>
          <el-input v-model="repoForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item v-if="repoForm.authType === 'USER_PASSWORD'" label="密码" required>
          <el-input v-model="repoForm.password" type="password" placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="包含路径">
          <el-input v-model="repoForm.includePattern" type="textarea" placeholder="src/main/java/**" />
        </el-form-item>
        <el-form-item label="排除路径">
          <el-input v-model="repoForm.excludePattern" type="textarea" placeholder="**/test/**,**/node_modules/**" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRepo">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, FolderOpened } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { sourceApi } from '@/api/source.api'

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const repoList = ref<any[]>([])

const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const testingUrl = ref(false)

const repoForm = reactive({
  repoName: '',
  repoType: 'BACKEND' as 'BACKEND' | 'FRONTEND' | 'FULLSTACK',
  gitUrl: '',
  branch: 'main',
  authType: 'NONE' as 'NONE' | 'TOKEN' | 'USER_PASSWORD',
  token: '',
  username: '',
  password: '',
  backendSubPath: '',
  frontendSubPath: '',
  includePattern: '',
  excludePattern: ''
})

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    READY: 'success',
    PULLING: 'warning',
    FAILED: 'danger',
    INIT: 'info'
  }
  return map[status] || 'info'
}

/** 重置表单 */
const resetForm = () => {
  editingId.value = null
  Object.assign(repoForm, {
    repoName: '',
    repoType: 'BACKEND',
    gitUrl: '',
    branch: 'main',
    authType: 'NONE',
    token: '',
    username: '',
    password: '',
    backendSubPath: '',
    frontendSubPath: '',
    includePattern: '',
    excludePattern: ''
  })
}

const showCreateDialog = () => {
  resetForm()
  dialogVisible.value = true
}

const showEditDialog = (row: any) => {
  editingId.value = row.id
  Object.assign(repoForm, {
    repoName: row.repoName || '',
    repoType: row.repoType || 'BACKEND',
    gitUrl: row.gitUrl || '',
    branch: row.branchName || 'main',
    authType: row.authType || 'NONE',
    token: '',
    username: row.username || '',
    password: '',
    backendSubPath: row.backendSubPath || '',
    frontendSubPath: row.frontendSubPath || '',
    includePattern: row.includePattern || '',
    excludePattern: row.excludePattern || ''
  })
  dialogVisible.value = true
}

const buildPayload = () => ({
  repoName: repoForm.repoName,
  repoType: repoForm.repoType,
  gitUrl: repoForm.gitUrl,
  branchName: repoForm.branch,
  authType: repoForm.authType,
  username: repoForm.authType === 'USER_PASSWORD' ? repoForm.username : undefined,
  token: repoForm.authType === 'TOKEN' ? repoForm.token : undefined,
  backendSubPath: repoForm.repoType === 'FULLSTACK' ? repoForm.backendSubPath || undefined : undefined,
  frontendSubPath: repoForm.repoType === 'FULLSTACK' ? repoForm.frontendSubPath || undefined : undefined,
  includePattern: repoForm.includePattern || undefined,
  excludePattern: repoForm.excludePattern || undefined
})

const saveRepo = async () => {
  if (!repoForm.repoName || !repoForm.gitUrl || !repoForm.branch) {
    ElMessage.warning('请填写必填项')
    return
  }
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await sourceApi.updateCodeRepo(projectId, editingId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await sourceApi.createCodeRepo(projectId, payload)
      ElMessage.success('添加成功')
    }
    dialogVisible.value = false
    await loadRepoList()
  } catch (error) {
    ElMessage.error(editingId.value ? '修改失败' : '添加失败')
  }
}

/** 在对话框中测试 Git URL 连通性 */
const testRepoUrl = async () => {
  const url = repoForm.gitUrl.trim()
  if (!url) {
    ElMessage.warning('请先填写Git地址')
    return
  }
  testingUrl.value = true
  try {
    const res = await sourceApi.testRepoUrl(projectId, url)
    if (res?.success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败: ' + (res?.message || '无法连接到仓库'))
    }
  } catch {
    ElMessage.error('连接测试失败')
  } finally {
    testingUrl.value = false
  }
}

const pullRepo = async (row: any) => {
  try {
    ElMessage.info('开始拉取代码...')
    row.status = 'PULLING'
    await sourceApi.pullRepo(projectId, row.id)
    row.status = 'READY'
    row.lastPullTime = new Date().toISOString()
    ElMessage.success('拉取完成')
  } catch (error) {
    row.status = 'FAILED'
    ElMessage.error('拉取失败')
  }
}

const scanRepo = async (row: any) => {
  try {
    ElMessage.info('开始扫描代码...')
    row.status = 'SCANNING'
    const res = await sourceApi.scanRepo(projectId, row.id)
    row.lastScanTime = new Date().toISOString()
    row.status = 'READY'
    ElMessage.success('扫描已启动: ' + (res?.message || ''))
  } catch (error) {
    row.status = 'FAILED'
    ElMessage.error('扫描启动失败')
  }
}

const deleteRepo = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除仓库 ${row.repoName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await sourceApi.deleteCodeRepo(projectId, row.id)
    ElMessage.success('删除成功')
    await loadRepoList()
  } catch {
    // cancelled
  }
}

const loadRepoList = async (page?: number) => {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const result = await sourceApi.listCodeRepo(projectId, { pageNum: pageNum.value, pageSize: pageSize.value })
    repoList.value = result.list
    total.value = result.total
  } catch (error) {
    ElMessage.error('获取仓库列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadRepoList(page)
}

onMounted(async () => {
  await loadRepoList()
})
</script>

<style scoped>
.repo-list {
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

.repo-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.repo-name .el-icon {
  font-size: 18px;
}

.repo-name .el-icon.backend {
  color: #409eff;
}

.repo-name .el-icon.frontend {
  color: #67c23a;
}

.repo-name .el-icon.fullstack {
  color: #e6a23c;
}

.text-gray {
  color: #909399;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
