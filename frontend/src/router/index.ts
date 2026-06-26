import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    redirect: '/projects'
  },
  {
    path: '/projects',
    name: 'Projects',
    component: () => import('@/views/project/ProjectList.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/projects/:projectId',
    name: 'ProjectDetail',
    component: () => import('@/views/project/ProjectDetail.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: to => `/projects/${to.params.projectId}/repos`
      },
      {
        path: 'repos',
        name: 'CodeRepos',
        component: () => import('@/views/source/CodeRepoList.vue')
      },
      {
        path: 'databases',
        name: 'Databases',
        component: () => import('@/views/source/DatabaseList.vue')
      },
      {
        path: 'documents',
        name: 'Documents',
        component: () => import('@/views/source/DocumentList.vue')
      },
      {
        path: 'scan-versions',
        name: 'ScanVersions',
        component: () => import('@/views/scan/ScanVersionList.vue')
      },
      {
        path: 'graph/code',
        name: 'CodeGraph',
        component: () => import('@/views/graph/CodeGraph.vue')
      },
      {
        path: 'reviews',
        name: 'Reviews',
        component: () => import('@/views/review/ReviewList.vue')
      },
      {
        path: 'review-history',
        name: 'ReviewHistory',
        component: () => import('@/views/review/ReviewHistory.vue')
      },
      {
        path: 'facts',
        name: 'Facts',
        component: () => import('@/views/fact/FactList.vue')
      },
      {
        path: 'evidence',
        name: 'Evidence',
        component: () => import('@/views/fact/EvidenceSearch.vue')
      },
      {
        path: 'test-cases',
        name: 'TestCases',
        component: () => import('@/views/test/TestCaseList.vue')
      },
      {
        path: 'validation',
        name: 'Validation',
        component: () => import('@/views/validation/ValidationDashboard.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (requiresAuth) {
    if (!userStore.token) {
      next('/login')
      return
    }
    if (!userStore.user && userStore.token) {
      await userStore.fetchUser()
    }
  }

  if (to.path === '/login' && userStore.token) {
    next('/projects')
    return
  }

  next()
})

export default router
