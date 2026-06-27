<template>
  <div class="code-viewer">
    <div class="code-viewer__header" v-if="showHeader">
      <span class="code-viewer__language">{{ language }}</span>
      <el-button link size="small" @click="copyCode">
        <el-icon><CopyDocument /></el-icon>
        复制
      </el-button>
    </div>
    <div class="code-viewer__content">
      <pre><code :class="`language-${language}`">{{ code }}</code></pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

interface Props {
  code: string
  language?: string
  showHeader?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  language: 'plaintext',
  showHeader: true,
})

async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.code)
    ElMessage.success('复制成功')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}
</script>

<style scoped>
.code-viewer {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
  background: #fafafa;
}

.code-viewer__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #dcdfe6;
}

.code-viewer__language {
  font-size: 12px;
  color: #909399;
  font-weight: 500;
}

.code-viewer__content {
  padding: 12px;
  overflow-x: auto;
  max-height: 400px;
}

.code-viewer__content pre {
  margin: 0;
}

.code-viewer__content code {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 13px;
  line-height: 1.5;
  color: #333;
}
</style>
