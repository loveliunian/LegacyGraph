<template>
  <div class="base-table">
    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="tableData"
      v-bind="$attrs"
      @sort-change="handleSortChange"
      @selection-change="handleSelectionChange"
    >
      <slot />
    </el-table>

    <div
      v-if="pagination"
      class="base-table__pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'

interface SortInfo {
  prop: string
  order: 'ascending' | 'descending' | null
}

interface Props {
  data: any[]
  loading?: boolean
  pagination?: boolean
  total?: number
  page?: number
  pageSize?: number
  remotePagination?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  data: () => [],
  loading: false,
  pagination: true,
  total: 0,
  page: 1,
  pageSize: 20,
  remotePagination: true
})

const emit = defineEmits<{
  'update:page': [page: number]
  'update:pageSize': [pageSize: number]
  'sort-change': [sortInfo: SortInfo]
  'selection-change': [selection: any[]]
}>()

const tableRef = ref<any>()
const currentPage = ref(props.page)
const pageSize = ref(props.pageSize)

const tableData = computed(() => {
  if (!props.pagination || props.remotePagination) {
    return props.data
  }
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return props.data.slice(start, end)
})

watch(() => props.page, (val) => {
  currentPage.value = val
})

watch(() => props.pageSize, (val) => {
  pageSize.value = val
})

function handleSortChange({ prop, order }: SortInfo) {
  emit('sort-change', { prop, order })
}

function handleSelectionChange(selection: any[]) {
  emit('selection-change', selection)
}

function handleSizeChange(size: number) {
  pageSize.value = size
  emit('update:pageSize', size)
}

function handleCurrentChange(page: number) {
  currentPage.value = page
  emit('update:page', page)
}

function clearSelection() {
  tableRef.value?.clearSelection()
}

function toggleRowSelection(row: any, selected?: boolean) {
  tableRef.value?.toggleRowSelection(row, selected)
}

function setCurrentRow(row: any) {
  tableRef.value?.setCurrentRow(row)
}

function sort(prop: string, order?: 'ascending' | 'descending') {
  tableRef.value?.sort(prop, order)
}

defineExpose({
  clearSelection,
  toggleRowSelection,
  setCurrentRow,
  sort
})
</script>

<style scoped>
.base-table {
  width: 100%;
}

.base-table__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
