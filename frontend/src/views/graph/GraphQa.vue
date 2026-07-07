<template>
  <div class="graph-qa-page">
    <el-container class="qa-container">
      <!-- 左侧对话列表 -->
      <el-aside
        width="260px"
        class="conversation-sidebar">
        <div class="sidebar-header">
          <el-button
            type="primary"
            :icon="Plus"
            @click="createNewConversation">
            新对话
          </el-button>
        </div>
        <div class="conversation-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            :class="['conversation-item', { active: currentConversationId === conv.id }]"
            @click="switchConversation(conv.id)"
          >
            <div class="conv-title">{{ conv.title || '新对话' }}</div>
            <div class="conv-meta">{{ conv.messageCount ?? 0 }} 条消息</div>
            <el-button
              class="delete-btn"
              :icon="Delete"
              size="small"
              text
              @click.stop="deleteConversation(conv.id)"
            />
          </div>
        </div>
      </el-aside>

      <!-- 右侧聊天区域 -->
      <el-main class="chat-main">
        <el-card class="qa-card">
          <template #header>
            <div class="card-header">
              <el-icon><ChatDotRound /></el-icon>
              <span style="margin-left: 8px; font-weight: 600;">图谱问答</span>
              <el-tag
                size="small"
                type="info"
                style="margin-left: 12px;">
                基于知识图谱的智能问答
              </el-tag>
            </div>
          </template>

          <div
            ref="chatContainer"
            class="chat-container">
            <div
              v-if="messages.length === 0"
              class="welcome-hint">
              <el-icon
                :size="48"
                color="#c0c4cc">
                <ChatDotRound />
              </el-icon>
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
                <el-icon
                  v-if="msg.role === 'user'"
                  :size="20">
                  <User />
                </el-icon>
                <el-icon
                  v-else
                  :size="20">
                  <Cpu />
                </el-icon>
              </div>
              <div class="message-body">
                <div
                  class="message-content"
                  v-html="renderMarkdown(msg.content)" />
                <div
                  v-if="msg.evidences && msg.evidences.length > 0"
                  class="message-evidences">
                  <p class="evidence-title">📎 引用证据（{{ msg.evidences.length }} 条）：</p>
                  <div
                    v-for="(ev, ei) in msg.evidences"
                    :key="ei"
                    class="evidence-item">
                    <el-tag
                      size="small"
                      :type="ev.sourceKind === 'GRAPH_NODE' ? 'success' : 'warning'">
                      {{ ev.sourceKind === 'GRAPH_NODE' ? '图谱节点' : '文档片段' }}
                    </el-tag>
                    <a
                      v-if="ev.jumpUrl"
                      :href="ev.jumpUrl"
                      target="_blank"
                      class="evidence-title-text evidence-link"
                    >
                      {{ ev.title }}
                      <el-icon><Link /></el-icon>
                    </a>
                    <span
                      v-else
                      class="evidence-title-text">{{ ev.title }}</span>
                    <p
                      v-if="ev.excerpt"
                      class="evidence-excerpt">
                      {{ ev.excerpt }}
                    </p>
                  </div>
                </div>
                <div
                  v-if="msg.confidence != null"
                  class="message-confidence">
                  置信度：{{ (msg.confidence * 100).toFixed(0) }}%
                </div>
                <div
                  v-if="msg.impact"
                  class="message-impact">
                  <p class="impact-title">🔍 变更影响分析：</p>
                  <div class="impact-summary">
                    <el-tag v-if="msg.impact.changeKind" size="small" type="warning">
                      {{ msg.impact.changeKind }}
                    </el-tag>
                    <el-tag v-if="msg.impact.tableName" size="small" type="info">
                      {{ msg.impact.tableName }}
                    </el-tag>
                    <el-tag v-if="msg.impact.severity" size="small" :type="msg.impact.severity === 'HIGH' ? 'danger' : msg.impact.severity === 'MEDIUM' ? 'warning' : 'success'">
                      {{ msg.impact.severity }}
                    </el-tag>
                  </div>
                  <div v-if="msg.impact.impactedNodes" class="impact-nodes">
                    <span v-for="(node, ni) in msg.impact.impactedNodes" :key="ni" class="impact-node">
                      {{ node }}
                    </span>
                  </div>
                  <el-button
                    v-if="msg.impact.suggestCreateTask"
                    size="small"
                    type="primary"
                    plain
                    class="create-task-btn"
                    @click="handleCreateChangeTask(msg.impact)">
                    📝 创建变更任务
                  </el-button>
                </div>
                <div
                  v-if="msg.role === 'assistant' && !thinking"
                  class="message-actions">
                  <el-button
                    size="small"
                    text
                    @click="submitFeedback(idx, true)">
                    👍 有帮助
                  </el-button>
                  <el-button
                    size="small"
                    text
                    @click="submitFeedback(idx, false)">
                    👎 需改进
                  </el-button>
                </div>
              </div>
            </div>

            <div
              v-if="thinking"
              class="message-item message-assistant">
              <div class="message-avatar">
                <el-icon :size="20"><Cpu /></el-icon>
              </div>
              <div class="message-body">
                <div class="message-content streaming-content">
                  {{ streamingContent }}
                  <span class="cursor">▊</span>
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
              <el-button
                :disabled="messages.length === 0"
                @click="clearChat">
                清空
              </el-button>
            </div>
          </div>
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ChatDotRound, User, Cpu, Promotion, Plus, Delete, Link } from '@element-plus/icons-vue'
import { qaApi, type QaConversation, type EvidenceItem } from '@/api/qa.api'
import { changeTaskApi } from '@/api/change-task.api'
import { mapQaHistoryMessages } from './qaMessageMapper'
import { ElMessage } from 'element-plus'

