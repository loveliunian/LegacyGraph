/**
 * HTTP请求工具模块
 * 基于axios封装，统一处理请求拦截、响应拦截、错误处理、loading提示
 * 支持自动添加Authorization令牌、请求追踪ID生成、401 token 刷新
 */
import axios, { AxiosRequestConfig, AxiosResponse, AxiosError, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { showLoading, hideLoading, forceHideLoading } from '@/utils/loading'

/**
 * 扩展axios配置，添加自定义字段
 */
declare module 'axios' {
  interface AxiosRequestConfig {
    /** 是否显示 loading。F-H10：改为按需开启，默认 false */
    _showLoading?: boolean
    /** loading提示文字 */
    _loadingText?: string
    /** 请求追踪ID */
    _traceId?: string
    /** 跳过响应拦截器的统一错误处理（内部刷新令牌等场景使用） */
    _skipErrorHandler?: boolean
  }
}

/**
 * 创建axios实例
 * F-S3：baseURL 读取环境变量 VITE_API_BASE_URL，缺失时回退 /api，支持多环境部署
 */
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000
})

/**
 * F-M1：统一 401 未授权处理（清除鉴权、提示重新登录、刷新页面）。
 * 原实现在响应成功/错误两个分支各写一遍，此处收口为单一函数。
 */
let unauthorizedHandling = false
function handleUnauthorized(): void {
  if (unauthorizedHandling) return
  unauthorizedHandling = true
  const userStore = useUserStore()
  userStore.clearAuth()
  // token 失效直接跳登录页，不弹框打扰用户
  window.location.href = '/login'
}

/**
 * F-M2：token 刷新机制（防并发重复刷新）。
 * 401 时用 refreshToken 换取新 accessToken；刷新期间并发的其它请求挂起队列，
 * 刷新成功后统一重试，刷新失败则走 handleUnauthorized。
 */
let isRefreshing = false
let pendingRequests: Array<(token: string) => void> = []

function flushPendingRequests(token: string): void {
  pendingRequests.forEach(cb => cb(token))
  pendingRequests = []
}

function rejectPendingRequests(): void {
  pendingRequests = []
}

