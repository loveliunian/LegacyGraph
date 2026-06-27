<template>
  <div class="search-form">
    <el-form :inline="true" :model="form" class="search-form__form">
      <slot />
      <el-form-item class="search-form__actions">
        <el-button type="primary" @click="handleSearch" :loading="searching">
          <template #icon>
            <Search v-if="!searching" />
            <Loading v-else />
          </template>
          搜索
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { debounce } from 'lodash-es'
import { computed } from 'vue'
import { Search, Loading } from '@element-plus/icons-vue'

interface Props {
  model: Record<string, any>
  searching?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  searching: false
})

const emit = defineEmits<{
  search: []
  reset: []
}>()

const form = computed({
  get: () => props.model,
  set: () => {}
})

const debouncedSearch = debounce(() => {
  emit('search')
}, 300, { leading: false, trailing: true })

function handleSearch() {
  debouncedSearch()
}

function handleReset() {
  for (const key in props.model) {
    if (typeof props.model[key] === 'string') {
      props.model[key] = ''
    } else if (Array.isArray(props.model[key])) {
      props.model[key] = []
    } else if (typeof props.model[key] === 'boolean') {
      props.model[key] = false
    } else {
      props.model[key] = undefined
    }
  }
  emit('reset')
}
</script>

<style scoped>
.search-form {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  margin-bottom: 16px;
}

.search-form__form {
  margin-bottom: 0;
}

.search-form__actions {
  margin-left: auto;
}
</style>
