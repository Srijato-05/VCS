import { useEffect, useState } from "react";
import { FileText, Search, User, Clock, ChevronRight, AlertCircle } from "lucide-react";
import { useParams, Link, useSearchParams } from "react-router-dom";
import { useRepo } from "../context/RepoContext";

export default function TracePage() {
  const { repoId } = useParams();
  const { selectedRepo } = useRepo();
  const [trackedFiles, setTrackedFiles] = useState([]);
  const [traceData, setTraceData] = useState([]);
  const [loadingFiles, setLoadingFiles] = useState(true);
  const [loadingTrace, setLoadingTrace] = useState(false);
  const [error, setError] = useState(null);
  const [fileSearch, setFileSearch] = useState("");

  const [searchParams, setSearchParams] = useSearchParams();
  const selectedFile = searchParams.get("file") || "";

  const setSelectedFile = (file) => {
    if (file) {
      setSearchParams({ file });
    } else {
      setSearchParams({});
    }
  };

  // Fetch file list from status
  useEffect(() => {
    async function fetchFiles() {
      try {
        setLoadingFiles(true);
        setError(null);
        const res = await fetch("/api/status");
        if (!res.ok) {
          throw new Error("Failed to load repository files status.");
        }
        const data = await res.json();
        const files = data.trackedFiles || [];
        setTrackedFiles(files);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoadingFiles(false);
      }
    }
    fetchFiles();
  }, [repoId]);

  // Fetch trace logs when selected file changes
  useEffect(() => {
    if (!selectedFile) {
      setTraceData([]);
      return;
    }

    async function fetchTrace() {
      try {
        setLoadingTrace(true);
        setError(null);
        const res = await fetch(`/api/trace?file=${encodeURIComponent(selectedFile)}`);
        if (!res.ok) {
          const errData = await res.json().catch(() => ({}));
          throw new Error(errData.error || `Failed to trace file: ${selectedFile}`);
        }
        const data = await res.json();
        setTraceData(data || []);
      } catch (err) {
        setError(err.message);
        setTraceData([]);
      } finally {
        setLoadingTrace(false);
      }
    }
    fetchTrace();
  }, [selectedFile]);

  const filteredFiles = trackedFiles.filter((file) =>
    file.toLowerCase().includes(fileSearch.toLowerCase())
  );

  // If no file is selected, render the file explorer list
  if (!selectedFile) {
    return (
      <div className="space-y-6">
        {/* Header */}
        <div className="rounded-3xl border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.35em] text-cyan-500">Trace Explorer</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900 dark:text-white">
              File Blame & History
            </h2>
            <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
              Select a file from the repository to trace its line-by-line history and blame.
            </p>
          </div>
        </div>

        {error && (
          <div className="rounded-3xl border border-rose-200 bg-rose-50/50 p-6 text-rose-700 dark:border-rose-900/30 dark:bg-rose-950/20 dark:text-rose-400">
            <div className="flex items-center gap-3">
              <AlertCircle size={20} />
              <div>
                <p className="font-semibold">Trace Operation Failed</p>
                <p className="mt-1 text-sm">{error}</p>
              </div>
            </div>
          </div>
        )}

        {loadingFiles ? (
          <div className="flex min-h-[300px] items-center justify-center rounded-3xl border border-slate-200/70 bg-white/80 p-8 dark:border-slate-800 dark:bg-slate-900/70">
            <div className="flex flex-col items-center gap-3">
              <div className="h-8 w-8 animate-spin rounded-full border-4 border-slate-200 border-t-cyan-500" />
              <p className="text-sm text-slate-500 dark:text-slate-400">Loading files...</p>
            </div>
          </div>
        ) : trackedFiles.length === 0 ? (
          <div className="flex min-h-[300px] flex-col items-center justify-center rounded-3xl border border-slate-200/70 bg-white/80 p-8 text-center dark:border-slate-800 dark:bg-slate-900/70">
            <FileText size={40} className="text-slate-400" />
            <h3 className="mt-4 text-lg font-medium text-slate-950 dark:text-white">No Tracked Files</h3>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Add and save files in this repository to trace them.</p>
          </div>
        ) : (
          <div className="space-y-4">
            {/* Search Input */}
            <div className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
              <Search size={18} className="text-slate-400" />
              <input
                type="text"
                placeholder="Search repository files..."
                value={fileSearch}
                onChange={(e) => setFileSearch(e.target.value)}
                className="w-full bg-transparent outline-none text-sm text-slate-700 dark:text-slate-200"
              />
            </div>

            {/* Files Grid / List */}
            <div className="rounded-3xl border border-slate-200/70 bg-white/80 overflow-hidden shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
              <div className="divide-y divide-slate-100 dark:divide-slate-800/40">
                {filteredFiles.length === 0 ? (
                  <div className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">
                    No files matching "{fileSearch}"
                  </div>
                ) : (
                  filteredFiles.map((file) => (
                    <button
                      key={file}
                      onClick={() => setSelectedFile(file)}
                      className="w-full flex items-center justify-between px-6 py-4 hover:bg-slate-50/50 dark:hover:bg-slate-950/20 text-left transition-colors"
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <FileText size={18} className="text-cyan-500 shrink-0" />
                        <span className="text-sm font-medium text-slate-800 dark:text-slate-200 truncate font-mono">
                          {file}
                        </span>
                      </div>
                      <span className="text-xs font-semibold text-cyan-600 dark:text-cyan-400 flex items-center gap-1">
                        Trace File
                        <ChevronRight size={14} />
                      </span>
                    </button>
                  ))
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  // Blame / History table when file is selected
  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="rounded-3xl border border-slate-200/70 bg-white/80 p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setSelectedFile("")}
                className="text-xs font-semibold text-cyan-600 dark:text-cyan-400 hover:underline flex items-center gap-1"
              >
                &larr; Back to Files
              </button>
            </div>
            <p className="mt-3 text-sm font-semibold uppercase tracking-[0.35em] text-cyan-500">Trace Explorer</p>
            <h2 className="mt-2 text-2xl font-semibold text-slate-900 dark:text-white truncate max-w-lg font-mono" title={selectedFile}>
              {selectedFile}
            </h2>
            <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
              Trace line-by-line changes back to their originating commit and author.
            </p>
          </div>
          
          {/* File Selector */}
          {!loadingFiles && trackedFiles.length > 0 && (
            <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 dark:border-slate-700 dark:bg-slate-950/70">
              <FileText size={16} className="text-cyan-500" />
              <select
                value={selectedFile}
                onChange={(e) => setSelectedFile(e.target.value)}
                className="bg-transparent text-sm font-medium text-slate-700 outline-none dark:text-slate-350"
              >
                {trackedFiles.map((f) => (
                  <option key={f} value={f} className="dark:bg-slate-900">
                    {f}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      </div>

      {error && (
        <div className="rounded-3xl border border-rose-200 bg-rose-50/50 p-6 text-rose-700 dark:border-rose-900/30 dark:bg-rose-950/20 dark:text-rose-400">
          <div className="flex items-center gap-3">
            <AlertCircle size={20} />
            <div>
              <p className="font-semibold">Trace Operation Failed</p>
              <p className="mt-1 text-sm">{error}</p>
            </div>
          </div>
        </div>
      )}

      {loadingTrace ? (
        <div className="flex min-h-[300px] items-center justify-center rounded-3xl border border-slate-200/70 bg-white/80 p-8 dark:border-slate-800 dark:bg-slate-900/70">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-slate-200 border-t-cyan-500" />
            <p className="text-sm text-slate-500 dark:text-slate-400">Tracing file modifications...</p>
          </div>
        </div>
      ) : (
        <div className="overflow-hidden rounded-3xl border border-slate-200/70 bg-white/80 shadow-sm dark:border-slate-800 dark:bg-slate-900/70">
          <div className="bg-slate-50/50 px-6 py-3 border-b border-slate-200 dark:border-slate-800 dark:bg-slate-900/50 flex justify-between items-center text-xs font-semibold uppercase text-slate-500 dark:text-slate-400">
            <span>Commit / Author / Date</span>
            <span>Source Code ({selectedFile})</span>
          </div>

          <div className="divide-y divide-slate-100 dark:divide-slate-800/40 font-mono text-xs">
            {traceData.map((item, idx) => (
              <div key={idx} className="flex hover:bg-slate-50/50 dark:hover:bg-slate-950/20 transition-colors">
                {/* Blame / Metadata Panel */}
                <div className="w-[300px] shrink-0 border-r border-slate-200/60 dark:border-slate-800/60 bg-slate-50/20 dark:bg-slate-950/10 px-4 py-2.5 flex flex-col gap-1 text-[10px] text-slate-500 select-none">
                  <div className="flex items-center justify-between">
                    <Link
                      to={`/repo/${repoId}/commit/${item.hash}`}
                      className="font-semibold text-cyan-600 dark:text-cyan-400 hover:underline flex items-center gap-0.5"
                    >
                      {item.hash ? item.hash.substring(0, 8) : ""}
                      <ChevronRight size={10} />
                    </Link>
                    <span className="flex items-center gap-1 font-sans">
                      <User size={10} />
                      {item.author}
                    </span>
                  </div>
                  <div className="flex items-center gap-1 font-sans">
                    <Clock size={10} />
                    <span>{item.date}</span>
                  </div>
                </div>

                {/* Code Line Panel */}
                <div className="flex-1 px-6 py-2.5 flex items-start gap-4 overflow-x-auto whitespace-pre">
                  <span className="w-8 text-right select-none text-slate-400 dark:text-slate-600 text-[10px] pr-2">
                    {idx + 1}
                  </span>
                  <span className="text-slate-800 dark:text-slate-300 font-mono">
                    {item.line}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
