import { useMemo } from 'react'
import { BookOpen, GitBranch, GitCommitVertical, History, Layers3, TerminalSquare } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import FileTree from '../components/FileTree'
import { useRepo } from '../context/RepoContext'


function CommitHistoryPage() {
  const navigate = useNavigate()
  const { repoId } = useParams()
  const { selectedRepo } = useRepo()

  const repo = useMemo(() => {
    if (selectedRepo?.id === repoId) {
      return selectedRepo;
    }
    return selectedRepo;
  }, [repoId, selectedRepo]);

  const commits = useMemo(() => {
    return repo?.commits || [];
  }, [repo?.commits])

  if (!repo) {
    return <div className="text-slate-400">Repository not found.</div>
  }

  const branchLabel = repo.currentBranch || 'main'
  const recentCommits = commits.slice(0, 6)
  const readmeFile = repo.files?.find((file) => file.path.toLowerCase().includes('readme'))
  const languageSummary = ['JavaScript', 'CSS', 'Markdown']

  return (
    <div className="space-y-6">
      <div className="rounded-4xl border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70 sm:p-8">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <div className="flex flex-wrap items-center gap-2 text-xs font-semibold uppercase tracking-[0.3em] text-cyan-500">
              <span>Repository</span>
              <span className="text-slate-400">/</span>
              <span>{repo.name}</span>
            </div>
            <h2 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900 dark:text-white">{repo.name}</h2>
            <p className="mt-3 max-w-2xl text-sm leading-7 text-slate-500 dark:text-slate-400">{repo.description}</p>
          </div>

          <div className="inline-flex items-center gap-2 rounded-full border border-cyan-500/20 bg-cyan-500/10 px-3 py-2 text-sm font-medium text-cyan-600 dark:text-cyan-300">
            <GitBranch size={16} />
            Current branch: {branchLabel}
          </div>
        </div>

        <div className="mt-6 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-950/70">
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-500 dark:text-slate-400">HEAD</p>
            <p className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">{commits[0]?.id?.slice(0, 8) ?? 'n/a'}</p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-950/70">
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-500 dark:text-slate-400">Latest Snapshot</p>
            <p className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">{repo.lastCommitMessage || 'n/a'}</p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-950/70">
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-500 dark:text-slate-400">Commit Count</p>
            <p className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">{repo.statistics?.totalCommits ?? 0}</p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-950/70">
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-500 dark:text-slate-400">Workspace Files</p>
            <p className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">{repo.files?.length ?? 0}</p>
          </div>
        </div>
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
        <div className="space-y-6">
          <div className="rounded-[28px] border border-slate-200/70 bg-white/80 p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900/70 sm:p-6">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">Current Snapshot</p>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Browse the active tree with the same snapshot context as the workspace.</p>
              </div>
              <div className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-300">
                {branchLabel}
              </div>
            </div>

            <div className="mb-4 flex flex-wrap items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50/70 px-3 py-2 text-sm text-slate-500 dark:border-slate-800 dark:bg-slate-950/70 dark:text-slate-400">
              <span className="font-medium text-slate-700 dark:text-slate-200">Breadcrumbs</span>
              <span>/</span>
              <span>{repo.name}</span>
              <span>/</span>
              <span>src</span>
              <span>/</span>
              <span>components</span>
            </div>

            <FileTree files={repo.files ?? []} showSearch title="File Explorer" />
          </div>
        </div>

        <div className="space-y-6">
          <div className="rounded-[28px] border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
              <Layers3 size={16} className="text-cyan-500" />
              Branches
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              {(Array.isArray(repo.branches) ? repo.branches : Object.keys(repo.branches ?? {})).map((branch) => (
                <span key={branch} className={`rounded-full px-3 py-1.5 text-sm ${branch === branchLabel ? 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-300' : 'bg-slate-50 text-slate-600 dark:bg-slate-950/70 dark:text-slate-300'}`}>
                  {branch}
                </span>
              ))}
            </div>
          </div>

          <div className="rounded-[28px] border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
              <TerminalSquare size={16} className="text-cyan-500" />
              Languages
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              {languageSummary.map((language) => (
                <span key={language} className="rounded-full bg-slate-50 px-3 py-1.5 text-sm text-slate-600 dark:bg-slate-950/70 dark:text-slate-300">
                  {language}
                </span>
              ))}
            </div>
          </div>

          <div className="rounded-[28px] border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
              <BookOpen size={16} className="text-cyan-500" />
              README
            </div>
            <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50/80 p-4 text-sm leading-7 text-slate-600 dark:border-slate-800 dark:bg-slate-950/70 dark:text-slate-300">
              <p className="font-medium text-slate-900 dark:text-white">{readmeFile?.name ?? 'README.md'}</p>
              <p className="mt-2">A polished workspace overview for shipping snapshots, reviewing changes, and collaborating with confidence.</p>
            </div>
          </div>

          <div className="rounded-[28px] border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
              <History size={16} className="text-cyan-500" />
              Recent Commits
            </div>
            <div className="mt-4 space-y-3">
              {recentCommits.map((commit) => (
                <button
                  key={commit.id}
                  type="button"
                  onClick={() => navigate(`/repo/${repo.id}/commit/${commit.id}`)}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-left transition hover:border-cyan-500 hover:bg-white dark:border-slate-800 dark:bg-slate-950/70"
                >
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-semibold text-slate-900 dark:text-white">{commit.message}</p>
                    <span className="text-xs text-slate-500 dark:text-slate-400">{commit.id?.slice(0, 8)}</span>
                  </div>
                  <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">{commit.author}</p>
                  <p></p>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default CommitHistoryPage
