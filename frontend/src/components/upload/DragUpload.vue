<template>
  <div
    class="drag-upload-container"
    :class="{ 'drag-over': isDragOver, 'disabled': disabled }"
    @dragenter="handleDragEnter"
    @dragover.prevent="handleDragOver"
    @dragleave="handleDragLeave"
    @drop.prevent="handleDrop"
  >
    <input
      ref="fileInputRef"
      type="file"
      :accept="accept"
      :multiple="multiple"
      class="file-input"
      @change="handleFileInputChange"
    />

    <div class="upload-content" v-if="!uploading">
      <el-icon class="upload-icon" :size="48"><UploadFilled /></el-icon>
      <div class="upload-text">
        <span class="primary-text">将文件拖到此处</span>
        <span class="secondary-text">或 <el-button type="primary" link @click="openFileSelector">点击上传</el-button></span>
      </div>
      <div class="upload-hint" v-if="showHint">
        <el-tag size="small" type="info">支持格式: {{ hint }}</el-tag>
        <el-tag size="small" type="info" v-if="maxSize > 0">单文件最大: {{ formatFileSize(maxSize) }}</el-tag>
      </div>
    </div>

    <div class="uploading-content" v-else>
      <el-progress
        :percentage="uploadProgress"
        :status="uploadProgress === 100 ? 'success' : undefined"
        :stroke-width="8"
      />
      <div class="uploading-text">{{ uploadingText }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'

interface FileChunk {
  file: File
  chunk: Blob
  index: number
  total: number
  md5: string
  start: number
  end: number
}

const props = withDefaults(defineProps<{
  accept?: string
  multiple?: boolean
  maxSize?: number
  maxCount?: number
  disabled?: boolean
  showHint?: boolean
  hint?: string
  chunkSize?: number
  enableChunk?: boolean
}>(), {
  accept: '*',
  multiple: true,
  maxSize: 100 * 1024 * 1024,
  maxCount: 10,
  disabled: false,
  showHint: true,
  hint: '所有格式',
  chunkSize: 5 * 1024 * 1024,
  enableChunk: true
})

const emit = defineEmits<{
  'update:modelValue': [files: File[]]
  change: [files: File[]]
  success: [files: File[]]
  error: [message: string]
  progress: [progress: number]
}>()

const fileInputRef = ref<HTMLInputElement>()
const isDragOver = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadedFiles = ref<File[]>([])

const uploadingText = computed(() => {
  if (uploadProgress === 100) return '上传完成'
  if (uploadProgress > 0) return `正在上传... ${uploadProgress}%`
  return '正在处理文件...'
})

function openFileSelector() {
  if (props.disabled || uploading.value) return
  fileInputRef.value?.click()
}

function handleDragEnter(e: DragEvent) {
  if (props.disabled) return
  isDragOver.value = true
}

function handleDragOver(e: DragEvent) {
  if (props.disabled) return
  e.dataTransfer!.dropEffect = 'copy'
}

function handleDragLeave(e: DragEvent) {
  if (props.disabled) return
  if (!e.relatedTarget || !(e.relatedTarget as Node).parentNode) {
    isDragOver.value = false
  }
}

function handleDrop(e: DragEvent) {
  if (props.disabled) return
  isDragOver.value = false

  const files = Array.from(e.dataTransfer?.files || [])
  if (files.length > 0) {
    processFiles(files)
  }
}

function handleFileInputChange(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files || [])
  if (files.length > 0) {
    processFiles(files)
  }
  target.value = ''
}

