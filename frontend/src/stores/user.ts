import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/types'
import { authApi } from '@/api'

export const useUserStore = defineStore('user', () => {
  const accessToken = ref<string>('')
  const refreshToken = ref<string>('')
  const userInfo = ref<User | null>(null)
  const permissions = ref<string[]>([])

  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)

  const setTokens = (access: string, refresh: string) => {
    accessToken.value = access
    refreshToken.value = refresh
  }

  const setUserInfo = (user: User) => {
    userInfo.value = user
    permissions.value = user.permissions || []
  }

  const clearAuth = () => {
    accessToken.value = ''
    refreshToken.value = ''
    userInfo.value = null
    permissions.value = []
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

  const hasPermission = (permission: string): boolean => {
    return permissions.value.includes(permission) || permissions.value.includes('*')
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    permissions,
    isLoggedIn,
    setTokens,
    setUserInfo,
    clearAuth,
    login,
    logout,
    fetchCurrentUser,
    hasPermission
  }
}, {
  persist: {
    key: 'legacy-graph-user',
    storage: localStorage,
    // userInfo 和 permissions 缓存到浏览器，避免每次刷新都请求 /lg/auth/me
    // 退出登录时 clearAuth() 将它们置空，localStorage 同步清除
    paths: ['accessToken', 'refreshToken', 'userInfo', 'permissions']
  }
})
