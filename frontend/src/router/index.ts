import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import * as LoadingService from '@/utils/loading'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginPage.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/',
    component: () => import('@/components/AppLayout.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Index.vue'),
        meta: { requiresAuth: true, title: 'menu.dashboard' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/project/ProjectList.vue'),
        meta: { requiresAuth: true, title: 'menu.projects' }
      },
      {
        path: 'projects/:projectId',
        name: 'ProjectDetail',
        component: () => import('@/views/project/ProjectDetail.vue'),
        meta: { requiresAuth: true, title: 'menu.projectDetail' },
        redirect: { name: 'ProjectOverview' },
        children: [
          {
            path: 'overview',
            name: 'ProjectOverview',
            component: () => import('@/views/project/ProjectOverview.vue'),
            meta: { title: 'menu.projectOverview' }
          },
          {
            path: 'repos',
            name: 'CodeRepos',
            component: () => import('@/views/source/CodeRepoList.vue'),
            meta: { title: 'menu.codeRepos' }
          },
          {
            path: 'databases',
            name: 'Databases',
            component: () => import('@/views/source/DatabaseList.vue'),
            meta: { title: 'menu.databases' }
          },
          {
            path: 'documents',
            name: 'Documents',
            component: () => import('@/views/source/DocumentList.vue'),
            meta: { title: 'menu.documents' }
          },
          {
            path: 'scan-versions',
            name: 'ScanVersions',
            component: () => import('@/views/scan/ScanVersionList.vue'),
            meta: { title: 'menu.scanVersions' }
          },
          {
            path: 'scan-versions/create',
            name: 'CreateScan',
            component: () => import('@/views/scan/CreateScan.vue'),
            meta: { title: '新建扫描' }
          },
          {
            path: 'graph/code',
            name: 'CodeGraph',
            component: () => import('@/views/graph/CodeGraph.vue'),
            meta: { title: 'menu.codeGraph' }
          },
          {
            path: 'graph/unified',
            name: 'UnifiedGraph',
            component: () => import('@/views/graph/UnifiedGraph.vue'),
            meta: { title: 'menu.unifiedGraph' }
          },
          {
            path: 'graph/business',
            name: 'BusinessGraph',
            component: () => import('@/views/graph/BusinessGraph.vue'),
            meta: { title: 'menu.businessGraph' }
          },
          {
            path: 'graph/feature',
            name: 'FeatureGraph',
            component: () => import('@/views/graph/FeatureGraph.vue'),
            meta: { title: 'menu.featureGraph' }
          },
          {
            path: 'graph/lineage',
            name: 'DataLineageGraph',
            component: () => import('@/views/graph/DataLineageGraph.vue'),
            meta: { title: 'menu.dataLineage' }
          },
          {
            path: 'graph/runtime',
            name: 'RuntimeGraph',
            component: () => import('@/views/graph/RuntimeGraph.vue'),
            meta: { title: 'menu.runtimeGraph' }
          },
          {
            path: 'reviews',
            name: 'Reviews',
            component: () => import('@/views/review/ReviewList.vue'),
            meta: { title: 'menu.reviews' }
          },
          {
            path: 'review-history',
            name: 'ReviewHistory',
            component: () => import('@/views/review/ReviewHistory.vue'),
            meta: { title: 'menu.reviewHistory' }
          },
          {
            path: 'facts',
            name: 'Facts',
            component: () => import('@/views/fact/FactList.vue'),
            meta: { title: 'menu.facts' }
          },
          {
            path: 'evidence',
            name: 'Evidence',
            component: () => import('@/views/fact/EvidenceSearch.vue'),
            meta: { title: 'menu.evidence' }
          },
          {
            path: 'test-cases',
            name: 'TestCases',
            component: () => import('@/views/test/TestCaseList.vue'),
            meta: { title: 'menu.testCases' }
          },
          {
            path: 'test-cases/new',
            name: 'TestCaseEditorNew',
            component: () => import('@/views/test/TestCaseEditor.vue'),
            meta: { title: 'menu.testCaseEditor' }
          },
          {
            path: 'test-cases/:id/edit',
            name: 'TestCaseEditorEdit',
            component: () => import('@/views/test/TestCaseEditor.vue'),
            meta: { title: 'menu.testCaseEditor' }
          },
          {
            path: 'test-runs',
            name: 'TestRunList',
            component: () => import('@/views/test/TestRunList.vue'),
            meta: { title: 'menu.testRuns' }
          },
          {
            path: 'test-runs/:id',
            name: 'TestRunDetail',
            component: () => import('@/views/test/TestRunDetail.vue'),
            meta: { title: 'menu.testRunDetail' }
          },
          {
            path: 'validation',
            name: 'Validation',
            component: () => import('@/views/report/ValidationReport.vue'),
            meta: { title: 'menu.validationReport' }
          },
          {
            path: 'migration/risks',
            name: 'MigrationRiskList',
            component: () => import('@/views/migration/RiskList.vue'),
            meta: { title: 'menu.migrationRisks' }
          },
          {
            path: 'migration/risks/:riskId',
            name: 'MigrationRiskDetail',
            component: () => import('@/views/migration/RiskDetail.vue'),
            meta: { title: 'menu.riskDetail' }
          },
          {
            path: 'audit/logs',
            name: 'AuditLogList',
            component: () => import('@/views/audit/LogList.vue'),
            meta: { title: 'menu.auditLogs' }
          },
          {
            path: 'audit/logs/:id',
            name: 'AuditLogDetail',
            component: () => import('@/views/audit/LogDetail.vue'),
            meta: { title: 'menu.logDetail' }
          },
          {
            path: 'workbench',
            name: 'EvidenceWorkbench',
            component: () => import('@/views/workbench/EvidenceWorkbench.vue'),
            meta: { title: '证据工作台' }
          }
        ]
      },
      {
        path: 'system/users',
        name: 'SystemUserList',
        component: () => import('@/views/system/UserList.vue'),
        meta: { title: 'menu.systemUsers' }
      },
      {
        path: 'system/dictionaries',
        name: 'SystemDictionaryList',
        component: () => import('@/views/system/DictionaryList.vue'),
        meta: { title: 'menu.systemDictionaries' }
      },
      {
        path: 'system/settings',
        name: 'SystemSettings',
        component: () => import('@/views/system/Settings.vue'),
        meta: { title: 'menu.systemSettings' }
      },
      {
        path: 'system/llm',
        name: 'LlmProviderSettings',
        component: () => import('@/views/system/LlmProviderSettings.vue'),
        meta: { title: 'LLM 提供商' }
      },
      {
        path: 'system/prompts',
        name: 'PromptTemplateList',
        component: () => import('@/views/system/PromptList.vue'),
        meta: { title: '提示词管理' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { requiresAuth: false, title: 'common.error' }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { requiresAuth: false, title: 'common.forbidden' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (requiresAuth) {
    if (!userStore.accessToken) {
      next('/login')
      return
    }
    if (!userStore.userInfo && userStore.accessToken) {
      try {
        await userStore.fetchCurrentUser()
      } catch (error) {
        next('/login')
        return
      }
    }
  }

  if (to.path === '/login' && userStore.accessToken) {
    next('/dashboard')
    return
  }

  next()
})

router.beforeResolve(async (to, from, next) => {
  LoadingService.showLoading()
  next()
})

router.afterEach(() => {
  LoadingService.hideLoading()
})

export default router
