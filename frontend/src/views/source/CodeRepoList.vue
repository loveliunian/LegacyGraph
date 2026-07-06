<template>
  <div class="repo-list">
    <div class="page-header">
      <h3>代码配置</h3>
      <el-button
        type="primary"
        @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        添加代码
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="repoList"
      border
      stripe>
      <el-table-column
        prop="repoName"
        label="仓库名称"
        width="180">
        <template #default="{ row }">
          <div class="repo-name">
            <el-icon :class="row.repoType === 'BACKEND' ? 'backend' : row.repoType === 'FULLSTACK' ? 'fullstack' : 'frontend'">
              <FolderOpened />
            </el-icon>
            <span>{{ row.repoName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="repoType"
        label="类型"
        width="100">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.repoType === 'BACKEND' ? 'primary' : row.repoType === 'FRONTEND' ? 'success' : 'warning'">
            {{ row.repoType === 'BACKEND' ? '后端' : row.repoType === 'FRONTEND' ? '前端' : '全栈' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="gitUrl"
        label="Git地址"
        show-overflow-tooltip />
      <el-table-column
        prop="branchName"
        label="分支"
        width="120" />
      <el-table-column
        label="最近拉取"
        width="180">
        <template #default="{ row }">
          <span v-if="row.lastPullTime">{{ formatTime(row.lastPullTime) }}</span>
          <span
            v-else
            class="text-gray">未拉取</span>
        </template>
      </el-table-column>
      <el-table-column
        label="最近扫描"
        width="180">
        <template #default="{ row }">
          <span v-if="row.lastScanTime">{{ formatTime(row.lastScanTime) }}</span>
          <span
            v-else
            class="text-gray">未扫描</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="status"
        label="状态"
        width="100">
        <template #default="{ row }">
          <el-tag
            v-if="row.status !== 'INIT'"
            size="small"
            :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="240"
        fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="pullRepo(row)">
            拉取
          </el-button>
          <el-button
            type="success"
            link
            size="small"
            @click="scanRepo(row)">
            扫描
          </el-button>
          <el-button
            type="warning"
            link
            size="small"
            @click="showEditDialog(row)">
            修改
          </el-button>
          <el-button
            type="danger"
            link
            size="small"
            @click="deleteRepo(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div
      v-if="total > 0"
      class="pagination-wrapper">
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

    <el-empty
      v-if="repoList.length === 0"
      description="暂无仓库配置" />

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '修改代码' : '添加代码'"
      width="620px">
      <el-form
        :model="repoForm"
        label-width="100px">
        <el-form-item
          label="仓库名称"
          required>
          <el-input
            v-model="repoForm.repoName"
            placeholder="请输入仓库名称" />
        </el-form-item>
        <el-form-item
          label="仓库类型"
          required>
          <el-radio-group v-model="repoForm.repoType">
            <el-radio label="BACKEND">后端</el-radio>
            <el-radio label="FRONTEND">前端</el-radio>
            <el-radio label="FULLSTACK">全栈</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          label="Git地址">
          <div style="display: flex; gap: 8px; width: 100%;">
            <el-input
              v-model="repoForm.gitUrl"
              placeholder="https://github.com/xxx/xxx.git"
              :disabled="!!repoForm.localPath"
              style="flex: 1;" />
            <el-button
              type="success"
              :loading="testingUrl"
              :disabled="!!repoForm.localPath"
              @click="testRepoUrl">
              测试连接
            </el-button>
          </div>
          <div v-if="repoForm.localPath" style="color: #909399; font-size: 12px; margin-top: 4px;">
            已填写本地路径，无需Git地址
          </div>
        </el-form-item>
        <el-form-item
          label="分支">
          <el-input
            v-model="repoForm.branch"
            placeholder="main / master"
            :disabled="!!repoForm.localPath" />
        </el-form-item>
        <!-- 全栈项目：分别指定前后端子路径 -->
        <template v-if="repoForm.repoType === 'FULLSTACK'">
          <el-form-item label="后端路径">
            <el-input
              v-model="repoForm.backendSubPath"
              placeholder="backend / server / src" />
          </el-form-item>
          <el-form-item label="前端路径">
            <el-input
              v-model="repoForm.frontendSubPath"
              placeholder="frontend / web / client" />
          </el-form-item>
        </template>
        <el-form-item label="本地路径">
          <div style="display: flex; gap: 8px; width: 100%;">
            <el-input
              v-model="repoForm.localPath"
              placeholder="点击右侧按钮选择文件夹"
              readonly
              style="flex: 1;" />
            <el-button type="primary" @click="openFolderPicker">
              <el-icon><FolderOpened /></el-icon>
              选择
            </el-button>
            <el-button
              v-if="repoForm.localPath"
              @click="clearLocalPath">
              清除
            </el-button>
          </div>
        </el-form-item>
        <el-form-item
          label="认证方式">
          <el-radio-group v-model="repoForm.authType" :disabled="!!repoForm.localPath">
            <el-radio label="NONE">无需认证</el-radio>
            <el-radio label="TOKEN">Token</el-radio>
            <el-radio label="USER_PASSWORD">用户名密码</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          v-if="repoForm.authType === 'TOKEN'"
          label="Token"
          required>
          <el-input
            v-model="repoForm.token"
            type="password"
            placeholder="请输入Token" />
        </el-form-item>
        <el-form-item
          v-if="repoForm.authType === 'USER_PASSWORD'"
          label="用户名"
          required>
          <el-input
            v-model="repoForm.username"
            placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item
          v-if="repoForm.authType === 'USER_PASSWORD'"
          label="密码"
          required>
          <el-input
            v-model="repoForm.password"
            type="password"
            placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="包含路径">
          <el-input
            v-model="repoForm.includePattern"
            type="textarea"
            placeholder="src/main/java/**" />
        </el-form-item>
        <el-form-item label="排除路径">
          <el-input
            v-model="repoForm.excludePattern"
            type="textarea"
            placeholder="**/test/**,**/node_modules/**" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          @click="saveRepo">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 文件夹选择器弹窗 -->
    <el-dialog
      v-model="folderPickerVisible"
      title="选择文件夹"
      width="520px"
      append-to-body>
      <div class="folder-picker">
        <div class="folder-picker-path">
          <el-button
            v-if="pickerParentPath"
            link
            type="primary"
            @click="navigateToFolder(pickerParentPath)">
            ⬆ 返回上级
          </el-button>
          <span class="current-path">{{ pickerCurrentPath }}</span>
        </div>
        <el-table
          v-loading="pickerLoading"
          :data="pickerEntries"
          highlight-current-row
          @current-change="onFolderSelect"
          style="width: 100%"
          max-height="360">
          <el-table-column label="文件夹">
            <template #default="{ row }">
              <div
                style="cursor: pointer; display: flex; align-items: center; gap: 6px;"
                @dblclick="navigateToFolder(row.path)">
                <el-icon><FolderOpened /></el-icon>
                <span>{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="folderPickerVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!pickerSelectedPath" @click="confirmFolderPick">
          选择当前目录
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, FolderOpened } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { sourceApi } from '@/api/source.api'
import { preloadDicts, dictLabel } from '@/utils/dict'

const route = useRoute()
const projectId = computed(() => route.params.projectId as string)

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
  localPath: '',
  includePattern: '',
  excludePattern: ''
})

/** 本地路径变化时清空Git相关字段 */
const onLocalPathChange = () => {
  if (repoForm.localPath) {
    repoForm.gitUrl = ''
    repoForm.branch = ''
    repoForm.authType = 'NONE'
    repoForm.token = ''
    repoForm.username = ''
    repoForm.password = ''
  }
}

const clearLocalPath = () => {
  repoForm.localPath = ''
}

// ---- 文件夹选择器 ----
const folderPickerVisible = ref(false)
const pickerLoading = ref(false)
const pickerCurrentPath = ref('')
const pickerParentPath = ref<string | null>(null)
const pickerEntries = ref<Array<{ name: string; path: string; isDirectory: boolean }>>([])
const pickerSelectedPath = ref('')

const openFolderPicker = async () => {
  folderPickerVisible.value = true
  pickerSelectedPath.value = ''
  await navigateToFolder(repoForm.localPath || '')
}

const navigateToFolder = async (path: string) => {
  pickerLoading.value = true
  pickerSelectedPath.value = ''
  try {
    const res = await sourceApi.browseDirectory(projectId.value, path || undefined)
    if (res) {
      pickerCurrentPath.value = res.currentPath
      pickerParentPath.value = res.parentPath
      pickerEntries.value = res.entries || []
    }
  } catch {
    ElMessage.error('读取目录失败')
  } finally {
    pickerLoading.value = false
  }
}

const onFolderSelect = (row: { name: string; path: string } | null) => {
  pickerSelectedPath.value = row ? row.path : ''
}

const confirmFolderPick = () => {
  repoForm.localPath = pickerSelectedPath.value || pickerCurrentPath.value
  onLocalPathChange()
  folderPickerVisible.value = false
}

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const getStatusType = (status: string): string => {
  const map: Record<string, string> = {
    READY: 'success',
    PULLING: 'warning',
    FAILED: 'danger'
  }
  return map[status] || 'info'
}

const getStatusText = (status: string) => dictLabel('repo_status', status)

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
    localPath: '',
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
    localPath: row.localPath || '',
    includePattern: row.includePattern || '',
    excludePattern: row.excludePattern || ''
  })
  dialogVisible.value = true
}

