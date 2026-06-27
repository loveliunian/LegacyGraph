/**
 * HTTP请求工具模块
 * 基于axios封装，统一处理请求拦截、响应拦截、错误处理、loading提示
 * 支持自动添加Authorization令牌、请求追踪ID生成
 */
import axios, { AxiosRequestConfig, AxiosResponse, AxiosError } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { showLoading, hideLoading, forceHideLoading } from '@/utils/loading'

/**
 * 扩展axios配置，添加自定义字段
 */
declare module 'axios' {
  interface AxiosRequestConfig {
    /** 是否显示loading，默认true */
    _showLoading?: boolean
    /** loading提示文字 */
    _loadingText?: string
    /** 请求追踪ID */
    _traceId?: string
  }
}

/**
 * 创建axios实例
 * 配置基础URL和超时时间
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

/**
 * 请求拦截器
 * 在请求发送前：
 * 1. 自动添加Authorization令牌
 * 2. 生成请求追踪ID X-Trace-Id
 * 3. 自动显示loading（除非关闭）
 */
request.interceptors.request.use(
  (config: AxiosRequestConfig) => {
    const userStore = useUserStore()
    // Ensure headers object exists
    if (!config.headers) {
      config.headers = {}
    }
    if (userStore.accessToken) {
      config.headers['Authorization'] = `Bearer ${userStore.accessToken}`
      console.debug('[Request] Added Authorization token')
    } else {
      console.debug('[Request] No access token available')
    }
    const traceId = generateTraceId()
    config.headers['X-Trace-Id'] = traceId
    config._traceId = traceId

    if (config._showLoading !== false) {
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
 * 统一处理响应：
 * 1. 隐藏loading
 * 2. 根据code判断请求是否成功
 * 3. 401自动跳转到登录
 * 4. 统一错误消息提示
 */
request.interceptors.response.use(
  (response: AxiosResponse) => {
    hideLoading()
    const res = response.data
    if (res.code !== 0 && res.code !== 200) {
      ElMessage.error({
        message: res.message || '请求错误',
        duration: 5000
      })
      if (res.code === 401) {
        const userStore = useUserStore()
        ElMessageBox.confirm(
          '登录状态已过期，请重新登录',
          '系统提示',
          {
            confirmButtonText: '重新登录',
            cancelButtonText: '取消',
            type: 'warning'
          }
        ).then(() => {
          userStore.clearAuth()
          window.location.reload()
        })
      }
      return Promise.reject(new Error(res.message || '请求错误'))
    }
    return res.data
  },
  (error: AxiosError) => {
    hideLoading()
    console.error('Response error:', error)
    const { response } = error
    if (response) {
      if (response.status === 401) {
        const userStore = useUserStore()
        ElMessageBox.confirm(
          '登录状态已过期，请重新登录',
          '系统提示',
          {
            confirmButtonText: '重新登录',
            cancelButtonText: '取消',
            type: 'warning'
          }
        ).then(() => {
          userStore.clearAuth()
          window.location.reload()
        })
      } else {
        ElMessage.error({
          message: response.statusText || `请求失败 ${response.status}`,
          duration: 5000
        })
      }
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
