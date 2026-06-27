import { post, get } from '@/utils/request'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: User
}

export interface User {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  permissions: string[]
}

export const authApi = {
  login: (data: LoginRequest) => {
    return post<LoginResponse>('/lg/auth/login', data)
  },

  logout: () => {
    return post('/lg/auth/logout', {})
  },

  getCurrentUser: () => {
    return get<User>('/lg/auth/current')
  }
}