const route = useRoute()
const projectId = route.params.projectId as string

interface Message {
  role: 'user' | 'assistant'
  content: string
  confidence?: number
  evidences?: EvidenceItem[]
  impact?: any
  messageId?: string
  conversationId?: string
}

const conversations = ref<QaConversation[]>([])
const currentConversationId = ref<string | null>(null)
const messages = ref<Message[]>([])
const inputText = ref('')
const thinking = ref(false)
const streamingContent = ref('')
const streamingEvidences = ref<EvidenceItem[]>([])
const streamingImpact = ref<any>(null)
const chatContainer = ref<HTMLElement>()

const exampleQuestions = [
  '这个项目用了哪些数据库表？',
  '有哪些 Controller 接口？',
  '列出所有的 Service 类',
  '项目的主要技术栈是什么？',
]

import DOMPurify from 'dompurify'

const renderMarkdown = (text: string) => {
  if (!text) return ''
  const html = text
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
  return DOMPurify.sanitize(html)
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
  streamingContent.value = ''
  streamingEvidences.value = []
  await scrollToBottom()

  try {
    qaApi.askStreamFetch(
      {
        question,
        projectId,
        conversationId: currentConversationId.value || undefined,
      },
      {
        onToken: (token) => {
          streamingContent.value += token
          scrollToBottom()
        },
        onEvidence: (evidences) => {
          streamingEvidences.value = evidences
        },
        onImpact: (data) => {
          streamingImpact.value = data
        },
        onComplete: (data) => {
          if (data.conversationId) {
            currentConversationId.value = data.conversationId
          }

          // 优先使用后端提取的 answer（纯文本），避免显示原始 JSON
          const finalContent = data.answer || streamingContent.value
          const evidences = data.evidences || streamingEvidences.value || []
          const confidence = data.confidence
          const messageId = data.messageId

          messages.value.push({
            role: 'assistant',
            content: finalContent,
            confidence,
            evidences,
            impact: data.changeImpact || streamingImpact.value,
            messageId,
            conversationId: data.conversationId || currentConversationId.value || undefined,
          })

          streamingContent.value = ''
          streamingEvidences.value = []
          streamingImpact.value = null
          thinking.value = false
          scrollToBottom()
          refreshConversations()
        },
        onError: (err) => {
          console.error('Stream error:', err)
          messages.value.push({
            role: 'assistant',
            content: '抱歉，问答服务暂时不可用，请稍后重试。',
          })
          streamingContent.value = ''
          streamingEvidences.value = []
          thinking.value = false
          scrollToBottom()
        },
      }
    )
  } catch (err) {
    console.error('Failed to start stream:', err)
    messages.value.push({
      role: 'assistant',
      content: '抱歉，问答服务暂时不可用，请稍后重试。',
    })
    thinking.value = false
    streamingEvidences.value = []
    scrollToBottom()
  }
}

const clearChat = () => {
  messages.value = []
  currentConversationId.value = null
}

const createNewConversation = async () => {
  currentConversationId.value = null
  messages.value = []
}

const switchConversation = async (convId: string) => {
  try {
    if (currentConversationId.value === convId) return

    currentConversationId.value = convId
    await loadConversationMessages(convId)
  } catch (error) {
    console.error('switchConversation error:', error)
    ElMessage.error('操作失败')
  }
  }

