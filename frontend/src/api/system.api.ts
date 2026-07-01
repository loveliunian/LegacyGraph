import { get, post, put, del } from '@/utils/request'
import type { PageResult, User } from '@/types'

/**
 * 字典类型实体（对应后端 SysDict）
 */
export interface DictType {
  /** 字典类型ID */
  id: string
  /** 字典编码（唯一标识，如 repo_type） */
  dictCode: string
  /** 字典名称（如 仓库类型） */
  dictName: string
  /** 描述 */
  description?: string
  /** 排序 */
  sortOrder: number
  /** 状态：ACTIVE/DISABLED */
  status: string
}

/**
 * 字典项实体（对应后端 SysDictItem）
 */
export interface DictItem {
  /** 字典项ID */
  id: string
  /** 所属字典类型ID */
  dictId: string
  /** 项值（如 BACKEND） */
  itemValue: string
  /** 项标签（如 后端） */
  itemLabel: string
  /** 描述 */
  description?: string
  /** 排序 */
  sortOrder: number
  /** 是否默认项 */
  isDefault: boolean
  /** 状态 */
  status: string
}

// 保持向后兼容的别名
export type Dictionary = DictType

/**
 * 系统配置实体
 */
export interface SystemConfig {
  /** 配置ID */
  id: string
  /** 配置键 */
  configKey: string
  /** 配置值 */
  configValue: string
  /** 配置描述 */
  configDesc: string
}

/**
 * 系统管理API
 * 提供用户管理、字典管理（类型+项）、系统配置管理功能
 */
export const systemApi = {
  // ==================== 用户管理 ====================

  listUsers: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    status?: string
  }) => {
    return get<PageResult<User>>('/lg/system/users/list', params)
  },

  createUser: (data: Partial<User>) => {
    return post<User>('/lg/system/users', data)
  },

  updateUser: (id: string, data: Partial<User>) => {
    return put(`/lg/system/users/${id}`, data)
  },

  deleteUser: (id: string) => {
    return del(`/lg/system/users/${id}`)
  },

  // ==================== 字典类型管理 ====================

  /** 分页查询字典类型列表 */
  listDictTypes: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    status?: string
  }) => {
    return get<PageResult<DictType>>('/lg/system/dicts/list', params)
  },

  /** 获取所有激活的字典类型 */
  listAllDictTypes: () => {
    return get<DictType[]>('/lg/system/dicts/all')
  },

  /** 创建字典类型 */
  createDictType: (data: Partial<DictType>) => {
    return post<DictType>('/lg/system/dicts', data)
  },

  /** 更新字典类型 */
  updateDictType: (id: string, data: Partial<DictType>) => {
    return put(`/lg/system/dicts/${id}`, data)
  },

  /** 删除字典类型 */
  deleteDictType: (id: string) => {
    return del(`/lg/system/dicts/${id}`)
  },

  // ==================== 字典项管理 ====================

  /** 获取字典类型下的所有项 */
  getDictItems: (dictId: string) => {
    return get<DictItem[]>(`/lg/system/dicts/${dictId}/items`)
  },

  /** 根据字典编码获取所有项 */
  getDictItemsByCode: (dictCode: string) => {
    return get<DictItem[]>(`/lg/system/dicts/code/${encodeURIComponent(dictCode)}/items`)
  },

  /** 根据字典编码获取值→标签映射 */
  getDictItemMap: (dictCode: string) => {
    return get<Record<string, string>>(
      `/lg/system/dicts/code/${encodeURIComponent(dictCode)}/map`
    )
  },

  /** 创建字典项 */
  createDictItem: (data: Partial<DictItem>) => {
    return post<DictItem>('/lg/system/dicts/items', data)
  },

  /** 更新字典项 */
  updateDictItem: (id: string, data: Partial<DictItem>) => {
    return put(`/lg/system/dicts/items/${id}`, data)
  },

  /** 删除字典项 */
  deleteDictItem: (id: string) => {
    return del(`/lg/system/dicts/items/${id}`)
  },

  // ==================== 向后兼容（deprecated，请使用新方法） ====================

  /** @deprecated 请使用 listDictTypes */
  listDictionaries: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    status?: string
  }) => systemApi.listDictTypes(params),

  /** @deprecated 请使用 createDictType */
  createDictionary: (data: Partial<DictType>) => systemApi.createDictType(data),

  /** @deprecated 请使用 updateDictType */
  updateDictionary: (id: string, data: Partial<DictType>) => systemApi.updateDictType(id, data),

  /** @deprecated 请使用 deleteDictType */
  deleteDictionary: (id: string) => systemApi.deleteDictType(id),

  // ==================== 系统配置 ====================

  listConfigs: (params: {
    pageNum: number
    pageSize: number
    configKey?: string
  }) => {
    return get<PageResult<SystemConfig>>('/lg/system/configs/list', params)
  },

  updateConfig: (id: string, data: Partial<SystemConfig>) => {
    return put(`/lg/system/configs/${id}`, data)
  }
}
