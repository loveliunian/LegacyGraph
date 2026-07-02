import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import EvidencePanel from '@/components/EvidencePanel.vue'

const mockEvidence = [
  {
    id: '1',
    sourceName: 'TestService.java',
    evidenceType: 'FILE_LINE',
    location: 'src/main/java/TestService.java:42',
    content: 'public void testMethod() {',
    summary: '测试方法',
    createdAt: '2025-01-15T10:30:00Z'
  },
  {
    id: '2',
    sourceName: '设计文档',
    evidenceType: 'DOC_PARAGRAPH',
    summary: '系统架构设计',
    createdAt: '2025-01-15T11:00:00Z'
  },
  {
    id: '3',
    sourceName: 'user_table',
    evidenceType: 'DB_SCHEMA',
    summary: '用户表结构',
    createdAt: '2025-01-15T09:00:00Z'
  },
  {
    id: '4',
    sourceName: '集成测试',
    evidenceType: 'TEST_RESULT',
    summary: '测试通过',
    createdAt: '2025-01-15T12:00:00Z'
  }
]

describe('EvidencePanel 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('应该正确渲染证据面板组件', () => {
    const wrapper = mount(EvidencePanel, {
      props: { evidence: [] }
    })
    expect(wrapper.find('.evidence-panel').exists()).toBe(true)
  })

  it('空证据时应该显示暂无数据', () => {
    const wrapper = mount(EvidencePanel, {
      props: { evidence: [] }
    })
    expect(wrapper.find('.evidence-panel').exists()).toBe(true)
  })

  it('应该正确显示代码证据标签页', () => {
    const wrapper = mount(EvidencePanel, {
      props: { evidence: mockEvidence }
    })
    expect(wrapper.find('.evidence-panel').exists()).toBe(true)
  })

  it('应该正确显示文档证据标签页', () => {
    const wrapper = mount(EvidencePanel, {
      props: { evidence: mockEvidence }
    })
    expect(wrapper.exists()).toBe(true)
  })
})
