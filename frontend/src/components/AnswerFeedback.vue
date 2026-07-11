<template>
  <div class="answer-feedback">
    <div class="feedback-buttons">
      <button
        class="fb-btn"
        :class="{ active: selected === 'POSITIVE' }"
        :disabled="submitting"
        @click="select('POSITIVE')"
        title="有用"
      >
        👍 有用
      </button>
      <button
        class="fb-btn"
        :class="{ active: selected === 'NEGATIVE' }"
        :disabled="submitting"
        @click="select('NEGATIVE')"
        title="无用"
      >
        👎 无用
      </button>
    </div>

    <div v-if="selected" class="feedback-extra">
      <textarea
        v-model="expectedEvidenceInput"
        placeholder="补充：期望命中但未出现的证据 ID（逗号分隔，可留空）"
        rows="2"
      />
      <button class="submit-btn" :disabled="submitting" @click="submit">
        {{ submitting ? '提交中...' : '提交反馈' }}
      </button>
    </div>

    <div v-if="message" class="feedback-msg" :class="messageType">{{ message }}</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { post } from '@/utils/request'

interface Props {
  question?: string
  answerId?: string
  claimText?: string
}

const props = defineProps<Props>()

const selected = ref<string>('')
const expectedEvidenceInput = ref<string>('')
const submitting = ref(false)
const message = ref('')
const messageType = ref<'success' | 'error'>('success')

const select = (type: string) => {
  selected.value = selected.value === type ? '' : type
}

const submit = async () => {
  if (!selected.value) return
  submitting.value = true
  message.value = ''
  try {
    const expectedEvidenceIds = expectedEvidenceInput.value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)

    await post('/lg/qa/feedback', {
      question: props.question || '',
      answerId: props.answerId || '',
      claimText: props.claimText || '',
      feedbackType: selected.value,
      expectedEvidenceIds,
    })

    message.value = '反馈已提交，感谢您的帮助！'
    messageType.value = 'success'
    selected.value = ''
    expectedEvidenceInput.value = ''
  } catch (e: any) {
    message.value = '提交失败：' + (e?.message || '未知错误')
    messageType.value = 'error'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.answer-feedback { font-size: 13px; }
.feedback-buttons { display: flex; gap: 8px; }
.fb-btn { padding: 4px 12px; border: 1px solid #d9d9d9; background: #fff; border-radius: 4px; cursor: pointer; }
.fb-btn:hover:not(:disabled) { border-color: #1890ff; color: #1890ff; }
.fb-btn.active { border-color: #1890ff; color: #1890ff; background: #e6f7ff; }
.fb-btn:disabled { cursor: not-allowed; opacity: 0.6; }
.feedback-extra { margin-top: 8px; display: flex; flex-direction: column; gap: 8px; }
.feedback-extra textarea { width: 100%; padding: 6px 8px; border: 1px solid #d9d9d9; border-radius: 4px; resize: vertical; font-family: inherit; }
.submit-btn { align-self: flex-start; padding: 4px 16px; background: #1890ff; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.submit-btn:disabled { background: #ccc; cursor: not-allowed; }
.feedback-msg { margin-top: 8px; padding: 4px 8px; border-radius: 3px; }
.feedback-msg.success { color: #52c41a; }
.feedback-msg.error { color: #f5222d; }
</style>
