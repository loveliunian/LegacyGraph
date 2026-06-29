<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>待人工确认 (低置信度节点和关系)</span>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="demo-form-inline">
        <el-form-item label="版本ID">
          <el-input v-model="query.versionId" placeholder="扫描版本ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="最小置信度">
          <el-slider v-model="query.minConfidence" :min="0" :max="1" :step="0.05" />
          <span class="ml-2">{{ query.minConfidence }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="mt-4">
      <el-table :data="list" v-loading="loading" border>
        <el-table-column prop="targetType" label="类型" width="100">
          <template #default="{row}">
            <el-tag>{{ row.targetType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nodeName" label="名称" width="200" />
        <el-table-column prop="confidence" label="置信度" width="100">
          <template #default="{row}">
            <el-tag :type="row.confidence >= 0.7 ? 'warning' : 'danger'">
              {{ row.confidence.toFixed(2) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sourceType" label="来源" width="120" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{row}">
            <el-button type="success" link @click="confirm(row)">确认</el-button>
            <el-button type="danger" link @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
        class="mt-4"
      />
    </el-card>

    <!-- 确认对话框 -->
    <el-dialog v-model="confirmDialogVisible" title="人工确认">
      <el-form label-width="100px">
        <el-form-item label="节点名称">
          <el-input :value="currentItem?.nodeName" disabled />
        </el-form-item>
        <el-form-item label="置信度">
          <el-input :value="currentItem?.confidence" disabled />
        </el-form-item>
        <el-form-item label="审核意见">
          <el-input
            v-model="reviewComment"
            type="textarea"
            placeholder="请输入审核意见（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDialogVisible = false">取消</el-button>
        <el-button type="success" @click="doConfirm">确认正确</el-button>
        <el-button type="danger" @click="doReject">确认错误</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reviewApi } from '@/api'
import { validationApi } from '@/api'

const route = useRoute()
const projectId = route.params.projectId as string

interface PendingItem {
  id: string
  targetType: 'NODE' | 'EDGE'
  nodeName: string
  confidence: number
  sourceType: string
  description: string
}

const loading = ref(false)
const list = ref<PendingItem[]>([])
const total = ref(0)
const query = reactive({
  versionId: '',
  minConfidence: 0.7,
  pageNum: 1,
  pageSize: 20
})

const confirmDialogVisible = ref(false)
const currentItem = ref<PendingItem | null>(null)
const reviewComment = ref('')

const loadData = async () => {
  loading.value = true
  try {
    // 调用 reviewApi 获取待审核列表
    const data: any = await reviewApi.listPending(projectId, {
      targetType: undefined,
      graphType: undefined,
      minConfidence: query.minConfidence,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
    })
    if (data && data.list) {
      list.value = data.list
      total.value = data.total || data.list.length
    } else {
      list.value = []
      total.value = 0
    }
  } catch (e) {
    console.error(e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const openConfirm = (item: PendingItem) => {
  currentItem.value = item
  reviewComment.value = ''
  confirmDialogVisible.value = true
}

const confirm = (item: PendingItem) => {
  openConfirm(item)
}

const reject = (item: PendingItem) => {
  openConfirm(item)
}

const doConfirm = async () => {
  if (!currentItem.value) return
  try {
    await validationApi.confirm({
      targetType: currentItem.value.targetType,
      targetId: currentItem.value.id,
      reviewStatus: 'CONFIRMED',
      comment: reviewComment.value
    })
    ElMessage.success('确认成功')
    confirmDialogVisible.value = false
    await loadData()
  } catch (e) {
    console.error(e)
  }
}

const doReject = async () => {
  if (!currentItem.value) return
  try {
    await validationApi.confirm({
      targetType: currentItem.value.targetType,
      targetId: currentItem.value.id,
      reviewStatus: 'REJECTED',
      comment: reviewComment.value
    })
    ElMessage.success('已驳回')
    confirmDialogVisible.value = false
    await loadData()
  } catch (e) {
    console.error(e)
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.mt-4 {
  margin-top: 1rem;
}

.ml-2 {
  margin-left: 0.5rem;
}
</style>