/**
 * 请求拦截器
 * 在请求发送前：
 * 1. 自动添加Authorization令牌
 * 2. 生成请求追踪ID X-Trace-Id
 * 3. 按需显示 loading（F-H10：默认不显示，需显式 _showLoading: true）
 */
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const userStore = useUserStore()
    // Ensure headers object exists
    if (!config.headers) {
      config.headers = {} as any
    }
    if (userStore.accessToken) {
      config.headers['Authorization'] = `Bearer ${userStore.accessToken}`
    }
    const traceId = generateTraceId()
    config.headers['X-Trace-Id'] = traceId
    config._traceId = traceId

    if (config._showLoading === true) {
      showLoading(config._loadingText)
    }

    return config
  },
  (error: AxiosError) => {
    forceHideLoading()
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器
 * 统一处理响应：隐藏 loading、按 code 判断成功、401 触发 token 刷新、统一错误提示。
 */
request.interceptors.response.use(
  (response: AxiosResponse) => {
    if (response.config?._showLoading === true) {
      hideLoading()
    }
    const res = response.data
    // 内部刷新调用：跳过业务码校验，直接返回完整响应体
    if (response.config?._skipErrorHandler) {
      return res
    }
    if (res.code !== 0 && res.code !== 200) {
      if (res.code === 401) {
        return handleTokenExpired(response.config)
      }
      ElMessage.error({
        message: res.message || '请求错误',
        duration: 5000
      })
      return Promise.reject(new Error(res.message || '请求错误'))
    }
    return res.data
  },
  (error: AxiosError) => {
    if (error.config?._showLoading === true) {
      hideLoading()
    }
    if (error.config?._skipErrorHandler) {
      return Promise.reject(error)
    }
    console.error('Response error:', error)
    const { response } = error
    if (response) {
      if (response.status === 401) {
        return handleTokenExpired(error.config)
      }
      ElMessage.error({
        message: response.statusText || `请求失败 ${response.status}`,
        duration: 5000
      })
    } else {
      ElMessage.error({
        message: '网络错误，请检查网络连接',
        duration: 5000
      })
    }
    return Promise.reject(error)
  }
)

/**
 * F-M2：401 处理 — 尝试用 refreshToken 刷新，成功则重试原请求，失败则踢登录。
 */
function handleTokenExpired(config?: InternalAxiosRequestConfig): Promise<any> {
  const userStore = useUserStore()
  const refreshTokenValue = userStore.refreshToken

  // 无 refreshToken 直接踢登录
  if (!refreshTokenValue) {
    handleUnauthorized()
    return Promise.reject(new Error('登录状态已过期'))
  }

  // 已在刷新中：把当前请求挂起，等刷新完成后用新 token 重试
  if (isRefreshing) {
    return new Promise((resolve, reject) => {
      pendingRequests.push((newToken: string) => {
        if (config?.headers) {
          config.headers['Authorization'] = `Bearer ${newToken}`
        }
        request(config!).then(resolve).catch(reject)
      })
    })
  }

  isRefreshing = true
  // 用原始 axios 实例发起刷新，_skipErrorHandler 避免响应拦截器递归
  return request.post('/lg/auth/refresh', refreshTokenValue, {
    headers: { 'Content-Type': 'text/plain' },
    _skipErrorHandler: true
  } as AxiosRequestConfig).then((res: any) => {
    const data = res?.data ?? res
    const newAccess: string = data.accessToken
    const newRefresh: string = data.refreshToken ?? refreshTokenValue
    if (!newAccess) {
      throw new Error('刷新令牌失败')
    }
    userStore.setTokens(newAccess, newRefresh)
    flushPendingRequests(newAccess)
    // 重试原请求
    if (config?.headers) {
      config.headers['Authorization'] = `Bearer ${newAccess}`
    }
    return config ? request(config) : Promise.reject(new Error('缺少原始请求配置'))
  }).catch((e) => {
    rejectPendingRequests()
    handleUnauthorized()
    return Promise.reject(e)
  }).finally(() => {
    isRefreshing = false
  })
}

/**
 * 生成请求追踪ID
 * 用于请求链路追踪
 * @returns 追踪ID字符串
 */
function generateTraceId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substring(2)
}

export default request

/**
 * GET请求
 * @param url 请求URL
 * @param params 查询参数
 * @param config 额外axios配置
 * @returns 响应数据
 */
export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.get(url, { params, ...config })
}

/**
 * POST请求
 * @param url 请求URL
 * @param data 请求体数据
 * @param config 额外axios配置
 * @returns 响应数据
 */
export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.post(url, data, config)
}

/**
 * PUT请求
 * @param url 请求URL
 * @param data 请求体数据
 * @param config 额外axios配置
 * @returns 响应数据
 */
export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.put(url, data, config)
}

/**
 * DELETE请求
 * @param url 请求URL
 * @param config 额外axios配置
 * @returns 响应数据
 */
export function del<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request.delete(url, config)
}

/**
 * 上传文件
 * 使用FormData包装文件，自动设置正确的Content-Type
 * @param url 请求URL
 * @param file 要上传的File对象
 * @param config 额外axios配置
 * @returns 响应数据
 */
export function upload<T = any>(url: string, file: File, config?: AxiosRequestConfig): Promise<T> {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(url, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    ...config
  })
}

/**
 * 下载文件
 * 用于下载文件，responseType设为blob
 * @param url 请求URL
 * @param params 查询参数
 * @returns Promise<axiosResponse> 包含blob数据
 */
export function downloadFile(url: string, params?: any) {
  return request({
    url,
    method: 'GET',
    params,
    responseType: 'blob'
  })
}
