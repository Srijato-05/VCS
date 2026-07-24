import { useEffect, useMemo, useState } from 'react'
import {Activity,AlertCircle,BellRing,ChevronLeft,ChevronRight,FolderGit2,GitBranch,GitCommitVertical,LayoutDashboard,Monitor,
  MoonStar,Plus,Search,Settings,Sparkles,SunMedium,UserCircle2,} from 'lucide-react'
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import { RepoProvider, useRepo } from './context/RepoContext'
import BranchManagerPage from './pages/BranchManagerPage'
import CommitHistoryPage from './pages/CommitHistoryPage'
import DiffViewPage from './pages/DiffViewPage'
import LoginPage from './pages/LoginPage'
import MergeConflictPage from './pages/MergeConflictPage'
import ProfilePage from './pages/ProfilePage'
import RepoListPage from './pages/RepoListPage'
import SettingsPage from './pages/SettingsPage'
import SignupPage from './pages/SignupPage'
import WelcomePage from './pages/WelcomePage'
import SnapshotExplorerPage from './pages/SnapshotExplorerPage'
import SnapshotTree from "./components/SnapshotTree";
import PullRequestsPage from "./pages/PullRequestsPage"; import { GitPullRequest, History, Eye } from "lucide-react";
import RaisePullRequestPage from "./pages/RaisePullRequestPage";
import CreateRepositoryPage from "./pages/CreateRepositoryPage";
import RepositoriesPage from "./pages/RepositoriesPage";
import LedgerPage from "./pages/LedgerPage";
import TracePage from "./pages/TracePage";

function ProtectedRoute({ user, children }) {
  if (!user) {
    return <Navigate to="/" replace />
  }

  return children
}

