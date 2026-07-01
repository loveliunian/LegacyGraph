<template>
  <div class="graph-qa-page">
    <el-card class="qa-card">
      <template #header>
        <div class="card-header">
          <el-icon><ChatDotRound /></el-icon>
          <span style="margin-left: 8px; font-weight: 600;">图谱问答</span>
          <el-tag size="small" type="info" style="margin-left: 12px;">基于知识图谱的智能问答</el-tag>
        </div>
      </template>

      <div class="chat-container" ref="chatContainer">
        <div v-if="messages.length === 0" class="welcome-hint">
          <el-icon :size="48" color="#c0c4cc"><ChatDotRound /></el-icon>
          <p>向我提问关于项目代码、架构、数据关系的任何问题</p>
          <div class="example-questions">
            <el-tag
              v-for="q in exampleQuestions"
              :key="q"
              class="example-tag"
              @click="askQuestion(q)"
            >
              {{ q }}
            </el-tag>
          </div>
        </div>

        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          :class="['message-item', msg.role === 'user' ? 'message-user' : 'message-assistant']"
        >
          <div class="message-avatar">
            <el-icon v-if="msg.role === 'user'" :size="20"><User /></el-icon>
            <el-icon v-else :size="20"><Cpu /></el-icon>
          </div>
          <div class="message-body">
            <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
            <div v-if="msg.evidences && msg.evidences.length > 0" class="message-evidences">
              <p class="evidence-title">📎 引用证据（{{ msg.evidences.length }} 条）：</p>
              <div v-for="(ev, ei) in msg.evidences" :key="ei" class="evidence-item">
                <el-tag size="small" :type="ev.sourceKind === 'GRAPH_NODE' ? 'success' : 'warning'">
                  {{ ev.sourceKind === 'GRAPH_NODE' ? '图谱节点' : '文档片段' }}
                </el-tag>
                <span class="evidence-title-text">{{ ev.title }}</span>
                <p v-if="ev.excerpt" class="evidence-excerpt">{{ ev.excerpt }}</p>
              </div>
            </div>
            <div v-if="msg.confidence != null" class="message-confidence">
              置信度：{{ (msg.confidence * 100).toFixed(0) }}%
            </div>
          </div>
        </div>

        <div v-if="thinking" class="message-item message-assistant">
          <div class="message-avatar">
            <el-icon :size="20"><Cpu /></el-icon>
          </div>
          <div class="message-body">
            <div class="thinking-dots">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>
      </div>

      <div class="input-area">
        <el-input
          v-model="inputText"
          placeholder="输入你的问题，例如：这个项目用了哪些数据库表？"
          :rows="3"
          type="textarea"
          @keydown.enter.exact.prevent="handleSend"
        />
        <div class="input-actions">
          <span class="char-count">{{ inputText.length }}/500</span>
          <el-button
            type="primary"
            :disabled="!inputText.trim() || thinking"
            :loading="thinking"
            @click="handleSend"
          >
            <el-icon><Promotion /></el-icon>
            发送
          </el-button>
          <el-button @click="clearChat" :disabled="messages.length === 0">清空</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ChatDotRound, User, Cpu, Promotion } from '@element-plus/icons-vue'
import { qaApi } from '@/api'

const route = useRoute()
const projectId = route.params.projectId as string

interface Message {
  role: 'user' | 'assistant'
  content: string
  confidence?: number
  evidences?: Array<{
    sourceKind: string
    ref: string
    title: string
    excerpt: string
    sourcePath?: string
    score?: number
  }>
}

const messages = ref<Message[]>([])
const inputText = ref('')
const thinking = ref(false)
const chatContainer = ref<HTMLElement>()

const exampleQuestions = [
  '这个项目用了哪些数据库表？',
  '有哪些 Controller 接口？',
  '列出所有的 Service 类',
  '项目的主要技术栈是什么？',
]

const renderMarkdown = (text: string) => {
  if (!text) return ''
  // 简单 markdown 渲染：代码块、粗体、换行
  return text
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

const askQuestion = (q: string) => {
  inputText.value = q
  handleSend()
}

const handleSend = async () => {
  const question = inputText.value.trim()
  if (!question || thinking.value) return

  messages.value.push({ role: 'user', content: question })
  inputText.value = ''
  thinking.value = true
  await scrollToBottom()

  try {
    const res = await qaApi.ask({ question, projectId }) as any
    const data = res?.data || res
    messages.value.push({
      role: 'assistant',
      content: data.answer || '未获取到回答',
      confidence: data.confidence,
      evidences: data.evidences || [],
    })
  } catch {
    messages.value.push({
      role: 'assistant',
      content: '抱歉，问答服务暂时不可用，请稍后重试。',
    })
  } finally {
    thinking.value = false
    await scrollToBottom()
  }
}

const clearChat = () => {
  messages.value = []
}

onMounted(() => {
  // 页面加载完成
})
</script>

<style scoped>
.graph-qa-page {
  height: calc(100vh - 180px);
  display: flex;
  flex-direction: column;
}

.qa-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.qa-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: center;
}

.chat-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  min-height: 0;
}

.welcome-hint {
  text-align: center;
  padding: 60px 20px;
  color: #909399;
}

.welcome-hint p {
  margin: 16px 0;
  font-size: 15px;
}

.example-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 16px;
}

.example-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.example-tag:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: #f0f2f5;
  color: #606266;
}

.message-user .message-avatar {
  background: #e6f4ff;
  color: #1890ff;
}

.message-body {
  max-width: 75%;
}

.message-user .message-body {
  text-align: right;
}

.message-content {
  background: #f5f7fa;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.message-user .message-content {
  background: #e6f4ff;
}

.message-content :deep(pre) {
  background: #282c34;
  color: #abb2bf;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 13px;
  margin: 8px 0;
}

.message-content :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.message-content :deep(pre code) {
  background: none;
  padding: 0;
}

.message-evidences {
  margin-top: 8px;
  padding: 10px;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.evidence-title {
  font-size: 12px;
  color: #909399;
  margin: 0 0 8px 0;
}

.evidence-item {
  margin-bottom: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.evidence-title-text {
  font-size: 13px;
  color: #303133;
}

.evidence-excerpt {
  width: 100%;
  margin: 4px 0 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}

.message-confidence {
  margin-top: 6px;
  font-size: 12px;
  color: #67c23a;
}

.thinking-dots {
  display: flex;
  gap: 6px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.thinking-dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  animation: dot-bounce 1.4s infinite ease-in-out both;
}

.thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots span:nth-child(2) { animation-delay: -0.16s; }
.thinking-dots span:nth-child(3) { animation-delay: 0s; }

@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

.input-area {
  border-top: 1px solid #ebeef5;
  padding: 16px 20px;
}

.input-area :deep(.el-textarea__inner) {
  resize: none;
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.char-count {
  font-size: 12px;
  color: #c0c4cc;
  margin-right: auto;
}
</style>
