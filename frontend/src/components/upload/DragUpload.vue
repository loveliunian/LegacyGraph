<template>
  <div
    class="drag-upload-container"
    :class="{ 'drag-over': isDragOver, 'disabled': disabled }">
    <input
      ref="fileInputRef"
      type="file"
      :accept="accept"
      :multiple="multiple"
      class="file-input"
      @change="handleFileInputChange"
    >

    <div
      v-if="uploadedFiles.length === 0 && !uploading"
      class="upload-content">
      <el-icon
        class="upload-icon"
        :size="48">
        <UploadFilled />
      </el-icon>
      <div class="upload-text">
        <span class="primary-text">将文件拖到此处</span>
        <span class="secondary-text">
          或 <el-button
            type="primary"
            link
            @click="openFileSelector">点击上传</el-button>
        </span>
      </div>
      <div
        v-if="showHint"
        class="upload-hint">
        <el-tag
          size="small"
          type="info">
          支持格式: {{ hint }}
        </el-tag>
        <el-tag
          v-if="maxSize > 0"
          size="small"
          type="info">
          单文件最大: {{ formatFileSize(maxSize) }}
        </el-tag>
        <el-tag
          v-if="enableChunk"
          size="small"
          type="success">
          支持分片上传
        </el-tag>
      </div>
    </div>

    <div
      v-else-if="uploading"
      class="uploading-content">
      <el-icon
        class="uploading-icon"
        :size="32">
        <Loading />
      </el-icon>
      <div class="uploading-text">{{ uploadingText }}</div>
      <el-progress
        :percentage="uploadProgress"
        :status="uploadProgress === 100 ? 'success' : undefined"
        :stroke-width="8"
        style="width: 80%; margin: 16px auto"
      />
      <div
        v-if="currentFile"
        class="uploading-stats">
        <span class="filename">{{ currentFile.name }}</span>
        <span class="speed">{{ formatFileSize(currentSpeed) }}/s</span>
        <span class="remaining">预计剩余 {{ estimatedTime }}s</span>
      </div>
      <el-button
        v-if="uploadProgress < 100"
        size="small"
        type="danger"
        plain
        @click="cancelUpload">
        取消上传
      </el-button>
    </div>

    <div
      v-else-if="uploadedFiles.length > 0"
      class="file-list">
      <div
        v-for="(file, index) in uploadedFiles"
        :key="index"
        class="file-item">
        <div class="file-info">
          <el-icon
            class="file-icon"
            :size="24">
            <Document />
          </el-icon>
          <div class="file-details">
            <span class="file-name">{{ file.name }}</span>
            <span class="file-size">{{ formatFileSize(file.size) }}</span>
          </div>
        </div>
        <div class="file-actions">
          <el-tooltip
            content="预览"
            placement="top">
            <el-button
              :icon="View"
              size="small"
              circle
              @click="previewFile(file)" />
          </el-tooltip>
          <el-tooltip
            content="下载"
            placement="top">
            <el-button
              :icon="Download"
              size="small"
              circle
              @click="downloadFile(file)" />
          </el-tooltip>
          <el-tooltip
            content="删除"
            placement="top">
            <el-button
              :icon="Close"
              size="small"
              circle
              type="danger"
              @click="removeFile(index)" />
          </el-tooltip>
        </div>
      </div>
      <div class="list-actions">
        <el-button
          size="small"
          @click="openFileSelector">
          <el-icon><Plus /></el-icon> 添加文件
        </el-button>
        <el-button
          size="small"
          type="primary"
          @click="submitUpload">
          <el-icon><Upload /></el-icon> 批量上传
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  UploadFilled,
  Loading,
  Document,
  View,
  Download,
  Close,
  Plus,
  Upload
} from '@element-plus/icons-vue'
import SparkMD5 from 'spark-md5'
import { useUserStore } from '@/stores/user'

interface ChunkUpload {
  file: File
  fileHash: string
  chunkSize: number
  chunks: Blob[]
  currentChunk: number
  uploadedChunks: Set<number>
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
  chunkThreshold?: number
  uploadUrl?: string
}>(), {
  accept: '*',
  multiple: true,
  maxSize: 100 * 1024 * 1024,
  maxCount: 10,
  disabled: false,
  showHint: true,
  hint: '所有格式',
  chunkSize: 5 * 1024 * 1024,
  enableChunk: true,
  chunkThreshold: 10 * 1024 * 1024,
  uploadUrl: '/api/upload'
})

const emit = defineEmits<{
  'update:modelValue': [files: File[]]
  change: [files: File[]]
  success: [files: File[]]
  error: [message: string]
  progress: [progress: number]
  chunkProgress: [file: File, chunkIndex: number, totalChunks: number]
}>()