const buildPayload = () => {
  const hasLocalPath = !!repoForm.localPath
  
  return {
    repoName: repoForm.repoName,
    repoType: repoForm.repoType,
    gitUrl: hasLocalPath ? undefined : repoForm.gitUrl,
    branchName: hasLocalPath ? undefined : repoForm.branch,
    authType: hasLocalPath ? undefined : repoForm.authType,
    username: repoForm.authType === 'USER_PASSWORD' ? repoForm.username : undefined,
    token: repoForm.authType === 'TOKEN' ? repoForm.token : undefined,
    backendSubPath: repoForm.repoType === 'FULLSTACK' ? repoForm.backendSubPath || undefined : undefined,
    frontendSubPath: repoForm.repoType === 'FULLSTACK' ? repoForm.frontendSubPath || undefined : undefined,
    localPath: repoForm.localPath || undefined,
    includePattern: repoForm.includePattern || undefined,
    excludePattern: repoForm.excludePattern || undefined
  }
}

const saveRepo = async () => {
  if (!repoForm.repoName) {
    ElMessage.warning('请填写仓库名称')
    return
  }
  if (!repoForm.localPath && !repoForm.gitUrl) {
    ElMessage.warning('请填写本地路径或Git地址（至少填写一个）')
    return
  }
  if (!repoForm.localPath && !repoForm.branch) {
    ElMessage.warning('请填写分支名称')
    return
  }
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await sourceApi.updateCodeRepo(projectId.value, editingId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await sourceApi.createCodeRepo(projectId.value, payload)
      ElMessage.success('添加成功')
    }
    dialogVisible.value = false
    await loadRepoList()
  } catch {
    // 错误消息已由响应拦截器统一展示
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
    const res = await sourceApi.testRepoUrl(projectId.value, url)
    if (res?.success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败: ' + (res?.message || '无法连接到仓库'))
    }
  } catch {
    // 错误消息已由响应拦截器统一展示
  } finally {
    testingUrl.value = false
  }
}