async function processFiles(files: File[]) {
  if (uploading.value) return

  const validFiles: File[] = []
  const errors: string[] = []

  if (files.length > props.maxCount) {
    errors.push(`最多只能上传 ${props.maxCount} 个文件`)
  }

  files.forEach(file => {
    if (props.maxSize > 0 && file.size > props.maxSize) {
      errors.push(`文件 ${file.name} 超过大小限制`)
      return
    }

    if (props.accept !== '*') {
      const acceptTypes = props.accept.split(',')
      const fileExt = '.' + file.name.split('.').pop()?.toLowerCase()
      const fileType = file.type.toLowerCase()
      const matched = acceptTypes.some(type => {
        type = type.trim().toLowerCase()
        return type === fileExt ||
               fileType.startsWith(type.replace('*', '')) ||
               (type.includes('*') && fileType.startsWith(type.split('/')[0]))
      })
      if (!matched) {
        errors.push(`文件 ${file.name} 格式不支持`)
        return
      }
    }

    validFiles.push(file)
  })

  if (errors.length > 0) {
    emit('error', errors.join('\n'))
    ElMessage.error(errors.join('\n'))
    return
  }

  if (validFiles.length === 0) return

  uploading.value = true
  uploadProgress.value = 0

  try {
    if (props.enableChunk && validFiles.some(f => f.size > props.chunkSize)) {
      await processChunkUpload(validFiles)
    } else {
      await simulateUpload(validFiles)
    }

    uploadedFiles.value = [...uploadedFiles.value, ...validFiles]
    emit('change', validFiles)
    emit('success', validFiles)
    ElMessage.success(`成功上传 ${validFiles.length} 个文件`)
  } catch (error) {
    emit('error', error instanceof Error ? error.message : '上传失败')
    ElMessage.error('上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

async function simulateUpload(files: File[]) {
  const totalSize = files.reduce((sum, f) => sum + f.size, 0)
  let uploadedSize = 0

  for (const file of files) {
    const chunkSize = Math.floor(file.size / 10) || file.size
    for (let i = 0; i < 10; i++) {
      await new Promise(resolve => setTimeout(resolve, 50))
      uploadedSize += chunkSize
      uploadProgress.value = Math.min(100, Math.floor((uploadedSize / totalSize) * 100))
      emit('progress', uploadProgress.value)
    }
  }

  uploadProgress.value = 100
  emit('progress', 100)
  await new Promise(resolve => setTimeout(resolve, 300))
}

async function processChunkUpload(files: File[]) {
  for (const file of files) {
    if (file.size <= props.chunkSize) continue

    const totalChunks = Math.ceil(file.size / props.chunkSize)
    for (let i = 0; i < totalChunks; i++) {
      const start = i * props.chunkSize
      const end = Math.min(start + props.chunkSize, file.size)
      const chunk = file.slice(start, end)

      await new Promise(resolve => setTimeout(resolve, 50))

      const progress = Math.floor(((i + 1) / totalChunks) * 100)
      emit('progress', progress)
    }
  }
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
}

function clearFiles() {
  uploadedFiles.value = []
}

function removeFile(index: number) {
  uploadedFiles.value.splice(index, 1)
}

defineExpose({
  clearFiles,
  removeFile,
  openFileSelector
})
</script>

<style scoped>
.drag-upload-container {
  position: relative;
  width: 100%;
  min-height: 180px;
  padding: 30px 20px;
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  background: #fafafa;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s ease;
}

.drag-upload-container:hover {
  border-color: #409eff;
  background: #ecf5ff;
}

.drag-upload-container.drag-over {
  border-color: #67c23a;
  background: #f0f9eb;
}

.drag-upload-container.disabled {
  cursor: not-allowed;
  opacity: 0.6;
  background: #f5f7fa;
}

.file-input {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}

.upload-icon {
  color: #c0c4cc;
  margin-bottom: 16px;
  transition: color 0.3s;
}

.drag-upload-container:hover .upload-icon {
  color: #409eff;
}

.drag-over .upload-icon {
  color: #67c23a;
}

.upload-text {
  margin-bottom: 12px;
}

.primary-text {
  display: block;
  font-size: 16px;
  color: #606266;
  margin-bottom: 8px;
}

.secondary-text {
  font-size: 14px;
  color: #909399;
}

.upload-hint {
  display: flex;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
}

.uploading-content {
  padding: 20px;
}

.uploading-text {
  margin-top: 16px;
  color: #606266;
  font-size: 14px;
}
</style>