const fileInputRef = ref<HTMLInputElement>()
const isDragOver = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadedFiles = ref<File[]>([])
const currentFile = ref<File | null>(null)
const currentSpeed = ref(0)
const estimatedTime = ref(0)
const abortController = ref<AbortController | null>(null)
const chunkUpload = ref<ChunkUpload | null>(null)
const startTime = ref(0)
const totalUploaded = ref(0)

const uploadingText = computed(() => {
  if (uploadProgress.value === 100) return '上传完成'
  if (uploadProgress.value > 0) return `正在上传... ${uploadProgress.value}%`
  return '处理文件中...'
})

function openFileSelector() {
  if (props.disabled || uploading.value) return
  fileInputRef.value?.click()
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

  const errors: string[] = []

  if (files.length + uploadedFiles.value.length > props.maxCount) {
    errors.push(`最多只能上传 ${props.maxCount} 个文件`)
  }

  const validFiles: File[] = []
  for (const file of files) {
    if (props.maxSize > 0 && file.size > props.maxSize) {
      errors.push(`文件 ${file.name} 超过大小限制`)
      continue
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
        continue
      }
    }

    validFiles.push(file)
  }

  if (errors.length > 0) {
    emit('error', errors.join('\n'))
    ElMessage.error(errors.join('\n'))
    return
  }

  if (validFiles.length === 0) return

  uploadedFiles.value = [...uploadedFiles.value, ...validFiles]
  emit('change', uploadedFiles.value)
}

async function calculateFileHash(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = (e) => {
      try {
        const hash = SparkMD5.hashBinary(e.target?.result as string)
        resolve(hash)
      } catch (err) {
        reject(err)
      }
    }
    reader.onerror = reject
    reader.readAsBinaryString(file)
  })
}

function createChunks(file: File): Blob[] {
  const chunks: Blob[] = []
  const totalChunks = Math.ceil(file.size / props.chunkSize)

  for (let i = 0; i < totalChunks; i++) {
    const start = i * props.chunkSize
    const end = Math.min(start + props.chunkSize, file.size)
    chunks.push(file.slice(start, end))
  }

  return chunks
}

async function uploadChunk(chunk: Blob, chunkIndex: number, totalChunks: number, fileHash: string): Promise<void> {
  const formData = new FormData()
  formData.append('chunk', chunk)
  formData.append('chunkIndex', chunkIndex.toString())
  formData.append('totalChunks', totalChunks.toString())
  formData.append('fileHash', fileHash)

  abortController.value = new AbortController()

  // 原生fetch需要手动添加认证头
  const userStore = useUserStore()
  const headers: Record<string, string> = {}
  if (userStore.accessToken) {
    headers['Authorization'] = `Bearer ${userStore.accessToken}`
  }

  try {
    const response = await fetch(props.uploadUrl, {
      method: 'POST',
      headers,
      body: formData,
      signal: abortController.value.signal
    })

    if (response.status === 401) {
      userStore.clearAuth()
      throw new Error('登录状态已过期，请重新登录')
    }

    if (!response.ok) {
      // 尝试从响应体提取后端错误消息
      let serverMessage = ''
      try {
        const body = await response.json()
        serverMessage = body?.message || ''
      } catch { /* 非JSON响应忽略 */ }
      throw new Error(serverMessage || `分片 ${chunkIndex + 1}/${totalChunks} 上传失败 (${response.status})`)
    }
  } catch (error) {
    if (error instanceof Error && error.name !== 'AbortError') {
      throw error
    }
  }
}

