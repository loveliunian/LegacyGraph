import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/projects'
  },
  {
    path: '/projects',
    name: 'Projects',
    component: () => import('@/views/project/ProjectList.vue')
  },
  {
    path: '/projects/:id',
    name: 'ProjectDetail',
    component: () => import('@/views/project/ProjectDetail.vue')
  },
  {
    path: '/scan/:versionId',
    name: 'ScanTask',
    component: () => import('@/views/scan/ScanTask.vue')
  },
  {
    path: '/graph/code',
    name: 'CodeGraph',
    component: () => import('@/views/graph/CodeGraph.vue')
  },
  {
    path: '/graph/feature',
    name: 'FeatureGraph',
    component: () => import('@/views/graph/FeatureGraph.vue')
  },
  {
    path: '/graph/business',
    name: 'BusinessGraph',
    component: () => import('@/views/graph/BusinessGraph.vue')
  },
  {
    path: '/review',
    name: 'Review',
    component: () => import('@/views/review/ReviewList.vue')
  },
  {
    path: '/test',
    name: 'Test',
    component: () => import('@/views/test/TestCaseList.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
