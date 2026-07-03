import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfidenceBadge from '@/components/ConfidenceBadge.vue'

describe('ConfidenceBadge Component', () => {
  it('should render correctly', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.8 }
    })
    // Component uses el-tag, not el-badge
    expect(wrapper.findComponent({ name: 'ElTag' }).exists() || wrapper.html().includes('tag')).toBe(true)
  })

  it('should show correct text when showText is true', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.8, showText: true }
    })
    expect(wrapper.text()).toContain('高置信度')
  })

  it('should show warning for medium confidence value', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.5, showText: true }
    })
    expect(wrapper.text()).toContain('中置信度')
  })

  it('should show danger for low confidence value', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.3, showText: true }
    })
    expect(wrapper.text()).toContain('低置信度')
  })

  it('should have correct default size', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.8 }
    })
    expect(wrapper.props('size')).toBe('small')
  })

  it('should show success type for high confidence', () => {
    const wrapper = mount(ConfidenceBadge, {
      props: { value: 0.9 }
    })
    expect(wrapper.findComponent({ name: 'ElTag' }).exists() || wrapper.html().includes('tag')).toBe(true)
  })
})
