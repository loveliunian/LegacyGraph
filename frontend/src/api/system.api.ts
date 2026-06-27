import { get, post, put, del } from '@/utils/request'
import type { PageResult } from '@/types'

/**
 * 系统用户实体
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
  /** 用户状态：ACTIVE/DISABLED */
  status: string
  /** 权限列表 */
  permissions: string[]
  /** 创建时间 */
  createdAt: string
}

/**
 * 字典项实体
 */
export interface Dictionary {
  /** 字典ID */
  id: string
  /** 字典类型 */
  dictType: string
  /** 字典编码 */
  dictCode: string
  /** 字典名称 */
  dictName: string
  /** 字典值 */
  dictValue: string
  /** 排序权重 */
  sort: number
  /** 状态：ACTIVE/DISABLED */
  status: string
}

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
 * 提供用户管理、字典管理、系统配置管理功能
 */
export const systemApi = {
  /**
   * 分页查询用户列表
   * @param params 查询参数，包含分页、关键词、状态筛选
   * @returns 分页后的用户列表
   */
  listUsers: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    status?: string
  }) => {
    return get<PageResult<User>>('/lg/system/users/list', params)
  },

  /**
   * 创建用户
   * @param data 用户数据
   * @returns 创建的用户信息
   */
  createUser: (data: Partial<User>) => {
    return post<User>('/lg/system/users', data)
  },

  /**
   * 更新用户
   * @param id 用户ID
   * @param data 更新数据
   * @returns 更新结果
   */
  updateUser: (id: string, data: Partial<User>) => {
    return put(`/lg/system/users/${id}`, data)
  },

  /**
   * 删除用户
   * @param id 用户ID
   * @returns 删除结果
   */
  deleteUser: (id: string) => {
    return del(`/lg/system/users/${id}`)
  },

  /**
   * 分页查询字典类型列表
   * @param params 查询参数，包含分页和类型筛选
   * @returns 分页后的字典列表
   */
  listDictionaries: (params: {
    pageNum: number
    pageSize: number
    dictType?: string
  }) => {
    return get<PageResult<Dictionary>>('/lg/system/dicts/list', params)
  },

  /**
   * 创建字典类型
   * @param data 字典数据
   * @returns 创建的字典信息
   */
  createDictionary: (data: Partial<Dictionary>) => {
    return post<Dictionary>('/lg/system/dicts', data)
  },

  /**
   * 更新字典类型
   * @param id 字典ID
   * @param data 更新数据
   * @returns 更新结果
   */
  updateDictionary: (id: string, data: Partial<Dictionary>) => {
    return put(`/lg/system/dicts/${id}`, data)
  },

  /**
   * 删除字典类型
   * @param id 字典ID
   * @returns 删除结果
   */
  deleteDictionary: (id: string) => {
    return del(`/lg/system/dicts/${id}`)
  },

  /**
   * 分页查询系统配置列表
   * @param params 查询参数，包含分页和键筛选
   * @returns 分页后的配置列表
   */
  listConfigs: (params: {
    pageNum: number
    pageSize: number
    configKey?: string
  }) => {
    return get<PageResult<SystemConfig>>('/lg/system/configs/list', params)
  },

  /**
   * 更新系统配置
   * @param id 配置ID
   * @param data 更新数据
   * @returns 更新结果
   */
  updateConfig: (id: string, data: Partial<SystemConfig>) => {
    return put(`/lg/system/configs/${id}`, data)
  }
}
