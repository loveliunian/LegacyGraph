/**
 * 权限判断工具
 */
import { useUserStore } from '@/stores/user'

/**
 * 判断是否有权限
 * @param permission 权限编码
 */
export function hasPermission(permission: string): boolean {
  const userStore = useUserStore()
  return userStore.hasPermission(permission)
}

/**
 * 判断是否有任意一个权限
 * @param permissions 权限编码列表
 */
export function hasAnyPermission(permissions: string[]): boolean {
  const userStore = useUserStore()
  for (const p of permissions) {
    if (userStore.hasPermission(p)) {
      return true
    }
  }
  return false
}

/**
 * 判断是否有所有权限
 * @param permissions 权限编码列表
 */
export function hasAllPermissions(permissions: string[]): boolean {
  const userStore = useUserStore()
  for (const p of permissions) {
    if (!userStore.hasPermission(p)) {
      return false
    }
  }
  return true
}
