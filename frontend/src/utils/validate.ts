import type { FormItemRule } from 'element-plus'

export const validators = {
  required: (message = '此项为必填项'): FormItemRule => ({
    required: true,
    message,
    trigger: ['blur', 'change']
  }),

  username: (): FormItemRule[] => [
    validators.required('请输入用户名'),
    { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '用户名只能包含字母、数字和下划线', trigger: 'blur' }
  ],

  password: (): FormItemRule[] => [
    validators.required('请输入密码'),
    { min: 6, max: 32, message: '密码长度在 6 到 32 个字符', trigger: 'blur' },
    { pattern: /^(?=.*[a-zA-Z])(?=.*\d)/, message: '密码需包含字母和数字', trigger: 'blur' }
  ],

  email: (): FormItemRule[] => [
    validators.required('请输入邮箱'),
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
  ],

  phone: (): FormItemRule[] => [
    validators.required('请输入手机号'),
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号格式', trigger: 'blur' }
  ],

  url: (required = false): FormItemRule[] => [
    ...(required ? [validators.required('请输入URL')] : []),
    { type: 'url', message: '请输入正确的URL格式', trigger: 'blur' }
  ],

  number: (min?: number, max?: number, required = true): FormItemRule[] => [
    ...(required ? [validators.required('请输入数字')] : []),
    { type: 'number', message: '请输入有效的数字', trigger: 'blur' },
    ...(min !== undefined ? [{ min, message: `最小值为 ${min}`, trigger: 'blur' }] : []),
    ...(max !== undefined ? [{ max, message: `最大值为 ${max}`, trigger: 'blur' }] : [])
  ],

  range: (min: number, max: number, required = true): FormItemRule[] => [
    { validator: (rule, value, callback) => {
        if (required && (value === undefined || value === null || value === '')) {
          callback(new Error('请输入数值'))
        } else if (value !== undefined && value !== null && value !== '') {
          const num = Number(value)
          if (isNaN(num)) {
            callback(new Error('请输入有效的数字'))
          } else if (num < min || num > max) {
            callback(new Error(`数值范围在 ${min} 到 ${max} 之间`))
          } else {
            callback()
          }
        } else {
          callback()
        }
      }, trigger: 'blur' }
  ],

  length: (min: number, max: number, required = true): FormItemRule[] => [
    ...(required ? [validators.required('请输入内容')] : []),
    { min, max, message: `长度在 ${min} 到 ${max} 个字符`, trigger: 'blur' }
  ],

  pattern: (regex: RegExp, message: string, required = true): FormItemRule[] => [
    ...(required ? [validators.required('请输入内容')] : []),
    { pattern: regex, message, trigger: 'blur' }
  ],

  arrayMinLength: (min: number, message?: string): FormItemRule => ({
    type: 'array',
    min,
    message: message || `至少选择 ${min} 项`,
    trigger: 'change'
  }),

  notEmptyArray: (message = '至少选择一项'): FormItemRule => ({
    type: 'array',
    required: true,
    min: 1,
    message,
    trigger: 'change'
  }),

  confirmPassword: (passwordField: string): FormItemRule => ({
    validator: (rule, value, callback, source) => {
      if (!value) {
        callback(new Error('请再次输入密码'))
      } else if (value !== source[passwordField]) {
        callback(new Error('两次输入密码不一致'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }),

  projectName: (): FormItemRule[] => [
    validators.required('请输入项目名称'),
    validators.length(2, 100)[1]
  ],

  projectCode: (): FormItemRule[] => [
    validators.required('请输入项目编码'),
    validators.pattern(/^[A-Za-z][A-Za-z0-9_-]{1,49}$/, '项目编码需以字母开头，只能包含字母、数字、下划线和中划线，长度2-50')[1]
  ],

  gitUrl: (): FormItemRule[] => [
    validators.required('请输入Git仓库地址'),
    { pattern: /^(http|https|git):\/\//, message: '请输入有效的Git地址', trigger: 'blur' }
  ]
}

export function validateEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

export function validatePhone(phone: string): boolean {
  return /^1[3-9]\d{9}$/.test(phone)
}

export function validateUrl(url: string): boolean {
  try {
    new URL(url)
    return true
  } catch {
    return false
  }
}

export function validatePasswordStrength(password: string): { level: number; text: string; color: string } {
  let level = 0
  if (password.length >= 6) level++
  if (password.length >= 10) level++
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) level++
  if (/\d/.test(password)) level++
  if (/[!@#$%^&*(),.?":{}|<>]/.test(password)) level++

  if (level <= 2) return { level, text: '弱', color: '#ff4d4f' }
  if (level <= 3) return { level, text: '中', color: '#faad14' }
  return { level, text: '强', color: '#52c41a' }
}
