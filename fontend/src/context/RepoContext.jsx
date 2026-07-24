import { createContext, useContext, useEffect, useMemo, useState } from 'react'

const RepoContext = createContext(null)

export function RepoProvider({ children }) {
  const [repositories, setRepositories] = useState([])
  const [selectedRepo, setSelectedRepo] = useState(null)
  const [loading, setLoading] = useState(true)

  const fetchRepositories = async () => {
    try {
      const res = await fetch('/api/repositories')
      if (res.ok) {
        const data = await res.json()
        setRepositories(data)
        
        const savedId = window.localStorage.getItem('vcsSelectedRepo')
        const active = data.find(r => r.id === savedId) || data[0]
        if (active) {
          await selectRepository(active)
        }
      }
    } catch (err) {
      console.error('Failed to load repositories', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchRepositories()
  }, [])

  const selectRepository = async (repoOrId) => {
    const repo = typeof repoOrId === 'string' ? { id: repoOrId, name: repoOrId } : repoOrId
    if (!repo) return

    try {
      await fetch(`/api/action?cmd=select-repo&id=${encodeURIComponent(repo.id)}`, {
        method: 'POST'
      })
      const statusRes = await fetch('/api/status')
      const dagRes = await fetch('/api/dag')
      
      const statusData = statusRes.ok ? await statusRes.json() : {}
      const dagData = dagRes.ok ? await dagRes.json() : []

      const fullRepo = {
        ...repo,
        currentBranch: statusData.activeHead || 'main',
        branches: statusData.branches || ['main'],
        commits: dagData.map(c => ({
          id: c.hash,
          commitId: c.hash,
          message: c.message,
          author: c.author,
          timestamp: c.timestamp,
          parents: c.parents,
          parentCommitIds: c.parents,
        })),
        files: (() => {
          const fileMap = new Map();
          (statusData.trackedFiles || []).forEach(f => fileMap.set(f, { path: f, type: 'file', status: 'tracked' }));
          (statusData.untracked || []).forEach(f => fileMap.set(f, { path: f, type: 'file', status: 'untracked' }));
          (statusData.modified || []).forEach(f => fileMap.set(f, { path: f, type: 'file', status: 'modified' }));
          return Array.from(fileMap.values());
        })(),
        conflicts: statusData.conflicts || [],
        visibility: repo.visibility || 'public',
        description: repo.description || 'VCS repository context',
        defaultBranch: repo.defaultBranch || 'main',
        statistics: {
          totalCommits: dagData.length,
          totalContributors: new Set(dagData.map(c => c.author)).size || 1,
        },
      }
      setSelectedRepo(fullRepo)
      window.localStorage.setItem('vcsSelectedRepo', repo.id)
    } catch (err) {
      console.error('Error fetching repo details', err)
      setSelectedRepo(repo)
    }
  }

  const switchBranch = async (repositoryId, branchName) => {
    try {
      const res = await fetch(`/api/action?cmd=checkout&branch=${branchName}`, {
        method: 'POST'
      })
      if (res.ok) {
        if (selectedRepo) {
          await selectRepository(selectedRepo)
        }
        return true
      }
    } catch (err) {
      console.error(err)
    }
    return false
  }

  const value = useMemo(
    () => ({
      repositories,
      selectedRepo,
      setSelectedRepo: selectRepository,
      selectRepository,
      switchBranch,
      loading,
      refresh: fetchRepositories
    }),
    [repositories, selectedRepo, loading]
  )

  return <RepoContext.Provider value={value}>{children}</RepoContext.Provider>
}

export function useRepo() {
  const context = useContext(RepoContext)

  if (!context) {
    throw new Error('useRepo must be used within a RepoProvider')
  }

  return context
}
