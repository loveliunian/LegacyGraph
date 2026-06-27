import { hasPermission } from '@/utils/permission'
import type { Directive, DirectiveBinding } from 'vue'

/**
 * v-permission 权限指令
 * 使用方法:
 * <el-button v-permission="'user:add'">添加</el-button>
 * <el-button v-permission="['user:add', 'user:edit']">编辑</el-button>
 */
const permission: Directive = {
  mounted(el: HTMLElement, binding: DirectiveBinding) {
    const { value } = binding
    if (!value) return

    let permissions: string[] = []
    if (typeof value === 'string') {
      permissions = [value]
    } else if (Array.isArray(value)) {
      permissions = value
    }

    if (permissions.length > 0 && !hasAnyPermission(permissions)) {
      // 没有权限，隐藏元素
      el.style.display = 'none'
    }
  }
}

function hasAnyPermission(permissions: string[]): boolean {
  return permissions.every(p => hasPermission(p))
}

export default permission