const loadConversationMessages = async (convId: string) => {
  try {
    const msgs = await qaApi.getMessages(convId)
    messages.value = mapQaHistoryMessages(msgs || [])
    scrollToBottom()
  } catch (err) {
    console.error('Failed to load messages:', err)
    ElMessage.error('加载对话失败')
  }
}

const deleteConversation = async (convId: string) => {
  try {
    await qaApi.deleteConversation(convId)
    ElMessage.success('对话已删除')
    if (currentConversationId.value === convId) {
      currentConversationId.value = null
      messages.value = []
    }
    refreshConversations()
  } catch (err) {
    console.error('Failed to delete conversation:', err)
    ElMessage.error('删除失败')
  }
}

const refreshConversations = async () => {
  try {
    const data = await qaApi.listConversations(projectId)
    conversations.value = data || []
  } catch (err) {
    console.error('Failed to load conversations:', err)
  }
}

const submitFeedback = async (msgIndex: number, helpful: boolean) => {
  const msg = messages.value[msgIndex]
  if (!msg || msg.role !== 'assistant' || !msg.messageId) return
  const conversationId = msg.conversationId || currentConversationId.value
  if (!conversationId) return

  try {
    await qaApi.submitFeedback({
      messageId: msg.messageId,
      conversationId,
      projectId,
      helpful,
      usedEvidenceIds: (msg.evidences || []).map((evidence) => evidence.ref).filter(Boolean),
      question: messages.value[msgIndex - 1]?.content,
      answer: msg.content,
    })
    ElMessage.success('感谢反馈！')
  } catch (err) {
    console.error('Failed to submit feedback:', err)
    ElMessage.error('反馈提交失败')
  }
}

const handleCreateChangeTask = async (impact: any) => {
  try {
    const taskType = impact.changeKind || 'ADD_COLUMN'
    const tableName = impact.tableName || ''
    const columnName = impact.columnName || ''
    
    const title = `添加字段: ${tableName}.${columnName}`
    const inputIssue = `为 ${tableName} 表添加 ${columnName} 字段`

    await changeTaskApi.create({
      projectId,
      versionId: 'latest', // 使用最新版本
      taskType,
      title,
      inputIssue,
    })
    
    ElMessage.success('变更任务创建成功！')
    
    // 更新消息中的 impact，移除按钮避免重复创建
    const lastAssistantMsg = [...messages.value].reverse().find(m => m.role === 'assistant' && m.impact)
    if (lastAssistantMsg && lastAssistantMsg.impact) {
      lastAssistantMsg.impact.suggestCreateTask = false
    }
  } catch (err) {
    console.error('Failed to create change task:', err)
    ElMessage.error('创建变更任务失败')
  }
}

onMounted(() => {
  refreshConversations()
})
</script>

<style scoped>
.graph-qa-page {
  height: calc(100vh - 180px);
}

.qa-container {
  height: 100%;
}

.conversation-sidebar {
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  background: #fafafa;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #ebeef5;
}

.sidebar-header .el-button {
  width: 100%;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conversation-item {
  padding: 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}

.conversation-item:hover {
  background: #f0f2f5;
}

.conversation-item.active {
  background: #e6f4ff;
  border-left: 3px solid #1890ff;
}

.conv-title {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.delete-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 0.2s;
}

.conversation-item:hover .delete-btn {
  opacity: 1;
}

.chat-main {
  padding: 0;
  display: flex;
  flex-direction: column;
}

.qa-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
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

.message-impact {
  margin-top: 12px;
  padding: 12px;
  background: #f0f9ff;
  border-radius: 8px;
  border-left: 3px solid #1890ff;
}

.impact-title {
  font-size: 14px;
  font-weight: 600;
  color: #1890ff;
  margin-bottom: 8px;
}

.impact-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.impact-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.impact-node {
  display: inline-block;
  padding: 4px 8px;
  background: #e6f7ff;
  border: 1px solid #91d5ff;
  border-radius: 4px;
  font-size: 12px;
  color: #1890ff;
}

.create-task-btn {
  margin-top: 8px;
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

.streaming-content {
  background: #f5f7fa;
  white-space: pre-wrap;
}

.cursor {
  animation: blink 1s infinite;
  color: #1890ff;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.evidence-link {
  color: #1890ff;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.evidence-link:hover {
  text-decoration: underline;
}

.message-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
