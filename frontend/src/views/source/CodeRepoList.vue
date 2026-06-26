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
            <el-icon :class="row.repoType === 'BACKEND' ? 'backend' : 'frontend'">
              <FolderOpened />
            </el-icon>
            <span>{{ row.repoName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="repoType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.repoType === 'BACKEND' ? 'primary' : 'success'">
            {{ row.repoType === 'BACKEND' ? '后端' : '前端' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gitUrl" label="Git地址" show-overflow-tooltip />
      <el-table-column prop="branch" label="分支" width="120" />
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
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="pullRepo(row)">拉取</el-button>
          <el-button type="success" link size="small" @click="testConnection(row)">测试连接</el-button>
          <el-button type="danger" link size="small" @click="deleteRepo(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="repoList.length === 0" description="暂无仓库配置" />

    <el-dialog v-model="createDialogVisible" title="添加代码仓库" width="600px">
      <el-form :model="repoForm" label-width="100px">
        <el-form-item label="仓库名称" required>
          <el-input v-model="repoForm.repoName" placeholder="请输入仓库名称" />
        </el-form-item>
        <el-form-item label="仓库类型" required>
          <el-radio-group v-model="repoForm.repoType">
            <el-radio label="BACKEND">后端</el-radio>
            <el-radio label="FRONTEND">前端</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Git地址" required>
          <el-input v-model="repoForm.gitUrl" placeholder="https://github.com/xxx/xxx.git" />
        </el-form-item>
        <el-form-item label="分支" required>
          <el-input v-model="repoForm.branch" placeholder="main / master" />
        </el-form-item>
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
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createRepo">保存</el-button>
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

const route = useRoute()
const projectId = route.params.projectId as string

const loading = ref(false)
const createDialogVisible = ref(false)
const repoList = ref<any[]>([])

const repoForm = reactive({
  repoName: '',
  repoType: 'BACKEND' as 'BACKEND' | 'FRONTEND',
  gitUrl: '',
  branch: 'main',
  authType: 'NONE' as 'NONE' | 'TOKEN' | 'USER_PASSWORD',
  token: '',
  username: '',
  password: '',
  includePattern: '',
  excludePattern: ''
})

const formatTime = (time: string) => {
  return dayjs(time).format('YYYY-MM-DD HH:mm')
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

const showCreateDialog = () => {
  Object.assign(repoForm, {
    repoName: '',
    repoType: 'BACKEND',
    gitUrl: '',
    branch: 'main',
    authType: 'NONE',
    token: '',
    username: '',
    password: '',
    includePattern: '',
    excludePattern: ''
  })
  createDialogVisible.value = true
}

const createRepo = async () => {
  if (!repoForm.repoName || !repoForm.gitUrl || !repoForm.branch) {
    ElMessage.warning('请填写必填项')
    return
  }
  try {
    const newRepo = {
      id: Date.now().toString(),
      ...repoForm,
      status: 'INIT',
      createdAt: new Date().toISOString()
    }
    repoList.value.unshift(newRepo)
    ElMessage.success('添加成功')
    createDialogVisible.value = false
  } catch (error) {
    ElMessage.error('添加失败')
  }
}

const pullRepo = async (row: any) => {
  try {
    ElMessage.info('开始拉取代码...')
    row.status = 'PULLING'
    setTimeout(() => {
      row.status = 'READY'
      row.lastPullTime = new Date().toISOString()
      ElMessage.success('拉取完成')
    }, 2000)
  } catch (error) {
    ElMessage.error('拉取失败')
  }
}

const testConnection = async (row: any) => {
  try {
    ElMessage.info('正在测试连接...')
    setTimeout(() => {
      ElMessage.success('连接成功')
    }, 1000)
  } catch (error) {
    ElMessage.error('连接失败')
  }
}

const deleteRepo = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除仓库 ${row.repoName} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const index = repoList.value.findIndex(r => r.id === row.id)
    if (index > -1) {
      repoList.value.splice(index, 1)
    }
    ElMessage.success('删除成功')
  } catch {
    // cancelled
  }
}

onMounted(async () => {
  loading.value = true
  setTimeout(() => {
    repoList.value = [
      {
        id: '1',
        repoName: 'legacy-backend',
        repoType: 'BACKEND',
        gitUrl: 'https://github.com/example/legacy-backend.git',
        branch: 'main',
        lastPullTime: new Date().toISOString(),
        lastScanTime: new Date(Date.now() - 86400000).toISOString(),
        status: 'READY'
      },
      {
        id: '2',
        repoName: 'legacy-frontend',
        repoType: 'FRONTEND',
        gitUrl: 'https://github.com/example/legacy-frontend.git',
        branch: 'develop',
        lastPullTime: new Date().toISOString(),
        lastScanTime: new Date(Date.now() - 86400000).toISOString(),
        status: 'READY'
      }
    ]
    loading.value = false
  }, 500)
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

.text-gray {
  color: #909399;
}
</style>
