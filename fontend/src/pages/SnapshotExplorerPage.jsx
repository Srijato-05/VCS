import { FolderGit2, Search, GitBranch } from "lucide-react";
import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { useRepo } from "../context/RepoContext";
import SnapshotCard from "../components/SnapshotCard";

export default function SnapshotExplorerPage() {
  const { repoId } = useParams();
  const { selectedRepo } = useRepo();

  const [search, setSearch] = useState("");
  const [branch, setBranch] = useState("All Branches");

  const snapshots = useMemo(() => {
    return (selectedRepo?.commits || []).map((commit) => ({
      snapshotId: commit.id,
      commitId: commit.id,
      message: commit.message,
      author: commit.author,
      timestamp: commit.timestamp,
      branch: selectedRepo.currentBranch || 'main',
    }));
  }, [selectedRepo]);

  const filteredSnapshots = useMemo(() => {
    return snapshots.filter((snapshot) => {
      const matchesSearch =
        snapshot.snapshotId.toLowerCase().includes(search.toLowerCase()) ||
        snapshot.message.toLowerCase().includes(search.toLowerCase()) ||
        snapshot.commitId.toLowerCase().includes(search.toLowerCase());

      const matchesBranch =
        branch === "All Branches" || snapshot.branch === branch;

      return matchesSearch && matchesBranch;
    });
  }, [snapshots, search, branch]);

  const latestSnapshot = filteredSnapshots[0];

  return (
    <div className="space-y-8">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-3 text-3xl font-bold text-slate-900 dark:text-white">
          <FolderGit2 className="text-cyan-500" size={32} />
          Snapshot Explorer
        </h1>

        <p className="mt-2 text-slate-500 dark:text-slate-400">
          Browse repository snapshots stored in the DAG.
        </p>
      </div>

      {/* Summary */}
      <div className="grid gap-4 md:grid-cols-4">
        <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <p className="text-sm text-slate-500">Repository</p>
          <h2 className="mt-2 text-lg font-semibold truncate">{repoId}</h2>
        </div>

        <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <p className="text-sm text-slate-500">Snapshots</p>
          <h2 className="mt-2 text-lg font-semibold">
            {filteredSnapshots.length}
          </h2>
        </div>

        <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <p className="text-sm text-slate-500">Current Branch</p>
          <h2 className="mt-2 text-lg font-semibold truncate">
            {latestSnapshot?.branch ?? "main"}
          </h2>
        </div>

        <div className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <p className="text-sm text-slate-500">Latest Snapshot</p>
          <p className="mt-2 text-xs font-bold uppercase tracking-widest text-cyan-500">HEAD</p>
          <h2 className="mt-1 font-mono text-lg font-semibold truncate" title={latestSnapshot?.snapshotId}>
            {latestSnapshot?.snapshotId?.slice(0, 8) ?? "N/A"}
          </h2>
        </div>
      </div>

      {/* Search + Filter */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">

        <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-900">
          <Search size={18} className="text-slate-400" />

          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search snapshots..."
            className="w-72 bg-transparent outline-none"
          />
        </div>

        <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 dark:border-slate-700 dark:bg-slate-900">
          <GitBranch size={18} className="text-cyan-500" />

          <select
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            className="bg-transparent outline-none text-slate-900 dark:text-white [&>option]:bg-slate-800 [&>option]:text-white"
          >
            <option value="All Branches">All Branches</option>
            {(Array.isArray(selectedRepo?.branches) ? selectedRepo.branches : Object.keys(selectedRepo?.branches ?? {})).map(b => (
              <option key={b} value={b}>{b}</option>
            ))}
          </select>
        </div>

      </div>

      {/* Snapshot Cards */}

      <div className="space-y-5">

        {filteredSnapshots.length === 0 ? (

          <div className="rounded-2xl border border-dashed border-slate-300 py-20 text-center dark:border-slate-700">

            <FolderGit2
              size={50}
              className="mx-auto text-slate-400"
            />

            <h2 className="mt-4 text-lg font-semibold">
              No Snapshots Found
            </h2>

            <p className="mt-2 text-slate-500">
              Try changing the search or branch filter.
            </p>

          </div>

        ) : (

          filteredSnapshots.map((snapshot) => (
            <SnapshotCard
              key={snapshot.snapshotId}
              snapshot={snapshot}
            />
          ))

        )}

      </div>
    </div>
  );
}