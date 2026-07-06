import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock request module
vi.mock('@/utils/request', () => ({
  get: vi.fn().mockResolvedValue({}),
  post: vi.fn().mockResolvedValue({}),
  del: vi.fn().mockResolvedValue({}),
  put: vi.fn().mockResolvedValue({}),
}))

import { get, post } from '@/utils/request'
import { graphifyApi } from '@/api/graphify.api'

describe('graphifyApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('importGraph', () => {
    it('should call POST with correct endpoint and data', async () => {
      const data = { graphJsonPath: '/tmp/graph.json', projectRoot: '/project' }
      await graphifyApi.importGraph('proj-1', 'ver-1', data)

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/scan-versions/ver-1/graphify/import',
        data
      )
    })
  })

  describe('run', () => {
    it('should call POST with correct endpoint and data', async () => {
      const data = { projectRoot: '/project', force: true }
      await graphifyApi.run('proj-1', 'ver-1', data)

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/scan-versions/ver-1/graphify/run',
        data
      )
    })

    it('should support optional postgresDsn', async () => {
      const data = { projectRoot: '/project', postgresDsn: 'postgresql://localhost/db' }
      await graphifyApi.run('proj-1', 'ver-1', data)

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/scan-versions/ver-1/graphify/run',
        data
      )
    })
  })

  describe('getJobs', () => {
    it('should call GET with correct endpoint', async () => {
      await graphifyApi.getJobs('proj-1')

      expect(get).toHaveBeenCalledWith('/lg/projects/proj-1/graphify/jobs')
    })
  })

  describe('retryJob', () => {
    it('should call POST with correct endpoint', async () => {
      await graphifyApi.retryJob('proj-1', 'job-123')

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/jobs/job-123/retry',
        {}
      )
    })
  })

  describe('rollbackJob', () => {
    it('should call POST with correct endpoint', async () => {
      await graphifyApi.rollbackJob('proj-1', 'job-123')

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/jobs/job-123/rollback',
        {}
      )
    })
  })

  describe('getDiff', () => {
    it('should call GET with correct endpoint and version IDs', async () => {
      await graphifyApi.getDiff('proj-1', 'old-ver', 'new-ver')

      expect(get).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/diff/old-ver/new-ver'
      )
    })
  })

  describe('askQuestion', () => {
    it('should call POST with correct endpoint and data', async () => {
      const data = {
        question: 'What is the entry point?',
        allowedSourceTypes: ['GRAPHIFY_AST'],
        maxEvidence: 5,
      }
      await graphifyApi.askQuestion('proj-1', data)

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/questions',
        data
      )
    })

    it('should work with minimal data', async () => {
      const data = { question: 'Simple question' }
      await graphifyApi.askQuestion('proj-1', data)

      expect(post).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/questions',
        data
      )
    })
  })

  describe('getQuality', () => {
    it('should call GET with correct endpoint', async () => {
      await graphifyApi.getQuality('proj-1')

      expect(get).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/quality',
        {}
      )
    })

    it('should pass versionId when provided', async () => {
      await graphifyApi.getQuality('proj-1', 'ver-1')

      expect(get).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/quality',
        { versionId: 'ver-1' }
      )
    })
  })

  describe('getCrossRepoImpact', () => {
    it('should call GET with correct endpoint', async () => {
      await graphifyApi.getCrossRepoImpact('proj-1')

      expect(get).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/cross-repo-impact',
        {}
      )
    })

    it('should pass versionId when provided', async () => {
      await graphifyApi.getCrossRepoImpact('proj-1', 'ver-1')

      expect(get).toHaveBeenCalledWith(
        '/lg/projects/proj-1/graphify/cross-repo-impact',
        { versionId: 'ver-1' }
      )
    })
  })
})