function AppLayout({ user, onLogout, theme, onThemeChange, children }) {
  const location = useLocation()
  const { selectedRepo } = useRepo()
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  const workflowLinks = useMemo(() => {
  const repoPath = selectedRepo ? `/repo/${selectedRepo.id}` : "/repos";

  return [
    {
      to: "/repos",
      label: "Dashboard",
      icon: LayoutDashboard,
    },

    {
      to: "/repositories",
      label: "Repositories",
      icon: FolderGit2,
    },

    {
      to: "/profile",
      label: "Profile",
      icon: UserCircle2,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/snapshots`
        : "/repos",
      label: "Snapshots",
      icon: FolderGit2,
    },

    {
      to: repoPath,
      label: "Commits",
      icon: GitCommitVertical,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/branches`
        : "/repos",
      label: "Branches",
      icon: GitBranch,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/merge`
        : "/repos",
      label: "Merge",
      icon: AlertCircle,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/pull-requests`
        : "/repos",
      label: "Pull Requests",
      icon: GitPullRequest,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/ledger`
        : "/repos",
      label: "Ledger",
      icon: History,
    },

    {
      to: selectedRepo
        ? `/repo/${selectedRepo.id}/trace`
        : "/repos",
      label: "Trace Explorer",
      icon: Eye,
    },

    {
      to: "/settings",
      label: "Settings",
      icon: Settings,
    },
  ];
}, [selectedRepo]);

  const checkIsActive = (link) => {
    const path = location.pathname
    if (link.to === '/repos') return path === '/repos'
    if (link.to === '/repositories') return path === '/repositories' || path.startsWith('/repositories/')
    if (link.to === '/profile') return path === '/profile'
    if (link.to === '/settings') return path === '/settings'

    if (!selectedRepo) return false
    const baseRepoPath = `/repo/${selectedRepo.id}`

    if (link.label === 'Commits') {
      return path === baseRepoPath || path.startsWith(`${baseRepoPath}/commit/`)
    }

    if (link.label === 'Snapshots') {
      return path.startsWith(`${baseRepoPath}/snapshots`) || path.startsWith(`${baseRepoPath}/snapshot/`)
    }

    if (link.label === 'Pull Requests') {
      return path.startsWith(`${baseRepoPath}/pull-requests`)
    }

    return path.startsWith(link.to)
  }

  const breadcrumbs = useMemo(() => {
    const segments = location.pathname.split('/').filter(Boolean)
    return segments.length > 0 ? segments : ['workspace']
  }, [location.pathname])

  const themeIcon = theme === 'dark' ? <MoonStar size={16} /> : theme === 'light' ? <SunMedium size={16} /> : <Monitor size={16} />

  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top_left,rgba(125,211,252,0.18),transparent_28%),linear-gradient(135deg,#f8fafc_0%,#eef2ff_100%)] text-slate-900 transition-colors duration-300 dark:bg-[radial-gradient(circle_at_top_left,rgba(34,211,238,0.16),transparent_28%),linear-gradient(135deg,#020617_0%,#111827_100%)] dark:text-slate-100">
      <div className="mx-auto flex min-h-screen max-w-7xl gap-4 px-3 py-3 sm:px-4 lg:px-6">
        <aside className={`hidden lg:flex flex-col rounded-[28px] border border-slate-200/70 bg-white/80 shadow-[0_20px_60px_rgba(15,23,42,0.10)] backdrop-blur-xl transition-all duration-300 dark:border-slate-800 dark:bg-slate-900/80 ${sidebarCollapsed ? 'w-20 p-3' : 'w-72 p-4'}`}>
          <div className={`flex ${sidebarCollapsed ? 'flex-col gap-3 items-center' : 'items-center justify-between'}`}>
            <div className={`flex items-center gap-3 ${sidebarCollapsed ? 'justify-center' : ''}`}>
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-slate-950 text-sm font-semibold text-white shadow-lg dark:bg-cyan-500/20 dark:text-cyan-300">
                V
              </div>
              {!sidebarCollapsed ? (
                <div>
                  <p className="text-[10px] font-semibold uppercase tracking-[0.35em] text-slate-500 dark:text-slate-400">Snapshot VCS</p>
                  <p className="text-sm font-semibold text-slate-900 dark:text-white">Developer Suite</p>
                </div>    
              ) : null}
            </div>
            <button
              type="button"
              onClick={() => setSidebarCollapsed((current) => !current)}
              className="rounded-full border border-slate-200 bg-white p-2 text-slate-500 transition hover:border-cyan-500 hover:text-cyan-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
              aria-label="Toggle sidebar"
            >
              {sidebarCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
            </button>
          </div>

          {!sidebarCollapsed ? (
            <div className="mt-6 rounded-2xl border border-slate-200/70 bg-slate-50/80 p-3 dark:border-slate-800 dark:bg-slate-950/70">
              <div className="flex items-center gap-2 text-sm font-medium text-slate-600 dark:text-slate-300">
                <FolderGit2 size={16} className="text-cyan-500" />
                <span>Quick switch</span>
              </div>
              <div className="mt-3 rounded-xl border border-slate-200/70 bg-white px-3 py-2 text-sm text-slate-700 shadow-sm dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
                {selectedRepo ? selectedRepo.name : 'No repository selected'}
              </div>
            </div>
          ) : null}

          <nav className={`mt-6 flex-1 ${sidebarCollapsed ? 'space-y-3' : 'space-y-1'}`}>
            {workflowLinks.map((link) => {
              const isActive = checkIsActive(link)
              const Icon = link.icon
              return (
                <Link
                  key={link.label}
                  to={link.to}
                  className={`flex items-center rounded-2xl py-2.5 text-sm font-medium transition ${
                    sidebarCollapsed ? 'justify-center px-0 w-11 h-11 mx-auto' : 'gap-3 px-3'
                  } ${
                    isActive
                      ? 'bg-cyan-500/15 text-cyan-600 shadow-sm dark:bg-cyan-500/20 dark:text-cyan-300'
                      : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'
                  }`}
                  title={link.label}
                >
                  <Icon size={18} />
                  {!sidebarCollapsed ? <span>{link.label}</span> : null}
                </Link>
              )
            })}
          </nav>

          <div className={`rounded-2xl border border-slate-200/70 bg-slate-50/80 dark:border-slate-800 dark:bg-slate-950/70 ${sidebarCollapsed ? 'p-2 flex justify-center' : 'p-3'}`}>
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-900 text-sm font-semibold text-white dark:bg-slate-800">
                {user?.name?.slice(0, 1).toUpperCase() ?? 'D'}
              </div>
              {!sidebarCollapsed ? (
                <div>
                  <p className="text-sm font-semibold text-slate-900 dark:text-white">{user?.name ?? 'Developer'}</p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">{user?.email ?? 'dev@vcs.dev'}</p>
                </div>
              ) : null}
            </div>
            {!sidebarCollapsed ? (
              <button
                type="button"
                onClick={onLogout}
                className="mt-3 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:border-rose-400 hover:text-rose-500 dark:border-slate-700 dark:text-slate-300"
              >
                Sign out
              </button>
            ) : null}
          </div>
        </aside>

        <div className="flex min-h-[calc(100vh-1.5rem)] flex-1 flex-col">
          <header className="sticky top-3 z-20 rounded-3xl border border-slate-200/70 bg-white/80 px-4 py-3 shadow-[0_14px_50px_rgba(15,23,42,0.08)] backdrop-blur-xl dark:border-slate-800 dark:bg-slate-900/80 sm:px-5">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div className="flex flex-wrap items-center gap-3">
                <button
                  type="button"
                  onClick={() => setSidebarCollapsed((current) => !current)}
                  className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 transition hover:border-cyan-500 hover:text-cyan-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 lg:hidden"
                >
                  <LayoutDashboard size={16} />
                  Menu
                </button>
                <div className="hidden rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-400 sm:flex max-w-xs truncate">
                  <span className="mr-2 text-slate-400">/</span>
                  {breadcrumbs.join(' / ')}
                </div>
              </div>

              <div className="flex flex-wrap items-center justify-end gap-2 sm:gap-3">
                <label className="flex items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-500 shadow-sm dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-400">
                  <Search size={16} className="text-slate-400" />
                  <input
                    aria-label="Global search"
                    placeholder="Search"
                    className="w-20 bg-transparent outline-none transition-all duration-200 focus:w-32 sm:w-32 sm:focus:w-44"
                  />
                </label>

                {location.pathname.startsWith('/repo/') && selectedRepo ? (
                  <>
                    <div className="flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-300">
                      <GitBranch size={14} className="text-cyan-500" />
                      <span className="font-medium">{selectedRepo.currentBranch ?? 'main'}</span>
                    </div>
                    <div className="hidden sm:flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-300">
                      <Activity size={14} className="text-emerald-500 animate-pulse" />
                      <span>Ready</span>
                    </div>
                  </>
                ) : null}

                <div className="hidden sm:block h-6 w-[1px] bg-slate-200 dark:bg-slate-800" />

                <button type="button" className="rounded-full border border-slate-200 bg-white p-2 text-slate-500 transition hover:border-cyan-500 hover:text-cyan-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400" aria-label="Notifications">
                  <BellRing size={16} />
                </button>
                <button type="button" onClick={onThemeChange} className="rounded-full border border-slate-200 bg-white p-2 text-slate-500 transition hover:border-cyan-500 hover:text-cyan-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400" aria-label="Toggle theme">
                  {themeIcon}
                </button>

                <div className="h-6 w-[1px] bg-slate-200 dark:bg-slate-800" />

                <Link to="/profile" className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-600 transition hover:border-cyan-500 hover:text-cyan-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
                  <UserCircle2 size={16} className="text-slate-400" />
                  <span className="hidden md:inline">{user?.name ?? 'Dev'}</span>
                </Link>

                <Link to="/repositories/new" className="inline-flex items-center justify-center gap-1.5 rounded-full bg-slate-950 px-3 py-2 text-sm font-semibold text-white transition hover:bg-cyan-600 dark:bg-cyan-500/90 dark:text-slate-950 shadow-sm" title="Create Repository">
                  <Plus size={16} />
                  <span className="hidden sm:inline">Create Repo</span>
                </Link>
              </div>
            </div>
          </header>

          <main className="mt-4 flex-1 rounded-[28px] border border-slate-200/70 bg-white/70 p-4 shadow-[0_18px_50px_rgba(15,23,42,0.08)] backdrop-blur-xl transition-colors duration-300 dark:border-slate-800 dark:bg-slate-900/70 sm:p-6 lg:p-8">
            {children}
          </main>
        </div>
      </div>
    </div>
  )
}

function AppRoutes({ theme, onThemeChange }) {
  const { user, login, signup, logout } = useAuth()

  const handleLogin = async (account) => {
    await login(account)
  }

  const handleSignup = async (account) => {
    await signup(account)
  }

  const handleLogout = () => {
    logout()
  }

  return (
    <Routes>
      <Route path="/" element={<LoginPage onLogin={handleLogin} user={user} />} />
      <Route path="/signup" element={<SignupPage onSignup={handleSignup} />} />
      <Route
        path="/welcome"
        element={
          <ProtectedRoute user={user}>
            <WelcomePage user={user} />
          </ProtectedRoute>
        }
      />
      <Route
        path="/repositories"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <RepositoriesPage />
            </AppLayout>
          </ProtectedRoute>
        }
     />
      <Route
        path="/repos"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <RepoListPage user={user} />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repo/:repoId"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <CommitHistoryPage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repositories/new"
        element={
          <ProtectedRoute user={user}>
           <AppLayout  user={user}  onLogout={handleLogout}  theme={theme}  onThemeChange={onThemeChange}  >
             <CreateRepositoryPage />
           </AppLayout>
         </ProtectedRoute>
        }
     />
      <Route
        path="/repo/:repoId/snapshots"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <SnapshotExplorerPage />
            </AppLayout>
          </ProtectedRoute>
        }
     />
     <Route 
       path="/repo/:repoId/snapshot/:snapshotId"
       element={
          <ProtectedRoute user={user}>
             <AppLayout user={user}  onLogout={handleLogout}  theme={theme}  onThemeChange={onThemeChange}  >
               <SnapshotTree />
             </AppLayout>
          </ProtectedRoute>
        }
    />
    <Route
      path="/repo/:repoId/pull-requests"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user}  onLogout={handleLogout}  theme={theme}  onThemeChange={onThemeChange}  >
              <PullRequestsPage />
            </AppLayout>
          </ProtectedRoute>
       }
    />
    <Route
      path="/repo/:repoId/pull-requests/new"
      element={
        <ProtectedRoute user={user}>
          <AppLayout user={user}  onLogout={handleLogout}  theme={theme}  onThemeChange={onThemeChange}  >
            <RaisePullRequestPage />
         </AppLayout>
        </ProtectedRoute>
      }
    />
      <Route
        path="/repo/:repoId/commit/:commitId"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <DiffViewPage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repo/:repoId/branches"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <BranchManagerPage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repo/:repoId/merge"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <MergeConflictPage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repo/:repoId/ledger"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <LedgerPage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/repo/:repoId/trace"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <TracePage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/profile"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <ProfilePage />
            </AppLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/settings"
        element={
          <ProtectedRoute user={user}>
            <AppLayout user={user} onLogout={handleLogout} theme={theme} onThemeChange={onThemeChange}>
              <SettingsPage user={user} theme={theme} onThemeChange={onThemeChange} />
            </AppLayout>
          </ProtectedRoute>
        }
      />
    </Routes>
  )
}

function App() {
  const [theme, setTheme] = useState(() => {
    if (typeof window === 'undefined') {
      return 'dark'
    }

    return window.localStorage.getItem('vcsTheme') ?? 'dark'
  })

  useEffect(() => {
    if (typeof window === 'undefined') {
      return
    }

    const root = window.document.documentElement
    const isDark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)

    root.classList.toggle('dark', isDark)
    root.classList.toggle('light', !isDark)
    root.style.colorScheme = isDark ? 'dark' : 'light'
    window.localStorage.setItem('vcsTheme', theme)
  }, [theme])

  const handleThemeChange = () => {
    setTheme((current) => {
      if (current === 'dark') return 'light'
      if (current === 'light') return 'system'
      return 'dark'
    })
  }
  return (
    <BrowserRouter>
      <AuthProvider>
        <RepoProvider>
          <AppRoutes theme={theme} onThemeChange={handleThemeChange} />
        </RepoProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
