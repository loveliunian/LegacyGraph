import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/types'
import { authApi } from '@/api'

/**
 * 解析 JWT payload（不校验签名，仅前端用于读取 exp 判断是否过期）。
 * JWT 三段式 header.payload.signature，payload 为 base64url 编码的 JSON。
 */
function decodeJwtExp(token: string): number | null {
  if (!token) return null
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    // base64url -> base64
    let payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    // 补齐 padding
    const pad = payload.length % 4
    if (pad) payload += '='.repeat(4 - pad)
    const decoded = decodeURIComponent(
      atob(payload)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    const claims = JSON.parse(decoded)
    const exp = claims?.exp
    return typeof exp === 'number' ? exp : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  const accessToken = ref<string>('')
  const refreshToken = ref<string>('')
  const userInfo = ref<User | null>(null)
  const permissions = ref<string[]>([])
  const roles = ref<string[]>([])

  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)

  const setTokens = (access: string, refresh: string) => {
    accessToken.value = access
    refreshToken.value = refresh
  }

  const setUserInfo = (user: User) => {
    userInfo.value = user
    permissions.value = user.permissions || []
    roles.value = user.roles || []
  }

  const clearAuth = () => {
    accessToken.value = ''
    refreshToken.value = ''
    userInfo.value = null
    permissions.value = []
    roles.value = []
  }

  const login = async (username: string, password: string) => {
    const result = await authApi.login({ username, password })
    setTokens(result.accessToken, result.refreshToken)
    setUserInfo(result.user)
    return result
  }

  const logout = async () => {
    try {
      await authApi.logout()
    } finally {
      clearAuth()
    }
  }

  const fetchCurrentUser = async () => {
    // 已缓存用户信息，不重复请求 /lg/auth/me
    if (userInfo.value) {
      return userInfo.value
    }
    if (accessToken.value) {
      const user = await authApi.getCurrentUser()
      setUserInfo(user)
      return user
    }
    return null
  }

  /**
   * F-H6：判断 access token 是否已过期（提前 30s 视为过期，给刷新留缓冲）。
   * 无法解析 exp 时返回 false（交由后端 401 + 响应拦截器刷新兜底）。
   * 注意：本后端 access/refresh token 同源同过期（7200s），过期时刷新令牌也已失效，
   * 故路由守卫检测到过期直接回登录页，不做无效的主动刷新。
   */
  const isTokenExpired = (): boolean => {
    const exp = decodeJwtExp(accessToken.value)
    if (exp === null) return false
    return Date.now() >= exp * 1000 - 30 * 1000
  }

  const hasPermission = (permission: string): boolean => {
    return permissions.value.includes(permission) || permissions.value.includes('*')
  }

  /** F-H6：是否拥有指定角色之一（支持 '*' 通配） */
  const hasRole = (role: string): boolean => {
    return roles.value.includes(role) || roles.value.includes('*')
  }

  /** F-H6：是否拥有 requiredRoles 中的任意一个角色 */
  const hasAnyRole = (requiredRoles: string[]): boolean => {
    if (!requiredRoles || requiredRoles.length === 0) return true
    return requiredRoles.some(r => hasRole(r))
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    permissions,
    roles,
    isLoggedIn,
    setTokens,
    setUserInfo,
    clearAuth,
    login,
    logout,
    fetchCurrentUser,
    isTokenExpired,
    hasPermission,
    hasRole,
    hasAnyRole
  }
}, {
  persist: {
    key: 'legacy-graph-user',
    storage: localStorage,
    // userInfo 和 permissions 缓存到浏览器，避免每次刷新都请求 /lg/auth/me
    // 退出登录时 clearAuth() 将它们置空，localStorage 同步清除
    paths: ['accessToken', 'refreshToken', 'userInfo', 'permissions', 'roles']
  }
})
