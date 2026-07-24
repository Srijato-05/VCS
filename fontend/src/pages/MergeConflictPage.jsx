import { useMemo, useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import ConflictResolver from '../components/ConflictResolver'
import { useRepo } from '../context/RepoContext'

function MergeConflictPage() {
  const { repoId } = useParams()
  const { selectedRepo, selectRepository } = useRepo()
  const [conflicts, setConflicts] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")

  const repo = useMemo(() => {
    return selectedRepo?.id === repoId ? selectedRepo : null
  }, [repoId, selectedRepo])

  const loadConflicts = async () => {
    if (!selectedRepo?.conflicts || selectedRepo.conflicts.length === 0) {
      setConflicts([])
      return
    }
    setLoading(true)
    setError("")
    try {
      const list = []
      for (const file of selectedRepo.conflicts) {
        const res = await fetch(`/api/conflict-details?file=${encodeURIComponent(file)}`)
        if (res.ok) {
          const data = await res.json()
          list.push({
            fileName: file,
            currentContent: [data.left || ''],
            incomingContent: [data.right || ''],
            ancestorContent: [data.ancestor || '']
          })
        } else {
          console.error(`Failed to load conflict details for ${file}`)
        }
      }
      setConflicts(list)
    } catch (err) {
      setError("Failed to load conflict details")
      console.error(err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConflicts()
  }, [selectedRepo?.conflicts])

  const handleResolveAction = async (fileName, resolution) => {
    try {
      const res = await fetch(`/api/action?cmd=resolve&file=${encodeURIComponent(fileName)}&resolution=${resolution}`, {
        method: "POST"
      })
      if (res.ok) {
        if (selectRepository && selectedRepo) {
          await selectRepository(selectedRepo)
        }
      } else {
        const err = await res.json()
        alert(err.error || "Failed to resolve conflict")
      }
    } catch (err) {
      alert(err.message || "Error resolving conflict")
    }
  }

  if (!repo) {
    return <div className="text-slate-400">Repository not found.</div>
  }

  return (
    <div className="space-y-6">
      <div className="rounded-3xl border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
        <p className="text-sm font-semibold uppercase tracking-[0.3em] text-cyan-500">Merge conflicts</p>
        <h2 className="mt-2 text-2xl font-semibold text-slate-900 dark:text-white">{repo.name}</h2>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">Review conflicting files before merging your branch.</p>
      </div>

      {error && (
        <div className="rounded-xl border border-rose-300 bg-rose-50 p-4 text-rose-700">
          ❌ {error}
        </div>
      )}

      {loading ? (
        <div className="text-slate-500">Loading conflict details...</div>
      ) : conflicts.length === 0 ? (
        <div className="rounded-3xl border border-slate-200/70 bg-white/80 p-6 text-sm text-slate-500 shadow-sm dark:border-slate-800 dark:bg-slate-900/70 dark:text-slate-400">
          No conflicts to resolve.
        </div>
      ) : (
        <div className="space-y-4">
          {conflicts.map((conflict) => (
            <ConflictResolver
              key={conflict.fileName}
              conflict={conflict}
              onAcceptCurrent={() => handleResolveAction(conflict.fileName, "ours")}
              onAcceptIncoming={() => handleResolveAction(conflict.fileName, "theirs")}
              onResolve={() => handleResolveAction(conflict.fileName, "both")}
            />
          ))}
        </div>
      )}
    </div>
  )
}

export default MergeConflictPage
