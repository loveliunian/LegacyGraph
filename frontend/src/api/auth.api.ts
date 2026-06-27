import { post, get } from '@/utils/request'

/**
 * 登录请求参数
 */
export interface LoginRequest {
  /** 用户名 */
  username: string
  /** 密码 */
  password: string
}

/**
 * 登录响应
 */
export interface LoginResponse {
  /** 访问令牌 */
  accessToken: string
  /** 刷新令牌 */
  refreshToken: string
  /** 用户信息 */
  user: User
}

/**
 * 用户信息
 */
export interface User {
  /** 用户ID */
  id: string
  /** 用户名 */
  username: string
  /** 用户昵称 */
  nickname: string
  /** 邮箱 */
  email: string
  /** 头像URL */
  avatar: string
  /** 权限列表 */
  permissions: string[]
}

/**
 * 认证API接口
 * 提供用户登录、登出、获取用户信息等认证相关操作
 */
export const authApi = {
  /**
   * 用户登录
   * @param data 登录请求参数，包含用户名和密码
   * @returns 登录响应，包含令牌和用户信息
   */
  login: (data: LoginRequest) => {
    return post<LoginResponse>('/lg/auth/login', data)
  },

  /**
   * 用户登出
   * @returns 成功响应
   */
  logout: () => {
    return post('/lg/auth/logout', {})
  },

  /**
   * 获取当前登录用户信息
   * @returns 当前用户信息
   */
  getCurrentUser: () => {
    return get<User>('/lg/auth/me')
  }
}