async function submitChunkUpload(file: File): Promise<void> {
  uploading.value = true
  uploadProgress.value = 0
  currentFile.value = file
  startTime.value = Date.now()
  totalUploaded.value = 0

  try {
    const fileHash = await calculateFileHash(file)
    const chunks = createChunks(file)
    const totalChunks = chunks.length

    chunkUpload.value = {
      file,
      fileHash,
      chunkSize: props.chunkSize,
      chunks,
      currentChunk: 0,
      uploadedChunks: new Set()
    }

    for (let i = 0; i < totalChunks; i++) {
      if (!uploading.value) break

      await uploadChunk(chunks[i], i, totalChunks, fileHash)
      chunkUpload.value.uploadedChunks.add(i)

      const progress = ((i + 1) / totalChunks) * 100
      uploadProgress.value = Math.round(progress)
      emit('progress', uploadProgress.value)
      emit('chunkProgress', file, i, totalChunks)

      const elapsed = (Date.now() - startTime.value) / 1000
      const uploadedBytes = (i + 1) * props.chunkSize
      currentSpeed.value = uploadedBytes / elapsed
      estimatedTime.value = Math.round(((totalChunks - i - 1) * props.chunkSize) / currentSpeed.value)
    }

    if (uploadProgress.value === 100) {
      ElMessage.success(`${file.name} 上传成功`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '上传失败'
    emit('error', message)
    ElMessage.error(message)
    throw error
  } finally {
    uploading.value = false
    currentFile.value = null
    abortController.value = null
  }
}

async function submitUpload() {
  if (uploadedFiles.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }

  uploading.value = true
  uploadProgress.value = 0

  try {
    for (const file of uploadedFiles.value) {
      if (props.enableChunk && file.size >= props.chunkThreshold) {
        await submitChunkUpload(file)
      } else {
        await submitSingleUpload(file)
      }
    }

    emit('success', uploadedFiles.value)
  } catch (error) {
    console.error('上传失败:', error)
  } finally {
    uploading.value = false
  }
}

async function submitSingleUpload(file: File): Promise<void> {
  currentFile.value = file
  startTime.value = Date.now()

  try {
    const formData = new FormData()
    formData.append('file', file)

    abortController.value = new AbortController()

    const xhr = new XMLHttpRequest()
    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) {
        const progress = Math.round((e.loaded / e.total) * 100)
        uploadProgress.value = progress
        emit('progress', progress)

        const elapsed = (Date.now() - startTime.value) / 1000
        currentSpeed.value = e.loaded / elapsed
        estimatedTime.value = Math.round((e.total - e.loaded) / currentSpeed.value)
      }
    })

    await new Promise<void>((resolve, reject) => {
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve()
        } else if (xhr.status === 401) {
          useUserStore().clearAuth()
          reject(new Error('登录状态已过期，请重新登录'))
        } else {
          // 尝试从响应体提取后端错误消息
          let serverMessage = ''
          try {
            const body = JSON.parse(xhr.responseText)
            serverMessage = body?.message || ''
          } catch { /* 非JSON响应忽略 */ }
          reject(new Error(serverMessage || `上传失败: ${xhr.statusText} (${xhr.status})`))
        }
      }
      xhr.onerror = () => reject(new Error('网络错误'))
      xhr.onabort = () => reject(new Error('上传已取消'))
      xhr.open('POST', props.uploadUrl)
      const userStore = useUserStore()
      if (userStore.accessToken) {
        xhr.setRequestHeader('Authorization', `Bearer ${userStore.accessToken}`)
      }
      xhr.send(formData)
    })

    ElMessage.success(`${file.name} 上传成功`)
  } catch (error) {
    if (error instanceof Error && error.message !== '上传已取消') {
      emit('error', error.message)
      ElMessage.error(error.message)
      throw error
    }
  } finally {
    currentFile.value = null
    abortController.value = null
  }
}

function cancelUpload() {
  if (abortController.value) {
    abortController.value.abort()
  }
  uploading.value = false
  uploadProgress.value = 0
  ElMessage.info('已取消上传')
}

function removeFile(index: number) {
  uploadedFiles.value.splice(index, 1)
  emit('change', uploadedFiles.value)
}

function clearFiles() {
  uploadedFiles.value = []
}

function downloadFile(file: File) {
  const url = URL.createObjectURL(file)
  const a = document.createElement('a')
  a.href = url
  a.download = file.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function previewFile(file: File) {
  if (file.type.startsWith('image/') || file.type === 'application/pdf') {
    const url = URL.createObjectURL(file)
    window.open(url, '_blank')
    URL.revokeObjectURL(url)
  } else {
    ElMessage.info('该文件类型暂不支持预览')
  }
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
}

defineExpose({
  clearFiles,
  removeFile,
  openFileSelector,
  submitUpload,
  cancelUpload
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

.uploading-icon {
  color: #409eff;
  animation: rotate 1s linear infinite;
  margin-bottom: 16px;
}

.uploading-text {
  margin-bottom: 16px;
  color: #606266;
  font-size: 14px;
}

.uploading-stats {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  font-size: 12px;
  color: #909399;
}

.filename {
  font-weight: 500;
  color: #606266;
}

.file-list {
  text-align: left;
}

.file-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #fff;
  border-radius: 6px;
  margin-bottom: 8px;
  border: 1px solid #ebeef5;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.file-icon {
  color: #409eff;
  flex-shrink: 0;
}

.file-details {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.file-details .file-name {
  display: block;
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-details .file-size {
  font-size: 12px;
  color: #909399;
}

.file-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.list-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  justify-content: flex-end;
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
