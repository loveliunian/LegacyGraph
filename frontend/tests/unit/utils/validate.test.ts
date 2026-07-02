import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  validators,
  validateEmail,
  validatePhone,
  validateUrl,
  validatePasswordStrength
} from '@/utils/validate'

describe('validate 工具', () => {
  describe('validators', () => {
    it('required 应该返回必填校验规则', () => {
      const rule = validators.required('此项必填')
      expect(rule.required).toBe(true)
      expect(rule.message).toBe('此项必填')
    })

    it('username 应该返回用户名校验规则数组', () => {
      const rules = validators.username()
      expect(rules.length).toBeGreaterThanOrEqual(2)
      expect(rules[0].required).toBe(true)
    })

    it('password 应该返回密码校验规则数组', () => {
      const rules = validators.password()
      expect(rules.length).toBeGreaterThanOrEqual(2)
      expect(rules[0].required).toBe(true)
    })

    it('email 应该返回邮箱校验规则数组', () => {
      const rules = validators.email()
      expect(rules.length).toBeGreaterThanOrEqual(1)
    })

    it('phone 应该返回手机号校验规则数组', () => {
      const rules = validators.phone()
      expect(rules.length).toBeGreaterThanOrEqual(1)
    })

    it('number 应该返回数字校验规则', () => {
      const rules = validators.number(0, 100, true)
      expect(rules.length).toBeGreaterThan(0)
    })

    it('length 应该返回长度校验规则', () => {
      const rules = validators.length(2, 50, true)
      expect(rules.length).toBeGreaterThan(0)
    })

    it('notEmptyArray 应该返回非空数组校验', () => {
      const rule = validators.notEmptyArray('至少选一项')
      expect(rule.type).toBe('array')
      expect(rule.required).toBe(true)
    })

    it('projectName 应该返回项目名称校验规则', () => {
      const rules = validators.projectName()
      expect(rules.length).toBeGreaterThan(0)
    })

    it('projectCode 应该返回项目编码校验规则', () => {
      const rules = validators.projectCode()
      expect(rules.length).toBeGreaterThan(0)
    })

    it('gitUrl 应该返回 Git 地址校验规则', () => {
      const rules = validators.gitUrl()
      expect(rules.length).toBeGreaterThan(0)
    })
  })

  describe('validateEmail', () => {
    it('有效邮箱应该返回 true', () => {
      expect(validateEmail('test@example.com')).toBe(true)
    })

    it('无效邮箱应该返回 false', () => {
      expect(validateEmail('invalid-email')).toBe(false)
    })

    it('空字符串应该返回 false', () => {
      expect(validateEmail('')).toBe(false)
    })
  })

  describe('validatePhone', () => {
    it('有效手机号应该返回 true', () => {
      expect(validatePhone('13800138000')).toBe(true)
    })

    it('无效手机号应该返回 false', () => {
      expect(validatePhone('12345')).toBe(false)
    })

    it('非1开头的号码应该返回 false', () => {
      expect(validatePhone('23800138000')).toBe(false)
    })
  })

  describe('validateUrl', () => {
    it('有效 URL 应该返回 true', () => {
      expect(validateUrl('https://example.com')).toBe(true)
    })

    it('无效 URL 应该返回 false', () => {
      expect(validateUrl('not-a-url')).toBe(false)
    })
  })

  describe('validatePasswordStrength', () => {
    it('弱密码应该返回弱等级', () => {
      const result = validatePasswordStrength('123')
      expect(result.level).toBeLessThanOrEqual(2)
    })

    it('强密码应该返回强等级', () => {
      const result = validatePasswordStrength('Abc123!@#defGHI')
      expect(result.level).toBeGreaterThanOrEqual(4)
    })

    it('应该返回 level、text 和 color', () => {
      const result = validatePasswordStrength('abc123')
      expect(result).toHaveProperty('level')
      expect(result).toHaveProperty('text')
      expect(result).toHaveProperty('color')
    })
  })
})
