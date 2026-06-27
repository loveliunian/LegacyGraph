import axios, { AxiosRequestConfig, AxiosResponse, AxiosError } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { showLoading, hideLoading, forceHideLoading } from '@/utils/loading'

declare module 'axios' {
  interface AxiosRequestConfig {
    _showLoading?: boolean
    _loadingText?: string
    _traceId?: string
  }
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(
  (config: AxiosRequestConfig) => {
    const userStore = useUserStore()
    if (userStore.accessToken) {
      config.headers = config.headers || {}
      config.headers['Authorization'] = `Bearer ${userStore.accessToken}`
    }
    const traceId = generateTraceId()
    config.headers = config.headers || {}
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

function generateTraceId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substring(2)
}

export default request

export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.get(url, { params, ...config })
}

export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.post(url, data, config)
}

export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request.put(url, data, config)
}

export function del<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request.delete(url, config)
}

export function upload<T = any>(url: string, file: File, config?: AxiosRequestConfig): Promise<T> {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(url, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    ...config
  })
}

export function downloadFile(url: string, params?: any) {
  return request({
    url,
    method: 'GET',
    params,
    responseType: 'blob'
  })
}
