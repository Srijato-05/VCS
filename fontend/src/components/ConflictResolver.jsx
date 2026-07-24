function ConflictResolver({
  conflict,
  onAcceptCurrent,
  onAcceptIncoming,
  onResolve,
}) {
  return (
    <div className="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-white">{conflict.fileName}</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400">Choose the version to keep before marking as resolved.</p>
        </div>
        <span className="rounded-full border border-amber-300 bg-amber-50 px-3 py-1 text-xs font-semibold uppercase tracking-[0.2em] text-amber-600 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-300">
          Conflict
        </span>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="rounded-2xl border border-rose-200 bg-rose-50 p-4 dark:border-rose-500/20 dark:bg-rose-950/20">
          <div className="mb-2 text-sm font-semibold uppercase tracking-[0.2em] text-rose-600 dark:text-rose-300">Current</div>
          <pre className="whitespace-pre-wrap font-mono text-xs leading-6 text-slate-700 dark:text-slate-300">{conflict.currentContent.join('')}</pre>
        </div>

        <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 dark:border-emerald-500/20 dark:bg-emerald-950/20">
          <div className="mb-2 text-sm font-semibold uppercase tracking-[0.2em] text-emerald-600 dark:text-emerald-300">Incoming</div>
          <pre className="whitespace-pre-wrap font-mono text-xs leading-6 text-slate-700 dark:text-slate-300">{conflict.incomingContent.join('')}</pre>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-3">
        <button type="button" onClick={onAcceptCurrent} className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 transition hover:border-cyan-500 hover:text-cyan-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
          Accept current
        </button>

        <button type="button" onClick={onAcceptIncoming} className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 transition hover:border-cyan-500 hover:text-cyan-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
          Accept incoming
        </button>

        <button type="button" onClick={onResolve} className="rounded-xl bg-slate-950 px-3 py-2 text-sm font-semibold text-white transition hover:bg-cyan-600 dark:bg-cyan-500 dark:text-slate-950">
          Mark resolved
        </button>
      </div>
    </div>
  )
}

export default ConflictResolver