const pullRepo = async (row: any) => {
  try {
    ElMessage.info('开始拉取代码...')
    row.status = 'PULLING'
    await sourceApi.pullRepo(projectId.value, row.id)
    row.status = 'READY'
    row.lastPullTime = new Date().toISOString()
    ElMessage.success('拉取完成')
  } catch {
    row.status = 'FAILED'
    // 错误消息已由响应拦截器统一展示（包含 git 具体失败原因）
  }
}

const scanRepo = async (row: any) => {
  try {
    ElMessage.info('开始扫描代码...')
    row.status = 'SCANNING'
    const res = await sourceApi.scanRepo(projectId.value, row.id)
    row.lastScanTime = new Date().toISOString()
    row.status = 'READY'
    ElMessage.success('扫描已启动: ' + (res?.message || ''))
  } catch {
    row.status = 'FAILED'
    // 错误消息已由响应拦截器统一展示
  }
}

const deleteRepo = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除仓库 ${row.repoName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await sourceApi.deleteCodeRepo(projectId.value, row.id)
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
    const result = await sourceApi.listCodeRepo(projectId.value, { pageNum: pageNum.value, pageSize: pageSize.value })
    repoList.value = result.list
    total.value = result.total
  } catch {
    // 错误消息已由响应拦截器统一展示
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  loadRepoList(page)
}

onMounted(async () => {
  preloadDicts(['repo_status'])
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
