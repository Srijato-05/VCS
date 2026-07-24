/**
 * @file DraftFlow.java
 * @description The main CLI application entry point and command router for the DraftFlow Version Control System.
 * Uses the Picocli library to register, parse, and route subcommands.
 * 
 * DESIGN RATIONALE:
 * - Subcommands are implemented as nested static classes inside `DraftFlow` implementing `Callable<Integer>` or `Runnable`.
 * - Centralizes exception handling to present user-friendly troubleshooting tips (e.g. database locks, file permissions, data corruption)
 *   while maintaining strict debug logging when `DRAFTFLOW_DEBUG` is set.
 * - Utilizes H2 DB as the metadata store and an object database (CAS) for commit blobs, trees, and delta lists.
 */

package com.draftflow;

import com.draftflow.core.*;
import com.draftflow.cdc.FastCDC;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import com.draftflow.merge.MergeEngine;
import com.draftflow.remote.*;
import com.draftflow.ui.UiServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.draftflow.merge.LineMerge.Edit;
import com.draftflow.merge.LineMerge.EditType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "draftflow", mixinStandardHelpOptions = true, version = "draftflow 1.0",
        description = "DraftFlow: High-Performance Snapshot-based DAG Version Control System",
        subcommands = {
                DraftFlow.SetupCmd.class,
                DraftFlow.StatusCmd.class,
                DraftFlow.SaveCmd.class,
                DraftFlow.SwitchCmd.class,
                DraftFlow.MergeCmd.class,
                DraftFlow.ResolveCmd.class,
                DraftFlow.UploadCmd.class,
                DraftFlow.DownloadCmd.class,
                DraftFlow.HistoryCmd.class,
                DraftFlow.BranchCmd.class,
                DraftFlow.UndoCmd.class,
                DraftFlow.DashboardCmd.class,
                DraftFlow.VerifyCmd.class,
                DraftFlow.PruneCmd.class,
                DraftFlow.StashCmd.class,
                DraftFlow.DiffCmd.class,
                DraftFlow.KeysCmd.class,
                DraftFlow.RebaseCmd.class,
                DraftFlow.CherryPickCmd.class,
                DraftFlow.IgnoreCmd.class,
                DraftFlow.CleanCmd.class,
                DraftFlow.LedgerCmd.class,
                DraftFlow.TraceCmd.class,
                DraftFlow.GitImportCmd.class,
                DraftFlow.GitExportCmd.class,
                DraftFlow.HooksCmd.class,
                DraftFlow.ConfigCmd.class
        })
public class DraftFlow implements Callable<Integer> {

    public static Path getCurrentDir() {
        return Paths.get(System.getProperty("draftflow.dir", ".")).toAbsolutePath().normalize();
    }

    public static String getAuthor(com.draftflow.db.MetadataStore db) {
        if (db != null) {
            String name = db.getConfig("author.name");
            String email = db.getConfig("author.email");
            if (name != null && !name.trim().isEmpty()) {
                if (email != null && !email.trim().isEmpty()) {
                    return name + " <" + email + ">";
                }
                return name;
            }
        }
        return System.getProperty("user.name", "User");
    }


    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(runMain(args));
    }

    public static class DraftFlowExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
            System.err.println("================================================================================");
            System.err.println("                     DRAFTFLOW VCS CRITICAL EXCEPTION");
            System.err.println("================================================================================");
            System.err.println("Operation failed due to an unexpected error.");
            System.err.println("Error Type: " + ex.getClass().getName());
            System.err.println("Message:    " + (ex.getMessage() != null ? ex.getMessage() : ""));
            System.err.println();
            System.err.println("Diagnostics & Troubleshooting Tips:");
            if (ex instanceof java.io.FileNotFoundException || (ex.getMessage() != null && (ex.getMessage().contains("Access is denied") || ex.getMessage().contains("Permission")))) {
                System.err.println("  -> Permissions Issue: Please verify that you have read/write access to the repository directory.");
            } else if (ex.getMessage() != null && (ex.getMessage().contains("lock") || ex.getMessage().contains("Lock"))) {
                System.err.println("  -> Lock Contention: Another process may be running a DraftFlow operation. Please wait for it to complete.");
            } else if (ex.getMessage() != null && (ex.getMessage().contains("corrupted") || ex.getMessage().contains("corruption"))) {
                System.err.println("  -> Data Corruption: A stored object failed checksum verification. Run 'draftflow verify' to verify and heal.");
            } else {
                System.err.println("  -> System State: Ensure you have enough disk space and that no other application is locking the database files.");
            }
            System.err.println();
            System.err.println("System Information:");
            System.err.println("  OS:           " + System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")");
            System.err.println("  Java Version: " + System.getProperty("java.version"));
            System.err.println("================================================================================");
            
            if ("true".equalsIgnoreCase(System.getenv("DRAFTFLOW_DEBUG")) || "true".equalsIgnoreCase(System.getProperty("DRAFTFLOW_DEBUG"))) {
                ex.printStackTrace();
            } else {
                System.err.println("To see the full stack trace, set the environment variable DRAFTFLOW_DEBUG=true");
            }
            return 1;
        }
    }

    public static String getLogLevel() {
        return "INFO";
    }

    public static int runMain(String[] args) {
        CommandLine cmd = new CommandLine(new DraftFlow());
        cmd.setExecutionExceptionHandler(new DraftFlowExecutionExceptionHandler());
        return cmd.execute(args);
    }

    // --- SUBCOMMANDS ---

    @Command(name = "setup", description = "Initialize a new, empty DraftFlow repository")
    public static class SetupCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (Files.exists(cas.getDraftFlowDir())) {
                System.out.println("DraftFlow repository already initialized in " + cas.getRootDir());
                return 0;
            }
            cas.init();
            Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setConfig("activeHead", "heads/main");
                db.commit();
            }

            // Create hooks directory and populate samples
            Path hooksDir = cas.getDraftFlowDir().resolve("hooks");
            Files.createDirectories(hooksDir);
            Files.writeString(hooksDir.resolve("pre-commit.sample"), "#!/bin/sh\n# Pre-commit hook sample\n# Exit with non-zero if validation fails\nexit 0\n", java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(hooksDir.resolve("post-commit.sample"), "#!/bin/sh\n# Post-commit hook sample\n# Runs after commit saves successfully\necho \"Commit saved successfully!\"\n", java.nio.charset.StandardCharsets.UTF_8);

            System.out.println("Initialized empty DraftFlow repository in " + cas.getDraftFlowDir());
            return 0;
        }
    }

    @Command(name = "status", description = "Show the working tree status")
    public static class StatusCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                String activeHead = db.getConfig("activeHead");
                String activeRev = db.getConfig("activeRevisionHash");
                String activeChange = db.getConfig("activeChangeId");

                System.out.println("On branch: " + (activeHead != null ? activeHead.replace("heads/", "") : "detached"));
                System.out.println("Active Change ID: " + activeChange);
                System.out.println("Active Revision: " + (activeRev != null ? activeRev : "None"));
                System.out.println();

                List<FileMetadata> tracked = db.getAllFiles();
                List<String> modified = new ArrayList<>();
                List<String> deleted = new ArrayList<>();
                List<String> conflicts = new ArrayList<>();

                for (FileMetadata file : tracked) {
                    Path p = cas.getRootDir().resolve(file.getPath());
                    if (!Files.exists(p)) {
                        deleted.add(file.getPath());
                    } else {
                        if (file.getType().equals(ObjectType.CONFLICT.name())) {
                            conflicts.add(file.getPath());
                        } else {
                            long size = Files.size(p);
                            long lastMod = Files.getLastModifiedTime(p).toMillis();
                            if (size != file.getSize() || lastMod != file.getLastModified()) {
                                modified.add(file.getPath());
                            }
                        }
                    }
                }

                if (conflicts.isEmpty() && modified.isEmpty() && deleted.isEmpty()) {
                    System.out.println("Working copy is clean.");
                } else {
                    if (!conflicts.isEmpty()) {
                        System.out.println("Unresolved conflicts:");
                        for (String f : conflicts) {
                            System.out.println("  (conflict)  " + f);
                        }
                        System.out.println("  (use \"draftflow resolve\" to resolve conflicts)");
                        System.out.println();
                    }
                    if (!modified.isEmpty()) {
                        System.out.println("Modified files:");
                        for (String f : modified) {
                            System.out.println("  (modified)  " + f);
                        }
                        System.out.println();
                    }
                    if (!deleted.isEmpty()) {
                        System.out.println("Deleted files:");
                        for (String f : deleted) {
                            System.out.println("  (deleted)   " + f);
                        }
                        System.out.println();
                    }
                }
            }
            return 0;
        }
    }

    @Command(name = "save", description = "Promote current working tree changes to a permanent commit")
    public static class SaveCmd implements Callable<Integer> {
        @Option(names = {"-m", "--message"}, description = "Commit message", required = true)
        private String message;

        @Option(names = {"-p", "--patch"}, description = "Interactive patch mode")
        private boolean patch;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    // Run pre-commit hook
                    if (!com.draftflow.core.HooksManager.runHook("pre-commit", cas.getRootDir())) {
                        System.err.println("Fatal: pre-commit hook failed. Aborting commit.");
                        return 1;
                    }

                    List<FileMetadata> allTracked = db.getAllFiles();
                    for (FileMetadata f : allTracked) {
                        if (f.getType().equals(ObjectType.CONFLICT.name())) {
                            System.err.println("Fatal: Cannot save with unresolved conflicts (" + f.getPath() + "). Run 'draftflow resolve' first.");
                            return 1;
                        }
                    }

                    String oldHash = db.getConfig("activeRevisionHash");

                    WorkspaceManager wm = new WorkspaceManager(cas, db);
                    DraftFlowConfig config = cas.getConfig();
                    com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());

                    Set<Path> allFiles = new HashSet<>();
                    Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (ignoreMatcher.isIgnored(dir)) {
                                  return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!ignoreMatcher.isIgnored(file)) {
                                allFiles.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });

                    for (FileMetadata meta : db.getAllFiles()) {
                        allFiles.add(cas.getRootDir().resolve(meta.getPath()));
                    }

                    if (patch) {
                        String activeHash = db.getConfig("activeRevisionHash");
                        String cleanRev = activeHash;
                        if (activeHash != null) {
                            Revision current = (Revision) cas.readObject(activeHash);
                            if (current.isDraft() && !current.getParentHashes().isEmpty()) {
                                cleanRev = current.getParentHashes().get(0);
                            }
                        }
                        if (cleanRev == null) {
                            System.err.println("Fatal: No permanent commits exist to patch against.");
                            return 1;
                        }

                        try (Scanner scanner = new Scanner(System.in)) {
                            for (FileMetadata f : allTracked) {
                                Path diskPath = cas.getRootDir().resolve(f.getPath());
                                if (!Files.exists(diskPath)) {
                                    continue;
                                }
                                long size = Files.size(diskPath);
                                long lastMod = Files.getLastModifiedTime(diskPath).toMillis();
                                if (size == f.getSize() && lastMod == f.getLastModified()) {
                                    continue;
                                }

                                byte[] baseBytes = getFileBytes(cas, f.getHash(), ObjectType.valueOf(f.getType()));
                                byte[] targetBytes = Files.readAllBytes(diskPath);

                                List<String> baseLines = Arrays.asList(new String(baseBytes, StandardCharsets.UTF_8).split("\\r?\\n", -1));
                                List<String> targetLines = Arrays.asList(new String(targetBytes, StandardCharsets.UTF_8).split("\\r?\\n", -1));

                                List<com.draftflow.diff.StagedHunk> hunks = com.draftflow.diff.StagedHunk.computeHunks(baseLines, targetLines);
                                if (hunks.isEmpty()) {
                                    continue;
                                }

                                System.out.println("Patching file: " + f.getPath());
                                List<com.draftflow.diff.StagedHunk> selectedHunks = new ArrayList<>();
                                boolean quitPatch = false;

                                for (int hunkIdx = 0; hunkIdx < hunks.size(); hunkIdx++) {
                                    com.draftflow.diff.StagedHunk hunk = hunks.get(hunkIdx);
                                    System.out.println("Hunk " + (hunkIdx + 1) + " of " + hunks.size() + ":");
                                    for (Edit e : hunk.edits) {
                                        if (e.type == EditType.DELETE) {
                                            System.out.println("-" + e.line);
                                        } else if (e.type == EditType.INSERT) {
                                            System.out.println("+" + e.line);
                                        }
                                    }

                                    String choice = "";
                                    while (true) {
                                        System.out.print("Stage this hunk? [y,n,q,a,d,?] ");
                                        String testChoice = System.getProperty("draftflow.test.patch.choice");
                                        choice = (testChoice != null) ? testChoice : scanner.nextLine().trim().toLowerCase();
                                        if (choice.equals("y") || choice.equals("n") || choice.equals("q") ||
                                            choice.equals("a") || choice.equals("d") || choice.equals("?")) {
                                            break;
                                        }
                                        System.out.println("Invalid response.");
                                    }

                                    if (choice.equals("y")) {
                                        selectedHunks.add(hunk);
                                    } else if (choice.equals("q")) {
                                        quitPatch = true;
                                        break;
                                    } else if (choice.equals("a")) {
                                        for (int rest = hunkIdx; rest < hunks.size(); rest++) {
                                            selectedHunks.add(hunks.get(rest));
                                        }
                                        break;
                                    } else if (choice.equals("d")) {
                                        break;
                                    } else if (choice.equals("?")) {
                                        System.out.println("y - stage this hunk");
                                        System.out.println("n - do not stage this hunk");
                                        System.out.println("q - quit; do not stage this hunk or any remaining hunks");
                                        System.out.println("a - stage this hunk and all remaining hunks in this file");
                                        System.out.println("d - do not stage this hunk or any remaining hunks in this file");
                                        hunkIdx--; // repeat prompt
                                    }
                                }

                                if (quitPatch) {
                                    break;
                                }

                                List<String> stagedLines = com.draftflow.diff.StagedHunk.applyHunks(baseLines, targetLines, selectedHunks);
                                byte[] stagedBytes = String.join("\n", stagedLines).getBytes(StandardCharsets.UTF_8);
                                String newHash;
                                String typeStr;
                                if (stagedBytes.length > 1024 * 1024) {
                                    List<com.draftflow.cdc.FastCDC.Chunk> chunks = com.draftflow.cdc.FastCDC.chunk(stagedBytes);
                                    List<String> chunkHashes = new ArrayList<>();
                                    List<Integer> chunkSizes = new ArrayList<>();
                                    for (com.draftflow.cdc.FastCDC.Chunk chunk : chunks) {
                                        byte[] cb = chunk.getBytes();
                                        Blob cblob = new Blob(cb);
                                        chunkHashes.add(cas.writeObject(cblob));
                                        chunkSizes.add(cb.length);
                                    }
                                    ChunkTree ct = new ChunkTree(chunkHashes, chunkSizes, stagedBytes.length);
                                    newHash = cas.writeObject(ct);
                                    typeStr = ObjectType.CHUNK_TREE.name();
                                } else {
                                    Blob blob = new Blob(stagedBytes);
                                    newHash = cas.writeObject(blob);
                                    typeStr = ObjectType.BLOB.name();
                                }

                                FileMetadata resolved = new FileMetadata(f.getPath(), stagedBytes.length, System.currentTimeMillis(), newHash, typeStr, f.getMode());
                                db.putFile(resolved);
                            }
                        }
                        db.commit();

                        String treeHash = wm.rebuildTree(db.getAllFiles());
                        String latestRevHash = db.getConfig("activeRevisionHash");
                        Revision draft = (Revision) cas.readObject(latestRevHash);

                        Revision permanent = new Revision(
                                treeHash,
                                Collections.singletonList(cleanRev),
                                draft.getChangeId(),
                                getAuthor(db),
                                System.currentTimeMillis(),
                                message,
                                false
                        );
                        permanent = SignatureHelper.signRevisionIfKeyExists(permanent, cas);
                        String permanentHash = cas.writeObject(permanent);
                        String activeHead = db.getConfig("activeHead");
                        if (activeHead != null) {
                            db.setRef(activeHead, permanentHash);
                        }
                        db.setConfig("activeRevisionHash", permanentHash);
                        db.setChangeRevision(draft.getChangeId(), permanentHash);

                        // Recreate draft/shadow commit on top of permanent commit with remaining workspace changes
                        wm.scanAndCreateShadowCommit(allFiles);
                        db.commit();

                        com.draftflow.core.ReflogManager.logTransition(
                                cas.getRootDir(),
                                oldHash,
                                permanentHash,
                                getAuthor(db),
                                "commit: " + message
                        );
                        com.draftflow.core.HooksManager.runHook("post-commit", cas.getRootDir());
                        System.out.println("Saved change " + draft.getChangeId().substring(0, 8) + " as revision: " + permanentHash.substring(0, 8));
                        return 0;
                    }

                    String latestRevHash = wm.scanAndCreateShadowCommit(allFiles);
                    Revision draft = (Revision) cas.readObject(latestRevHash);

                    Revision permanent = new Revision(
                            draft.getTreeHash(),
                            draft.getParentHashes(),
                            draft.getChangeId(),
                            getAuthor(db),
                            System.currentTimeMillis(),
                            message,
                            false
                    );
                    permanent = SignatureHelper.signRevisionIfKeyExists(permanent, cas);

                    String permanentHash = cas.writeObject(permanent);
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead != null) {
                        db.setRef(activeHead, permanentHash);
                    }
                    db.setConfig("activeRevisionHash", permanentHash);
                    db.setChangeRevision(draft.getChangeId(), permanentHash);

                    Revision newDraft = new Revision(
                            draft.getTreeHash(),
                            Collections.singletonList(permanentHash),
                            draft.getChangeId(),
                            getAuthor(db),
                            System.currentTimeMillis(),
                            "shadow-revision (WIP)",
                            true
                    );
                    newDraft = SignatureHelper.signRevisionIfKeyExists(newDraft, cas);
                    String newDraftHash = cas.writeObject(newDraft);
                    if (activeHead != null) {
                        db.setRef(activeHead, newDraftHash);
                    }
                    db.setConfig("activeRevisionHash", newDraftHash);
                    db.commit();

                    // Log transition in reflog
                    com.draftflow.core.ReflogManager.logTransition(
                            cas.getRootDir(),
                            oldHash,
                            permanentHash,
                            getAuthor(db),
                            "commit: " + message
                    );

                    // Run post-commit hook
                    com.draftflow.core.HooksManager.runHook("post-commit", cas.getRootDir());

                    System.out.println("Saved change " + draft.getChangeId().substring(0, 8) + " as revision: " + permanentHash.substring(0, 8));
                }
                return 0;
            });
        }
    }

    @Command(name = "switch", description = "Switch to a specific revision hash")
    public static class SwitchCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Revision hash to check out")
        private String revisionHash;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    WorkspaceManager wm = new WorkspaceManager(cas, db);

                    String oldHash = db.getConfig("activeRevisionHash");
                    String targetRef = "heads/" + revisionHash;
                    String fullHash = db.getRef(targetRef);
                    if (fullHash == null) {
                        fullHash = cas.resolveHash(revisionHash);
                        if (fullHash != null) {
                            db.removeConfig("activeHead");
                        }
                    } else {
                        db.setConfig("activeHead", targetRef);
                    }

                    if (fullHash == null) {
                        System.err.println("Error: Revision not found: " + revisionHash);
                        return 1;
                    }

                    db.setConfig("activeRevisionHash", fullHash);
                    db.commit();

                    wm.restoreWorkingCopy(fullHash);

                    // Re-align shadow commit
                    DraftFlowConfig config = cas.getConfig();
                    com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());
                    Set<Path> allFiles = new HashSet<>();
                    Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (ignoreMatcher.isIgnored(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!ignoreMatcher.isIgnored(file)) {
                                allFiles.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    for (FileMetadata meta : db.getAllFiles()) {
                        allFiles.add(cas.getRootDir().resolve(meta.getPath()));
                    }
                    String shadowHash = wm.scanAndCreateShadowCommit(allFiles);
                    db.setConfig("activeRevisionHash", shadowHash);
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead != null) {
                        db.setRef(activeHead, shadowHash);
                    }
                    db.commit();

                    com.draftflow.core.ReflogManager.logTransition(
                            cas.getRootDir(),
                            oldHash,
                            fullHash,
                            getAuthor(db),
                            "checkout: moving to " + revisionHash
                    );

                    System.out.println("Switched to revision: " + fullHash.substring(0, 8));
                    com.draftflow.core.HooksManager.runHook("post-checkout", cas.getRootDir(), oldHash != null ? oldHash : "null", fullHash);
                }
                return 0;
            });
        }
    }

    @Command(name = "merge", description = "Merge another revision into the current branch")
    public static class MergeCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Revision hash or branch ref to merge")
        private String target;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    String oursHash = db.getConfig("activeRevisionHash");
                    if (oursHash == null) {
                        System.err.println("Error: No active revision on current branch.");
                        return 1;
                    }

                    String theirsHash = cas.resolveHash(target);
                    if (theirsHash == null) {
                        theirsHash = db.getRef("heads/" + target);
                        if (theirsHash == null) {
                            theirsHash = db.getRef(target);
                        }
                    }

                    if (theirsHash == null) {
                        System.err.println("Error: Could not resolve target: " + target);
                        return 1;
                    }

                    System.out.println("Merging " + theirsHash.substring(0, 8) + " into current revision " + oursHash.substring(0, 8) + "...");
                    
                    MergeEngine.MergeResult result = MergeEngine.mergeRevisions(oursHash, theirsHash, cas);

                    List<String> parents = Arrays.asList(oursHash, theirsHash);
                    String activeChangeId = db.getConfig("activeChangeId");

                    Revision mergedDraft = new Revision(
                            result.treeHash,
                            parents,
                            activeChangeId,
                            getAuthor(db),
                            System.currentTimeMillis(),
                            "Merge commit draft",
                            true
                    );
                    String mergedDraftHash = cas.writeObject(mergedDraft);

                    WorkspaceManager wm = new WorkspaceManager(cas, db);
                    wm.restoreWorkingCopy(mergedDraftHash);

                    if (result.clean) {
                        System.out.println("Merge successful! Clean merged state checked out.");
                    } else {
                        System.out.println("Merge finished with CONFLICTS!");
                        System.out.println("Conflicted files:");
                        for (String f : result.conflicts) {
                            System.out.println("  (conflict)  " + f);
                        }
                        System.out.println("Run 'draftflow resolve' to resolve conflicts interactively.");
                    }
                }
                return 0;
            });
        }
    }

    @Command(name = "resolve", description = "Resolve conflicts in the working copy interactively")
    public static class ResolveCmd implements Callable<Integer> {
        @SuppressWarnings("resource")
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    List<FileMetadata> allFiles = db.getAllFiles();
                    List<FileMetadata> conflicts = new ArrayList<>();
                    for (FileMetadata f : allFiles) {
                        if (f.getType().equals(ObjectType.CONFLICT.name())) {
                            conflicts.add(f);
                        }
                    }

                    if (conflicts.isEmpty()) {
                        System.out.println("No unresolved conflicts.");
                        return 0;
                    }

                    Scanner scanner = new Scanner(System.in);
                    WorkspaceManager wm = new WorkspaceManager(cas, db);

                    System.out.println("Found " + conflicts.size() + " unresolved conflict(s).");
                    for (FileMetadata f : conflicts) {
                        System.out.println("\n----------------------------------------");
                        System.out.println("Conflicting file: " + f.getPath());

                        ConflictNode node = (ConflictNode) cas.readObject(f.getHash());

                        System.out.println("Select resolution action:");
                        System.out.println("  1. Keep OURS (Left) version");
                        System.out.println("  2. Keep THEIRS (Right) version");
                        System.out.println("  3. Resolve manually (Verify file no longer has markers on disk)");
                        System.out.println("  4. Launch external merge tool");
                        System.out.print("Enter choice [1-4]: ");

                        String choice = scanner.nextLine().trim();
                        if (choice.equals("4")) {
                            Path ancestorTmp = Files.createTempFile("draftflow_base_", ".tmp");
                            Path leftTmp = Files.createTempFile("draftflow_ours_", ".tmp");
                            Path rightTmp = Files.createTempFile("draftflow_theirs_", ".tmp");
                            Path mergedTmp = Files.createTempFile("draftflow_merged_", ".tmp");

                            byte[] ancestorBytes = (node.getAncestorHash() != null) ? getFileBytes(cas, node.getAncestorHash(), ObjectType.valueOf(db.getFile(f.getPath()) != null ? db.getFile(f.getPath()).getType() : ObjectType.BLOB.name())) : new byte[0];
                            byte[] leftBytes = (node.getLeftHash() != null) ? getFileBytes(cas, node.getLeftHash(), ObjectType.BLOB) : new byte[0];
                            byte[] rightBytes = (node.getRightHash() != null) ? getFileBytes(cas, node.getRightHash(), ObjectType.BLOB) : new byte[0];

                            Files.write(ancestorTmp, ancestorBytes);
                            Files.write(leftTmp, leftBytes);
                            Files.write(rightTmp, rightBytes);
                            Files.write(mergedTmp, leftBytes);

                            String mergeCmd = System.getenv("DRAFTFLOW_MERGETOOL");
                            if (mergeCmd == null) {
                                mergeCmd = System.getProperty("draftflow.test.mergetool.cmd");
                            }
                            if (mergeCmd == null) {
                                mergeCmd = "code --merge %left %right %base %merged";
                            }

                            String cmdStr = mergeCmd
                                .replace("%left", leftTmp.toAbsolutePath().toString())
                                .replace("%ours", leftTmp.toAbsolutePath().toString())
                                .replace("%right", rightTmp.toAbsolutePath().toString())
                                .replace("%theirs", rightTmp.toAbsolutePath().toString())
                                .replace("%base", ancestorTmp.toAbsolutePath().toString())
                                .replace("%ancestor", ancestorTmp.toAbsolutePath().toString())
                                .replace("%merged", mergedTmp.toAbsolutePath().toString());

                            String[] cmdArgs;
                            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                                cmdArgs = new String[] { "cmd.exe", "/c", cmdStr };
                            } else {
                                cmdArgs = new String[] { "sh", "-c", cmdStr };
                            }

                            System.out.println("Launching external merge tool: " + mergeCmd);
                            Process p = new ProcessBuilder(cmdArgs).start();
                            int exitCode = p.waitFor();
                            if (exitCode != 0) {
                                System.err.println("Merge tool exited with non-zero code: " + exitCode);
                            } else {
                                byte[] resolvedBytes = Files.readAllBytes(mergedTmp);
                                Path diskPath = cas.getRootDir().resolve(f.getPath());
                                Files.write(diskPath, resolvedBytes);

                                String newHash;
                                String typeStr;
                                if (resolvedBytes.length > 1024 * 1024) {
                                    List<com.draftflow.cdc.FastCDC.Chunk> chunks = com.draftflow.cdc.FastCDC.chunk(resolvedBytes);
                                    List<String> chunkHashes = new ArrayList<>();
                                    List<Integer> chunkSizes = new ArrayList<>();
                                    for (com.draftflow.cdc.FastCDC.Chunk chunk : chunks) {
                                        byte[] cb = chunk.getBytes();
                                        Blob cblob = new Blob(cb);
                                        chunkHashes.add(cas.writeObject(cblob));
                                        chunkSizes.add(cb.length);
                                    }
                                    ChunkTree ct = new ChunkTree(chunkHashes, chunkSizes, resolvedBytes.length);
                                    newHash = cas.writeObject(ct);
                                    typeStr = ObjectType.CHUNK_TREE.name();
                                } else {
                                    Blob blob = new Blob(resolvedBytes);
                                    newHash = cas.writeObject(blob);
                                    typeStr = ObjectType.BLOB.name();
                                }

                                FileMetadata resolved = new FileMetadata(f.getPath(), resolvedBytes.length, System.currentTimeMillis(), newHash, typeStr, f.getMode());
                                db.putFile(resolved);
                                System.out.println("Resolved " + f.getPath() + " using external merge tool.");
                            }

                            Files.deleteIfExists(leftTmp);
                            Files.deleteIfExists(rightTmp);
                            Files.deleteIfExists(ancestorTmp);
                            Files.deleteIfExists(mergedTmp);
                        } else if (choice.equals("1")) {
                            if (node.getLeftHash() == null) {
                                db.removeFile(f.getPath());
                                Files.deleteIfExists(cas.getRootDir().resolve(f.getPath()));
                                System.out.println("Resolved " + f.getPath() + " by deleting it (Ours).");
                            } else {
                                Blob blob = (Blob) cas.readObject(node.getLeftHash());
                                Path path = cas.getRootDir().resolve(f.getPath());
                                Files.write(path, blob.getContent());
                                long size = Files.size(path);
                                long lastMod = Files.getLastModifiedTime(path).toMillis();
                                FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, node.getLeftHash(), ObjectType.BLOB.name(), f.getMode());
                                db.putFile(resolved);
                                System.out.println("Resolved " + f.getPath() + " using OURS version.");
                            }
                        } else if (choice.equals("2")) {
                            if (node.getRightHash() == null) {
                                db.removeFile(f.getPath());
                                Files.deleteIfExists(cas.getRootDir().resolve(f.getPath()));
                                System.out.println("Resolved " + f.getPath() + " by deleting it (Theirs).");
                            } else {
                                Blob blob = (Blob) cas.readObject(node.getRightHash());
                                Path path = cas.getRootDir().resolve(f.getPath());
                                Files.write(path, blob.getContent());
                                long size = Files.size(path);
                                long lastMod = Files.getLastModifiedTime(path).toMillis();
                                FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, node.getRightHash(), ObjectType.BLOB.name(), f.getMode());
                                db.putFile(resolved);
                                System.out.println("Resolved " + f.getPath() + " using THEIRS version.");
                            }
                        } else if (choice.equals("3")) {
                            Path path = cas.getRootDir().resolve(f.getPath());
                            if (!Files.exists(path)) {
                                System.err.println("File does not exist on disk. If deleted, select option 1 or 2.");
                                continue;
                            }
                            String content = Files.readString(path);
                            if (content.contains("<<<<<<< OURS") || content.contains("=======") || content.contains(">>>>>>> THEIRS")) {
                                System.err.println("Warning: File still contains conflict markers on disk! Clean the file first, then try resolving manually again.");
                            } else {
                                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                                String newBlobHash;
                                String typeStr;

                                if (data.length > 1024 * 1024) {
                                    List<FastCDC.Chunk> chunks = FastCDC.chunk(data);
                                    List<String> chunkHashes = new ArrayList<>();
                                    List<Integer> chunkSizes = new ArrayList<>();
                                    for (FastCDC.Chunk chunk : chunks) {
                                        byte[] cb = chunk.getBytes();
                                        Blob cblob = new Blob(cb);
                                        chunkHashes.add(cas.writeObject(cblob));
                                        chunkSizes.add(cb.length);
                                    }
                                    ChunkTree ct = new ChunkTree(chunkHashes, chunkSizes, data.length);
                                    newBlobHash = cas.writeObject(ct);
                                    typeStr = ObjectType.CHUNK_TREE.name();
                                } else {
                                    Blob blob = new Blob(data);
                                    newBlobHash = cas.writeObject(blob);
                                    typeStr = ObjectType.BLOB.name();
                                }

                                long size = Files.size(path);
                                long lastMod = Files.getLastModifiedTime(path).toMillis();
                                FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, newBlobHash, typeStr, f.getMode());
                                db.putFile(resolved);
                                System.out.println("Staged manually resolved file: " + f.getPath());
                            }
                        } else {
                            System.out.println("Invalid choice. Skipping.");
                        }
                    }

                    Set<Path> scanned = new HashSet<>();
                    for (FileMetadata f : db.getAllFiles()) {
                        scanned.add(cas.getRootDir().resolve(f.getPath()));
                    }
                    wm.scanAndCreateShadowCommit(scanned);
                    db.commit();
                    System.out.println("\nResolve run completed.");
                }
                return 0;
            });
        }
    }

    @Command(name = "upload", description = "Upload branch commits to a remote repository")
    public static class UploadCmd implements Callable<Integer> {
        @Option(names = {"--remote"}, description = "Remote repository URL (e.g. file:///... or http://...)", required = true)
        private String remoteUrl;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead == null) {
                        System.err.println("Error: No branch active to upload.");
                        return 1;
                    }
                    String localHead = db.getConfig("activeRevisionHash");
                    if (localHead == null) {
                        System.err.println("Error: Branch is empty.");
                        return 1;
                    }

                    RemoteClient client = new RemoteClient(remoteUrl);
                    String remoteHead = client.getRef(activeHead);

                    if (!com.draftflow.core.HooksManager.runHook("pre-push", cas.getRootDir(), remoteUrl)) {
                        System.err.println("Fatal: pre-push hook failed. Aborting push.");
                        return 1;
                    }

                    List<String> missingHashes = new ArrayList<>();
                    Queue<String> queue = new LinkedList<>();
                    queue.add(localHead);
                    Set<String> visited = new HashSet<>();

                    while (!queue.isEmpty()) {
                        String curr = queue.poll();
                        if (curr == null || visited.contains(curr) || curr.equals(remoteHead)) {
                            continue;
                        }
                        visited.add(curr);
                        missingHashes.add(curr);

                        collectReferencedObjects(curr, cas, missingHashes);

                        Revision rev = (Revision) cas.readObject(curr);
                        queue.addAll(rev.getParentHashes());
                    }

                    if (missingHashes.isEmpty()) {
                        System.out.println("Everything up-to-date.");
                        return 0;
                    }

                    System.out.println("Packing " + missingHashes.size() + " objects...");
                    String packId = "pack-" + UUID.randomUUID().toString().substring(0, 8);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Packer.createPack(missingHashes, cas, out);
                    byte[] packData = out.toByteArray();

                    System.out.println("Uploading pack " + packId + " to remote...");
                    client.uploadPack(packId, packData);

                    Map<String, String> remoteIndex = client.downloadIndex();
                    for (String h : missingHashes) {
                        remoteIndex.put(h, packId);
                    }
                    client.uploadIndex(remoteIndex);

                    // Push LFS objects if any
                    Path remoteLfsDir = null;
                    if (remoteUrl.startsWith("file://")) {
                        try {
                            remoteLfsDir = Paths.get(new URI(remoteUrl)).resolve(".draftflow");
                        } catch (Exception e) {}
                    } else if (remoteUrl.startsWith("file:")) {
                        try {
                            remoteLfsDir = Paths.get(new URI(remoteUrl)).resolve(".draftflow");
                        } catch (Exception e) {}
                    } else {
                        try {
                            remoteLfsDir = Paths.get(remoteUrl).resolve(".draftflow");
                        } catch (Exception e) {}
                    }
                    if (remoteLfsDir != null) {
                        for (String h : missingHashes) {
                            try {
                                DraftFlowObject obj = cas.readObject(h);
                                if (obj.getType() == ObjectType.BLOB) {
                                    Blob b = (Blob) obj;
                                    String contentStr = new String(b.getContent(), StandardCharsets.UTF_8);
                                    com.draftflow.core.LFSManager.LfsPointer ptr = com.draftflow.core.LFSManager.parsePointer(contentStr);
                                    if (ptr != null) {
                                        com.draftflow.core.LFSManager.pushLfsObject(cas.getRootDir(), ptr.oid, remoteLfsDir);
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }

                    System.out.println("Updating remote ref " + activeHead + "...");
                    try {
                        OCC.tryUpdateRef(client, activeHead, remoteHead, localHead);
                        System.out.println("Upload successful!");
                    } catch (OCC.ConcurrencyException e) {
                        System.err.println("Upload failed: " + e.getMessage());
                        return 1;
                    }
                }
                return 0;
            });
        }

        private void collectReferencedObjects(String revHash, CAS cas, List<String> list) {
            try {
                Revision rev = (Revision) cas.readObject(revHash);
                String treeHash = rev.getTreeHash();
                if (treeHash != null && !list.contains(treeHash)) {
                    list.add(treeHash);
                    collectTreeObjects(treeHash, cas, list);
                }
            } catch (Exception ignored) {}
        }

        private void collectTreeObjects(String treeHash, CAS cas, List<String> list) {
            try {
                Tree tree = (Tree) cas.readObject(treeHash);
                for (TreeEntry entry : tree.getEntries()) {
                    String hash = entry.getHash();
                    if (hash != null && !list.contains(hash)) {
                        list.add(hash);
                        if (entry.getType() == ObjectType.TREE) {
                            collectTreeObjects(hash, cas, list);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Command(name = "download", description = "Download branch commits from a remote repository")
    public static class DownloadCmd implements Callable<Integer> {
        @Option(names = {"--remote"}, description = "Remote repository URL", required = true)
        private String remoteUrl;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead == null) {
                        System.err.println("Error: No active branch to download into.");
                        return 1;
                    }

                    RemoteClient client = new RemoteClient(remoteUrl);
                    String remoteHead = client.getRef(activeHead);
                    if (remoteHead == null) {
                        System.out.println("Branch " + activeHead + " does not exist on remote.");
                        return 0;
                    }

                    String localHead = db.getConfig("activeRevisionHash");
                    if (remoteHead.equals(localHead)) {
                        System.out.println("Already up-to-date.");
                        return 0;
                    }

                    Map<String, String> remoteIndex = client.downloadIndex();
                    Set<String> missing = new HashSet<>();
                    Queue<String> queue = new LinkedList<>();
                    queue.add(remoteHead);

                    while (!queue.isEmpty()) {
                        String curr = queue.poll();
                        if (curr == null || missing.contains(curr)) {
                            continue;
                        }
                        Path objPath = cas.getDraftFlowDir().resolve("objects").resolve(curr.substring(0, 2)).resolve(curr.substring(2));
                        if (Files.exists(objPath)) {
                            continue;
                        }

                        missing.add(curr);
                        String packId = remoteIndex.get(curr);
                        if (packId != null) {
                            System.out.println("Downloading missing pack: " + packId);
                            byte[] packData = client.downloadPack(packId);
                            ByteArrayInputStream in = new ByteArrayInputStream(packData);
                            Packer.unpack(in, cas);
                        }

                        try {
                            Revision rev = (Revision) cas.readObject(curr);
                            queue.addAll(rev.getParentHashes());
                        } catch (Exception ignored) {}
                    }

                    // Pull LFS objects if any
                    Path remoteLfsDir = null;
                    if (remoteUrl.startsWith("file://")) {
                        try {
                            remoteLfsDir = Paths.get(new URI(remoteUrl)).resolve(".draftflow");
                        } catch (Exception e) {}
                    } else if (remoteUrl.startsWith("file:")) {
                        try {
                            remoteLfsDir = Paths.get(new URI(remoteUrl)).resolve(".draftflow");
                        } catch (Exception e) {}
                    } else {
                        try {
                            remoteLfsDir = Paths.get(remoteUrl).resolve(".draftflow");
                        } catch (Exception e) {}
                    }
                    if (remoteLfsDir != null) {
                        for (String h : missing) {
                            try {
                                DraftFlowObject obj = cas.readObject(h);
                                if (obj.getType() == ObjectType.BLOB) {
                                    Blob b = (Blob) obj;
                                    String contentStr = new String(b.getContent(), StandardCharsets.UTF_8);
                                    com.draftflow.core.LFSManager.LfsPointer ptr = com.draftflow.core.LFSManager.parsePointer(contentStr);
                                    if (ptr != null) {
                                        com.draftflow.core.LFSManager.pullLfsObject(cas.getRootDir(), ptr.oid, remoteLfsDir);
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }

                    db.setRef(activeHead, remoteHead);
                    db.setConfig("activeRevisionHash", remoteHead);
                    db.commit();

                    WorkspaceManager wm = new WorkspaceManager(cas, db);
                    wm.restoreWorkingCopy(remoteHead);

                    System.out.println("Download successful! Updated local head to: " + remoteHead.substring(0, 8));
                }
                return 0;
            });
        }
    }

    @Command(name = "history", description = "Show DAG commit history")
    public static class HistoryCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                String activeRev = db.getConfig("activeRevisionHash");
                if (activeRev == null) {
                    System.out.println("No commits in this branch.");
                    return 0;
                }

                Queue<String> queue = new LinkedList<>();
                queue.add(activeRev);
                Set<String> visited = new HashSet<>();

                while (!queue.isEmpty()) {
                    String curr = queue.poll();
                    if (curr == null || visited.contains(curr)) {
                        continue;
                    }
                    visited.add(curr);

                    Revision rev = (Revision) cas.readObject(curr);
                    String sigStatus = "[UNSIGNED]";
                    if (rev.getSignature() != null && rev.getPublicKey() != null) {
                        boolean verified = SignatureHelper.verify(rev.getSigningData(), rev.getSignature(), rev.getPublicKey());
                        sigStatus = verified ? "[SIGNED & VERIFIED]" : "[SIGNATURE INVALID]";
                    }
                    System.out.println("*  Revision: " + curr.substring(0, 8) + (rev.isDraft() ? " (DRAFT)" : "") + " " + sigStatus);
                    System.out.println("|  Change ID: " + rev.getChangeId().substring(0, 8));
                    System.out.println("|  Author: " + rev.getAuthor());
                    System.out.println("|  Date: " + new java.util.Date(rev.getTimestamp()));
                    System.out.println("|  Message: " + rev.getMessage());
                    System.out.println("|");

                    queue.addAll(rev.getParentHashes());
                }
            }
            return 0;
        }
    }

    @Command(name = "branch", description = "List, create, or delete branches")
    public static class BranchCmd implements Callable<Integer> {
        @Option(names = {"-c", "--create"}, description = "Create a branch with the specified name")
        private String newBranch;

        @Option(names = {"-d", "--delete"}, description = "Delete the branch with the specified name")
        private String deleteBranch;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    if (newBranch != null) {
                        String head = db.getConfig("activeRevisionHash");
                        if (head == null) {
                            System.err.println("Fatal: Cannot create a branch because there are no commits in the repository yet. Save a commit first.");
                            return 1;
                        }
                        db.setRef("heads/" + newBranch, head);
                        db.commit();
                        System.out.println("Created branch: " + newBranch);
                    } else if (deleteBranch != null) {
                        db.removeRef("heads/" + deleteBranch);
                        db.commit();
                        System.out.println("Deleted branch: " + deleteBranch);
                    } else {
                        String active = db.getConfig("activeHead");
                        for (String name : db.getRefNames()) {
                            if (name.startsWith("heads/")) {
                                String branch = name.replace("heads/", "");
                                if (name.equals(active)) {
                                    System.out.println("* " + branch);
                                } else {
                                    System.out.println("  " + branch);
                                }
                            }
                        }
                    }
                }
                return 0;
            });
        }
    }

    @Command(name = "undo", description = "Undo the last commit / revert HEAD pointer to parent")
    public static class UndoCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev == null) {
                        System.err.println("Error: Nothing to undo.");
                        return 1;
                    }

                    String curr = activeRev;
                    Revision discardRev = null;
                    while (curr != null) {
                        Revision r = (Revision) cas.readObject(curr);
                        if (!r.isDraft()) {
                            discardRev = r;
                            break;
                        }
                        if (r.getParentHashes().isEmpty()) {
                            break;
                        }
                        curr = r.getParentHashes().get(0);
                    }

                    if (discardRev == null) {
                        System.err.println("Error: No permanent commit to undo.");
                        return 1;
                    }

                    if (discardRev.getParentHashes().isEmpty()) {
                        System.err.println("Error: Root revision reached, cannot undo.");
                        return 1;
                    }

                    String parent = discardRev.getParentHashes().get(0);
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead != null) {
                        db.setRef(activeHead, parent);
                    }
                    db.setConfig("activeRevisionHash", parent);
                    db.commit();

                    com.draftflow.core.ReflogManager.logTransition(
                            cas.getRootDir(),
                            activeRev,
                            parent,
                            getAuthor(db),
                            "undo: revert to parent " + parent.substring(0, 8)
                    );

                    WorkspaceManager wm = new WorkspaceManager(cas, db);
                    wm.restoreWorkingCopy(parent);

                    System.out.println("Reverted branch ref back to: " + parent.substring(0, 8));
                }
                return 0;
            });
        }
    }

    public static Integer runLockedCommand(CAS cas, Callable<Integer> action) throws Exception {
        cas.acquireLock();
        try {
            return action.call();
        } finally {
            cas.releaseLock();
        }
    }

    @Command(name = "verify", description = "Verify CAS objects and index integrity, prune orphaned entries")
    public static class VerifyCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    System.out.println("Running DraftFlow verify integrity verification...");
                    
                    Path objectsDir = cas.getDraftFlowDir().resolve("objects");
                    if (!Files.exists(objectsDir)) {
                        System.out.println("No CAS objects to verify.");
                        return 0;
                    }
                    
                    List<String> corruptedObjects = new ArrayList<>();
                    List<String> validObjects = new ArrayList<>();
                    
                    try (DirectoryStream<Path> prefixes = Files.newDirectoryStream(objectsDir)) {
                        for (Path prefixDir : prefixes) {
                            if (!Files.isDirectory(prefixDir)) continue;
                            try (DirectoryStream<Path> objs = Files.newDirectoryStream(prefixDir)) {
                                for (Path objPath : objs) {
                                    String hash = prefixDir.getFileName().toString() + objPath.getFileName().toString();
                                    try {
                                        cas.readObject(hash);
                                        validObjects.add(hash);
                                    } catch (Exception e) {
                                        corruptedObjects.add(hash);
                                        Files.deleteIfExists(objPath);
                                    }
                                }
                            }
                        }
                    }
                    
                    System.out.println("Checked " + (validObjects.size() + corruptedObjects.size()) + " CAS objects.");
                    if (!corruptedObjects.isEmpty()) {
                        System.err.println("Found and cleared " + corruptedObjects.size() + " corrupted objects: " + corruptedObjects);
                    } else {
                        System.out.println("All stored CAS objects are healthy.");
                    }
                    
                    List<FileMetadata> trackedFiles = db.getAllFiles();
                    int missingRefs = 0;
                    for (FileMetadata f : trackedFiles) {
                        String hash = f.getHash();
                        Path objPath = objectsDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
                        if (!Files.exists(objPath)) {
                            System.err.println("Warning: Tracked file " + f.getPath() + " references missing CAS object " + hash);
                            missingRefs++;
                        }
                    }
                    if (missingRefs > 0) {
                        System.err.println("Integrity Check Failed: " + missingRefs + " tracked files are missing their content in the CAS store.");
                        return 1;
                    } else {
                        System.out.println("Index matches CAS successfully.");
                    }
                }
                return 0;
            });
        }
    }

    @Command(name = "dashboard", description = "Launch visualization dashboard server")
    public static class DashboardCmd implements Callable<Integer> {
        @Option(names = {"-p", "--port"}, description = "Port to listen on", defaultValue = "8080")
        private int port;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
            
            MetadataStore db = new MetadataStore(dbPath);
            db.open();

            UiServer uiServer = new UiServer(cas, db, port);
            uiServer.start();

            try {
                System.out.println("Dashboard: http://localhost:" + uiServer.getPort());
                System.out.println("Press Ctrl+C to terminate UI server...");
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                System.out.println("UI server terminated.");
            } finally {
                uiServer.stop();
                db.close();
            }
            return 0;
        }
    }

    @Command(name = "keys", description = "Generate ECDSA keypair for cryptographic commit signing")
    public static class KeysCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            Path privPath = cas.getDraftFlowDir().resolve("id_ecdsa");
            Path pubPath = cas.getDraftFlowDir().resolve("id_ecdsa.pub");

            if (Files.exists(privPath) || Files.exists(pubPath)) {
                System.out.println("Keypair already exists under .draftflow/id_ecdsa");
                return 0;
            }

            System.out.println("Generating ECDSA keypair...");
            SignatureHelper.KeyPairStrings pair = SignatureHelper.generateKeyPair();

            Files.writeString(privPath, pair.privateKeyBase64, StandardCharsets.UTF_8);
            Files.writeString(pubPath, pair.publicKeyBase64, StandardCharsets.UTF_8);

            System.out.println("Keypair generated successfully!");
            System.out.println("Private key: " + privPath);
            System.out.println("Public key:  " + pubPath);
            return 0;
        }
    }

    @Command(name = "prune", description = "Run garbage collection to delete unreachable objects in CAS")
    public static class PruneCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    Set<String> reachable = new HashSet<>();

                    List<String> refs = db.getRefNames();
                    Queue<String> queue = new LinkedList<>();
                    for (String ref : refs) {
                        String hash = db.getRef(ref);
                        if (hash != null) {
                            queue.add(hash);
                        }
                    }

                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev != null) {
                        queue.add(activeRev);
                    }

                    while (!queue.isEmpty()) {
                        String curr = queue.poll();
                        if (curr == null || reachable.contains(curr)) {
                            continue;
                        }
                        reachable.add(curr);

                        try {
                            Revision rev = (Revision) cas.readObject(curr);
                            queue.addAll(rev.getParentHashes());
                            if (rev.getTreeHash() != null) {
                                collectTreeObjects(rev.getTreeHash(), cas, reachable);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    System.out.printf("DEBUG PRUNE: refs=%s, activeRev=%s, reachable=%s\n", refs, activeRev, reachable);
                    Path objectsDir = cas.getDraftFlowDir().resolve("objects");
                    if (!Files.exists(objectsDir)) {
                        System.out.println("No objects to prune.");
                        return 0;
                    }

                    int prunedCount = 0;
                    long prunedBytes = 0;

                    try (var subdirs = Files.newDirectoryStream(objectsDir)) {
                        for (Path subdir : subdirs) {
                            if (Files.isDirectory(subdir) && subdir.getFileName().toString().length() == 2) {
                                try (var files = Files.newDirectoryStream(subdir)) {
                                    for (Path file : files) {
                                        String hash = subdir.getFileName().toString() + file.getFileName().toString();
                                        if (hash.contains(".tmp")) {
                                            Files.deleteIfExists(file);
                                            continue;
                                        }
                                        if (!reachable.contains(hash)) {
                                            long size = Files.size(file);
                                            Files.delete(file);
                                            prunedCount++;
                                            prunedBytes += size;
                                        }
                                    }
                                }
                                try (var files = Files.newDirectoryStream(subdir)) {
                                    if (!files.iterator().hasNext()) {
                                        Files.delete(subdir);
                                    }
                                }
                            }
                        }
                    }

                    System.out.println("Pruned " + prunedCount + " unreachable object(s) (" + (prunedBytes / 1024) + " KB reclaimed).");
                }
                return 0;
            });
        }

        private void collectTreeObjects(String treeHash, CAS cas, Set<String> reachable) {
            if (treeHash == null || reachable.contains(treeHash)) {
                return;
            }
            reachable.add(treeHash);

            try {
                Tree tree = (Tree) cas.readObject(treeHash);
                for (TreeEntry entry : tree.getEntries()) {
                    String hash = entry.getHash();
                    if (hash != null) {
                        if (entry.getType() == ObjectType.TREE) {
                            collectTreeObjects(hash, cas, reachable);
                        } else if (entry.getType() == ObjectType.CHUNK_TREE) {
                            if (!reachable.contains(hash)) {
                                reachable.add(hash);
                                try {
                                    ChunkTree ct = (ChunkTree) cas.readObject(hash);
                                    reachable.addAll(ct.getChunkHashes());
                                } catch (Exception ignored) {}
                            }
                        } else {
                            reachable.add(hash);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Command(name = "stash", description = "Stash away working copy modifications temporarily")
    public static class StashCmd implements Callable<Integer> {
        @Option(names = {"push"}, description = "Push current modifications to a new stash")
        private boolean push;

        @Option(names = {"list"}, description = "List all stashes")
        private boolean list;

        @Option(names = {"pop"}, description = "Pop the latest stash back to working copy")
        private boolean pop;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            if (!push && !list && !pop) {
                push = true;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    WorkspaceManager wm = new WorkspaceManager(cas, db);

                    if (push) {
                        DraftFlowConfig config = cas.getConfig();
                        com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());
                        Set<Path> allFiles = new HashSet<>();
                        Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                if (ignoreMatcher.isIgnored(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                if (!ignoreMatcher.isIgnored(file)) {
                                    allFiles.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });

                        for (FileMetadata meta : db.getAllFiles()) {
                            allFiles.add(cas.getRootDir().resolve(meta.getPath()));
                        }

                        String cleanRev = db.getConfig("activeRevisionHash");
                        if (cleanRev == null) {
                            System.err.println("Error: Cannot stash. No commits exist in this repository.");
                            return 1;
                        }

                        String shadowHash = wm.scanAndCreateShadowCommit(allFiles);
                        Revision shadow = (Revision) cas.readObject(shadowHash);

                        Revision active = (Revision) cas.readObject(cleanRev);
                        if (shadow.getTreeHash().equals(active.getTreeHash())) {
                            System.out.println("No local modifications to stash.");
                            return 0;
                        }

                        String activeHead = db.getConfig("activeHead");
                        String branchName = activeHead != null ? activeHead.replace("heads/", "") : "detached";
                        String message = "Stash: WIP on " + branchName + " @ " + new java.util.Date();
                        Revision stashRev = new Revision(
                                shadow.getTreeHash(),
                                Collections.singletonList(cleanRev),
                                shadow.getChangeId(),
                                getAuthor(db),
                                System.currentTimeMillis(),
                                message,
                                false
                        );
                        String stashHash = cas.writeObject(stashRev);

                        String stashRef = "stashes/stash-" + UUID.randomUUID().toString().substring(0, 8);
                        db.setRef(stashRef, stashHash);
                        db.commit();

                        wm.restoreWorkingCopy(cleanRev);

                        System.out.println("Saved working directory modifications to stash: " + stashRef);
                    } else if (list) {
                        List<String> stashes = new ArrayList<>();
                        for (String ref : db.getRefNames()) {
                            if (ref.startsWith("stashes/")) {
                                stashes.add(ref);
                            }
                        }
                        if (stashes.isEmpty()) {
                            System.out.println("No stashes found.");
                            return 0;
                        }

                        stashes.sort((a, b) -> {
                            try {
                                Revision ra = (Revision) cas.readObject(db.getRef(a));
                                Revision rb = (Revision) cas.readObject(db.getRef(b));
                                return Long.compare(rb.getTimestamp(), ra.getTimestamp());
                            } catch (Exception e) {
                                return 0;
                            }
                        });

                        for (String s : stashes) {
                            Revision rev = (Revision) cas.readObject(db.getRef(s));
                            System.out.println(s + " -> " + rev.getMessage());
                        }
                    } else if (pop) {
                        List<String> stashes = new ArrayList<>();
                        for (String ref : db.getRefNames()) {
                            if (ref.startsWith("stashes/")) {
                                stashes.add(ref);
                            }
                        }
                        if (stashes.isEmpty()) {
                            System.err.println("Error: No stashes to pop.");
                            return 1;
                        }

                        stashes.sort((a, b) -> {
                            try {
                                Revision ra = (Revision) cas.readObject(db.getRef(a));
                                Revision rb = (Revision) cas.readObject(db.getRef(b));
                                return Long.compare(rb.getTimestamp(), ra.getTimestamp());
                            } catch (Exception e) {
                                return 0;
                            }
                        });

                        String targetStashRef = stashes.get(0);
                        String stashHash = db.getRef(targetStashRef);

                        wm.restoreWorkingCopy(stashHash);

                        db.removeRef(targetStashRef);
                        db.commit();

                        System.out.println("Popped stash: " + targetStashRef + " successfully!");
                    }
                }
                return 0;
            });
        }
    }

    @Command(name = "diff", description = "Show line-by-line differences of files in the workspace")
    public static class DiffCmd implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Optional file path to diff")
        private String filePath;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();

                List<FileMetadata> targets = new ArrayList<>();
                if (filePath != null) {
                    Path normalized = cas.getRootDir().resolve(filePath).toAbsolutePath().normalize();
                    String rel = cas.getRootDir().relativize(normalized).toString().replace("\\", "/");
                    FileMetadata meta = db.getFile(rel);
                    if (meta == null) {
                        System.err.println("Error: File not tracked in index: " + filePath);
                        return 1;
                    }
                    targets.add(meta);
                } else {
                    for (FileMetadata file : db.getAllFiles()) {
                        Path p = cas.getRootDir().resolve(file.getPath());
                        if (Files.exists(p)) {
                            long size = Files.size(p);
                            long lastMod = Files.getLastModifiedTime(p).toMillis();
                            if (size != file.getSize() || lastMod != file.getLastModified()) {
                                targets.add(file);
                            }
                        }
                    }
                }

                if (targets.isEmpty()) {
                    System.out.println("No modifications detected.");
                    return 0;
                }

                for (FileMetadata f : targets) {
                    System.out.println("\n--- Diff for file: " + f.getPath() + " ---");
                    
                    List<String> originalLines = new ArrayList<>();
                    try {
                        byte[] origData;
                        if (f.getType().equals(ObjectType.CHUNK_TREE.name())) {
                            ChunkTree ct = (ChunkTree) cas.readObject(f.getHash());
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            for (String ch : ct.getChunkHashes()) {
                                Blob b = (Blob) cas.readObject(ch);
                                out.write(b.getContent());
                            }
                            origData = out.toByteArray();
                        } else {
                            Blob b = (Blob) cas.readObject(f.getHash());
                            origData = b.getContent();
                        }
                        originalLines = Arrays.asList(new String(origData, StandardCharsets.UTF_8).split("\\r?\\n"));
                    } catch (Exception e) {
                        System.out.println("  (Could not read original content: " + e.getMessage() + ")");
                    }

                    Path diskPath = cas.getRootDir().resolve(f.getPath());
                    List<String> diskLines = Collections.emptyList();
                    if (Files.exists(diskPath)) {
                        diskLines = Files.readAllLines(diskPath, StandardCharsets.UTF_8);
                    }

                    try {
                        List<com.draftflow.merge.LineMerge.Edit> edits = com.draftflow.merge.LineMerge.diff(originalLines, diskLines);
                        for (com.draftflow.merge.LineMerge.Edit edit : edits) {
                            if (edit.type == com.draftflow.merge.LineMerge.EditType.INSERT) {
                                System.out.println("\u001B[32m+ " + edit.line + "\u001B[0m");
                            } else if (edit.type == com.draftflow.merge.LineMerge.EditType.DELETE) {
                                System.out.println("\u001B[31m- " + edit.line + "\u001B[0m");
                            } else {
                                System.out.println("  " + edit.line);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("  (Error rendering diff: " + e.getMessage() + ")");
                    }
                }
            }
            return 0;
        }
    }

    private static List<String> getPermanentParents(String hash, CAS cas) throws IOException {
        Revision r = (Revision) cas.readObject(hash);
        List<String> result = new ArrayList<>();
        for (String p : r.getParentHashes()) {
            String perm = getPermanentRevision(p, cas);
            if (perm != null) {
                result.add(perm);
            }
        }
        return result;
    }

    private static String findCommonAncestor(String rev1, String rev2, CAS cas) throws IOException {
        String p1 = getPermanentRevision(rev1, cas);
        String p2 = getPermanentRevision(rev2, cas);
        if (p1 == null || p2 == null) return null;

        Set<String> ancestors1 = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(p1);
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr != null && ancestors1.add(curr)) {
                try {
                    queue.addAll(getPermanentParents(curr, cas));
                } catch (Exception ignored) {}
            }
        }

        queue.clear();
        queue.add(p2);
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr != null) {
                if (ancestors1.contains(curr)) {
                    return curr;
                }
                try {
                    queue.addAll(getPermanentParents(curr, cas));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String getPermanentRevision(String hash, CAS cas) throws IOException {
        if (hash == null) return null;
        Revision r = (Revision) cas.readObject(hash);
        while (r.isDraft() && !r.getParentHashes().isEmpty()) {
            hash = r.getParentHashes().get(0);
            r = (Revision) cas.readObject(hash);
        }
        return r.isDraft() ? null : hash;
    }

    @Command(name = "rebase", description = "Rebase the current branch commits on top of upstream branch or commit")
    public static class RebaseCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Upstream branch or commit hash to rebase onto")
        private String upstream;

        @Option(names = {"-i", "--interactive"}, description = "Interactively edit commit list before rebasing")
        private boolean interactive;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    WorkspaceManager wm = new WorkspaceManager(cas, db);

                    String upstreamHash = db.getRef("heads/" + upstream);
                    if (upstreamHash == null) {
                        upstreamHash = cas.resolveHash(upstream);
                    }
                    if (upstreamHash == null) {
                        System.err.println("Error: Could not resolve upstream target: " + upstream);
                        return 1;
                    }
                    upstreamHash = getPermanentRevision(upstreamHash, cas);

                    if (!com.draftflow.core.HooksManager.runHook("pre-rebase", cas.getRootDir(), upstreamHash)) {
                        System.err.println("Fatal: pre-rebase hook failed. Aborting rebase.");
                        return 1;
                    }

                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev == null) {
                        System.err.println("Error: No active revision found.");
                        return 1;
                    }
                    activeRev = getPermanentRevision(activeRev, cas);

                    String ancestor = findCommonAncestor(activeRev, upstreamHash, cas);
                    System.out.printf("DEBUG REBASE: activeRev=%s, upstreamHash=%s, ancestor=%s\n", activeRev, upstreamHash, ancestor);
                    if (ancestor == null) {
                        System.err.println("Error: No common ancestor found between " + activeRev + " and " + upstreamHash);
                        return 1;
                    }

                    if (ancestor.equals(upstreamHash)) {
                        System.out.println("Current branch is already up to date with " + upstream);
                        return 0;
                    }

                    List<String> toReplay = new ArrayList<>();
                    String curr = activeRev;
                    while (curr != null && !curr.equals(ancestor)) {
                        toReplay.add(curr);
                        List<String> parents = getPermanentParents(curr, cas);
                        if (parents.isEmpty()) {
                            break;
                        }
                        curr = parents.get(0);
                    }
                    Collections.reverse(toReplay);

                    if (toReplay.isEmpty()) {
                        System.out.println("Nothing to replay.");
                        return 0;
                    }

                    // Handle Interactive Rebase
                    List<String> finalReplay = new ArrayList<>(toReplay);
                    Map<String, String> actionMap = new HashMap<>(); // commitHash -> action (pick, reword, squash, drop)
                    Map<String, String> customMessages = new HashMap<>(); // commitHash -> message

                    if (interactive) {
                        StringBuilder sbTodo = new StringBuilder();
                        sbTodo.append("# Interactive Rebase Todo List\n");
                        sbTodo.append("# Commands:\n");
                        sbTodo.append("#  pick <hash> <message> = use commit\n");
                        sbTodo.append("#  reword <hash> <message> = use commit, but change commit message\n");
                        sbTodo.append("#  squash <hash> <message> = use commit, but merge into previous commit\n");
                        sbTodo.append("#  drop <hash> <message> = skip commit\n#\n");
                        for (String h : toReplay) {
                            Revision r = (Revision) cas.readObject(h);
                            sbTodo.append(String.format("pick %s %s\n", h.substring(0, 8), r.getMessage()));
                        }

                        Path todoFile = cas.getDraftFlowDir().resolve("rebase-todo");
                        Files.writeString(todoFile, sbTodo.toString(), java.nio.charset.StandardCharsets.UTF_8);

                        String testTodo = System.getProperty("draftflow.test.rebase.todo");
                        if (testTodo != null) {
                            Files.writeString(todoFile, testTodo, java.nio.charset.StandardCharsets.UTF_8);
                        } else {
                            String editor = System.getenv("EDITOR");
                            if (editor == null) {
                                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                                    editor = "notepad.exe";
                                } else {
                                    editor = "vi";
                                }
                            }
                            ProcessBuilder pb = new ProcessBuilder(editor, todoFile.toAbsolutePath().toString());
                            pb.inheritIO();
                            Process p = pb.start();
                            p.waitFor();
                        }

                        // Parse edited todo file
                        finalReplay.clear();
                        List<String> lines = Files.readAllLines(todoFile, java.nio.charset.StandardCharsets.UTF_8);
                        for (String line : lines) {
                            String trim = line.trim();
                            if (trim.isEmpty() || trim.startsWith("#")) continue;
                            String[] parts = trim.split("\\s+", 3);
                            if (parts.length < 2) continue;
                            String act = parts[0].toLowerCase();
                            String partialHash = parts[1];
                            String msg = parts.length > 2 ? parts[2] : "";

                            // Resolve the partial hash to the full hash from original toReplay
                            String fullHash = null;
                            for (String orig : toReplay) {
                                if (orig.startsWith(partialHash)) {
                                    fullHash = orig;
                                    break;
                                }
                            }
                            if (fullHash == null) {
                                System.err.println("Warning: Could not resolve hash: " + partialHash);
                                continue;
                            }

                            if (act.equals("drop")) {
                                continue;
                            }

                            finalReplay.add(fullHash);
                            actionMap.put(fullHash, act);
                            if (act.equals("reword") && !msg.isEmpty()) {
                                customMessages.put(fullHash, msg);
                            }
                        }
                    }

                    if (finalReplay.isEmpty()) {
                        System.out.println("Nothing to replay after interactive filtering.");
                        return 0;
                    }

                    System.out.println("Rebasing " + finalReplay.size() + " commit(s) onto " + upstreamHash + "...");

                    wm.restoreWorkingCopy(upstreamHash);

                    String rebaseHead = upstreamHash;
                    DraftFlowConfig config = cas.getConfig();
                    com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());

                    for (String origComm : finalReplay) {
                        Revision origRev = (Revision) cas.readObject(origComm);
                        String origParent = origRev.getParentHashes().isEmpty() ? ancestor : origRev.getParentHashes().get(0);

                        String act = actionMap.getOrDefault(origComm, "pick");
                        System.out.println("Applying (" + act + "): " + origRev.getMessage());

                        db.setConfig("activeRevisionHash", rebaseHead);
                        db.commit();

                        wm.applyRevisionDiff(origParent, origComm);

                        Set<Path> allFiles = new HashSet<>();
                        Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                if (ignoreMatcher.isIgnored(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                if (!ignoreMatcher.isIgnored(file)) {
                                    allFiles.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });

                        for (FileMetadata meta : db.getAllFiles()) {
                            allFiles.add(cas.getRootDir().resolve(meta.getPath()));
                        }

                        String shadowHash = wm.scanAndCreateShadowCommit(allFiles);
                        Revision shadow = (Revision) cas.readObject(shadowHash);

                        if (act.equals("squash") && !rebaseHead.equals(upstreamHash)) {
                            Revision prevRev = (Revision) cas.readObject(rebaseHead);
                            String combinedMsg = prevRev.getMessage() + "\n\n" + origRev.getMessage();
                            Revision squashed = new Revision(
                                    shadow.getTreeHash(),
                                    prevRev.getParentHashes(),
                                    prevRev.getChangeId(),
                                    prevRev.getAuthor(),
                                    System.currentTimeMillis(),
                                    combinedMsg,
                                    false
                            );
                            squashed = SignatureHelper.signRevisionIfKeyExists(squashed, cas);
                            String squashedHash = cas.writeObject(squashed);
                            rebaseHead = squashedHash;
                        } else {
                            String commitMsg = act.equals("reword") && customMessages.containsKey(origComm)
                                    ? customMessages.get(origComm)
                                    : origRev.getMessage();
                            Revision replayed = new Revision(
                                    shadow.getTreeHash(),
                                    Collections.singletonList(rebaseHead),
                                    origRev.getChangeId(),
                                    origRev.getAuthor(),
                                    System.currentTimeMillis(),
                                    commitMsg,
                                    false
                            );
                            replayed = SignatureHelper.signRevisionIfKeyExists(replayed, cas);
                            String newCommHash = cas.writeObject(replayed);
                            rebaseHead = newCommHash;
                        }
                    }

                    String oldActive = db.getConfig("activeRevisionHash");
                    String activeHead = db.getConfig("activeHead");
                    if (activeHead != null) {
                        db.setRef(activeHead, rebaseHead);
                    }
                    db.setConfig("activeRevisionHash", rebaseHead);
                    db.commit();

                    com.draftflow.core.ReflogManager.logTransition(
                            cas.getRootDir(),
                            oldActive,
                            rebaseHead,
                            getAuthor(db),
                            "rebase: replaying branch onto " + upstream
                    );

                    System.out.println("Successfully rebased current branch onto " + upstream);
                }
                return 0;
            });
        }
    }

    @Command(name = "cherry-pick", description = "Apply the changes introduced by an arbitrary revision onto the current branch")
    public static class CherryPickCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "The revision hash to cherry-pick")
        private String targetRevision;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    WorkspaceManager wm = new WorkspaceManager(cas, db);

                    String targetHash = db.getRef("heads/" + targetRevision);
                    if (targetHash == null) {
                        targetHash = cas.resolveHash(targetRevision);
                    }
                    if (targetHash == null) {
                        System.err.println("Error: Could not resolve target revision: " + targetRevision);
                        return 1;
                    }
                    targetHash = getPermanentRevision(targetHash, cas);

                    Revision targetRev = (Revision) cas.readObject(targetHash);
                    List<String> parents = getPermanentParents(targetHash, cas);
                    String parentHash = parents.isEmpty() ? null : parents.get(0);
                    if (parentHash == null) {
                        System.err.println("Error: Cherry-pick target commit has no parent.");
                        return 1;
                    }

                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev == null) {
                        System.err.println("Error: No active commit in this repository.");
                        return 1;
                    }

                    System.out.println("Cherry-picking revision " + targetHash + ": " + targetRev.getMessage());

                    wm.applyRevisionDiff(parentHash, targetHash);

                    DraftFlowConfig config = cas.getConfig();
                    com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());
                    Set<Path> allFiles = new HashSet<>();
                    Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (ignoreMatcher.isIgnored(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (!ignoreMatcher.isIgnored(file)) {
                                allFiles.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });

                    for (FileMetadata meta : db.getAllFiles()) {
                        allFiles.add(cas.getRootDir().resolve(meta.getPath()));
                    }

                    String shadowHash = wm.scanAndCreateShadowCommit(allFiles);
                    Revision shadow = (Revision) cas.readObject(shadowHash);

                    Revision cherryCommit = new Revision(
                            shadow.getTreeHash(),
                            Collections.singletonList(activeRev),
                            UUID.randomUUID().toString(),
                            getAuthor(db),
                            System.currentTimeMillis(),
                            "Cherry-pick: " + targetRev.getMessage(),
                            false
                    );

                    cherryCommit = SignatureHelper.signRevisionIfKeyExists(cherryCommit, cas);

                    String newCommHash = cas.writeObject(cherryCommit);

                    String activeHead = db.getConfig("activeHead");
                    if (activeHead != null) {
                        db.setRef(activeHead, newCommHash);
                    }
                    db.setConfig("activeRevisionHash", newCommHash);
                    db.commit();

                    com.draftflow.core.ReflogManager.logTransition(
                            cas.getRootDir(),
                            activeRev,
                            newCommHash,
                            getAuthor(db),
                            "cherry-pick: " + targetRev.getMessage()
                    );

                    System.out.println("Successfully cherry-picked and committed: " + newCommHash);
                }
                return 0;
            });
        }
    }

    @Command(name = "ignore", description = "Add, list, or check file exclude ignore patterns")
    public static class IgnoreCmd implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Ignore pattern to append")
        private String pattern;

        @Option(names = {"--check"}, description = "Check ignore status of a specific file path")
        private String checkPath;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            Path dfIgnorePath = cas.getRootDir().resolve(".dfignore");

            if (checkPath != null) {
                Path target = cas.getRootDir().resolve(checkPath).toAbsolutePath().normalize();

                if (Files.exists(dfIgnorePath)) {
                    List<String> lines = Files.readAllLines(dfIgnorePath, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String trim = line.trim();
                        if (trim.isEmpty() || trim.startsWith("#")) continue;
                        com.draftflow.core.GitIgnoreMatcher m = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), Collections.singletonList(trim));
                        if (m.isIgnored(target)) {
                            System.out.println("Ignored: Yes");
                            System.out.println("Source: .dfignore");
                            System.out.println("Pattern: " + trim);
                            return 0;
                        }
                    }
                }

                Path gitIgnorePath = cas.getRootDir().resolve(".gitignore");
                if (Files.exists(gitIgnorePath)) {
                    List<String> lines = Files.readAllLines(gitIgnorePath, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String trim = line.trim();
                        if (trim.isEmpty() || trim.startsWith("#")) continue;
                        com.draftflow.core.GitIgnoreMatcher m = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), Collections.singletonList(trim));
                        if (m.isIgnored(target)) {
                            System.out.println("Ignored: Yes");
                            System.out.println("Source: .gitignore");
                            System.out.println("Pattern: " + trim);
                            return 0;
                        }
                    }
                }

                DraftFlowConfig config = cas.getConfig();
                com.draftflow.core.GitIgnoreMatcher m = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());
                if (m.isIgnored(target)) {
                    System.out.println("Ignored: Yes (default repository/build directory)");
                    return 0;
                }

                System.out.println("Ignored: No");
                return 0;
            }

            if (pattern != null) {
                List<String> lines = new ArrayList<>();
                if (Files.exists(dfIgnorePath)) {
                    lines = Files.readAllLines(dfIgnorePath, StandardCharsets.UTF_8);
                }
                if (lines.contains(pattern)) {
                    System.out.println("Pattern '" + pattern + "' already exists in .dfignore");
                    return 0;
                }
                Files.writeString(dfIgnorePath, pattern + "\n", StandardCharsets.UTF_8, 
                                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println("Added pattern '" + pattern + "' to .dfignore");
                return 0;
            }

            System.out.println("--- .dfignore patterns ---");
            if (Files.exists(dfIgnorePath)) {
                Files.readAllLines(dfIgnorePath, StandardCharsets.UTF_8).forEach(System.out::println);
            } else {
                System.out.println("(No .dfignore file found)");
            }

            Path gitIgnorePath = cas.getRootDir().resolve(".gitignore");
            System.out.println("\n--- .gitignore patterns ---");
            if (Files.exists(gitIgnorePath)) {
                Files.readAllLines(gitIgnorePath, StandardCharsets.UTF_8).forEach(System.out::println);
            } else {
                System.out.println("(No .gitignore file found)");
            }

            return 0;
        }
    }

    @Command(name = "clean", description = "Remove untracked files from the working directory")
    public static class CleanCmd implements Callable<Integer> {
        @Option(names = {"-d"}, description = "Remove untracked directories as well")
        private boolean removeDirs;

        @Option(names = {"-f", "--force"}, description = "Force clean (actually delete files)")
        private boolean force;

        @Option(names = {"-x"}, description = "Remove ignored files as well")
        private boolean cleanIgnored;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();
                    DraftFlowConfig config = cas.getConfig();
                    com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());

                    Set<String> trackedRelativePaths = new HashSet<>();
                    for (FileMetadata f : db.getAllFiles()) {
                        trackedRelativePaths.add(f.getPath());
                    }

                    List<Path> toDeleteFiles = new ArrayList<>();
                    List<Path> toDeleteDirs = new ArrayList<>();

                    Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (dir.equals(cas.getDraftFlowDir())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            String rel = cas.getRootDir().relativize(dir).toString().replace('\\', '/');
                            if (rel.isEmpty()) return FileVisitResult.CONTINUE;

                            // Check ignored status
                            boolean isIgn = ignoreMatcher.isIgnored(dir);
                            if (isIgn && !cleanIgnored) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }

                            // If directory itself is not tracked and is not parent of tracked files, it's untracked
                            boolean hasTrackedChildren = trackedRelativePaths.stream().anyMatch(p -> p.startsWith(rel + "/"));
                            if (!hasTrackedChildren) {
                                if (isIgn && cleanIgnored) {
                                    toDeleteDirs.add(dir);
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (!isIgn) {
                                    toDeleteDirs.add(dir);
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            String rel = cas.getRootDir().relativize(file).toString().replace('\\', '/');
                            if (trackedRelativePaths.contains(rel)) {
                                return FileVisitResult.CONTINUE;
                            }
                            boolean isIgn = ignoreMatcher.isIgnored(file);
                            if (isIgn && !cleanIgnored) {
                                return FileVisitResult.CONTINUE;
                            }
                            toDeleteFiles.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });

                    if (toDeleteFiles.isEmpty() && (!removeDirs || toDeleteDirs.isEmpty())) {
                        System.out.println("Nothing to clean.");
                        return 0;
                    }

                    if (!force) {
                        System.out.println("Dry-run (use -f/--force to delete):");
                        for (Path f : toDeleteFiles) {
                            System.out.println("Would remove file: " + cas.getRootDir().relativize(f));
                        }
                        if (removeDirs) {
                            for (Path d : toDeleteDirs) {
                                System.out.println("Would remove directory: " + cas.getRootDir().relativize(d));
                            }
                        }
                        return 0;
                    }

                    // Perform deletion
                    for (Path f : toDeleteFiles) {
                        Files.deleteIfExists(f);
                        System.out.println("Removed file: " + cas.getRootDir().relativize(f));
                    }
                    if (removeDirs) {
                        // Delete directories from deepest first (reverse order)
                        Collections.reverse(toDeleteDirs);
                        for (Path d : toDeleteDirs) {
                            deleteDirectoryRecursively(d);
                            System.out.println("Removed directory: " + cas.getRootDir().relativize(d));
                        }
                    }
                }
                return 0;
            });
        }

        private void deleteDirectoryRecursively(Path path) throws IOException {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Command(name = "ledger", description = "Show reference log history")
    public static class LedgerCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            List<com.draftflow.core.ReflogManager.ReflogEntry> entries = com.draftflow.core.ReflogManager.getReflog(cas.getRootDir());
            if (entries.isEmpty()) {
                System.out.println("No reflog entries found.");
                return 0;
            }

            // Print in reverse order (newest first)
            for (int i = entries.size() - 1; i >= 0; i--) {
                com.draftflow.core.ReflogManager.ReflogEntry e = entries.get(i);
                int idx = entries.size() - 1 - i;
                System.out.printf("%s HEAD@{%d}: %s\n", e.getNewHash().substring(0, 7), idx, e.getMessage());
            }
            return 0;
        }
    }

    @Command(name = "trace", description = "Annotate each line of a file with last commit information")
    public static class TraceCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "File path to blame")
        private String filePath;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    Path fullPath = cas.getRootDir().resolve(filePath).toAbsolutePath().normalize();
                    if (!Files.exists(fullPath)) {
                        System.err.println("Error: File not found: " + filePath);
                        return 1;
                    }
                    String relPath = cas.getRootDir().relativize(fullPath).toString().replace('\\', '/');

                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev == null) {
                        System.err.println("Error: No commits in this repository.");
                        return 1;
                    }

                    // Read current file lines from disk
                    List<String> currentLines = Files.readAllLines(fullPath, StandardCharsets.UTF_8);

                    // Trace lineage of commits backwards to identify introduction commit of each line
                    String[] finalBlame = new String[currentLines.size()];
                    for (int i = 0; i < currentLines.size(); i++) {
                        finalBlame[i] = activeRev; // Default to activeRev
                    }

                    String currHash = activeRev;
                    while (currHash != null) {
                        Revision rev = (Revision) cas.readObject(currHash);
                        if (rev.getParentHashes().isEmpty()) {
                            break;
                        }
                        String parentHash = rev.getParentHashes().get(0);
                        Revision parentRev = (Revision) cas.readObject(parentHash);

                        byte[] parentBytes = getFileContentAtCommit(cas, parentRev.getTreeHash(), relPath);
                        if (parentBytes == null) {
                            break;
                        }
                        List<String> parentLines = Arrays.asList(new String(parentBytes, StandardCharsets.UTF_8).split("\\r?\\n"));

                        for (int i = 0; i < currentLines.size(); i++) {
                            if (finalBlame[i].equals(currHash)) {
                                if (parentLines.contains(currentLines.get(i))) {
                                    finalBlame[i] = parentHash; // Propagate blame to parent
                                }
                            }
                        }

                        currHash = parentHash;
                    }

                    // Print final annotated lines
                    for (int i = 0; i < currentLines.size(); i++) {
                        String bh = finalBlame[i];
                        Revision r = (Revision) cas.readObject(bh);
                        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(r.getTimestamp()));
                        System.out.printf("%s (%s %s \"%s\") %s\n", bh.substring(0, 8), r.getAuthor(), dateStr, r.getMessage(), currentLines.get(i));
                    }
                }
                return 0;
            });
        }

        private byte[] getFileContentAtCommit(CAS cas, String treeHash, String relPath) throws IOException {
            String[] parts = relPath.split("/");

            String currentTreeHash = treeHash;
            for (int i = 0; i < parts.length; i++) {
                Tree currentTree = (Tree) cas.readObject(currentTreeHash);
                String part = parts[i];
                TreeEntry entry = currentTree.getEntries().stream()
                        .filter(e -> e.getName().equals(part))
                        .findFirst().orElse(null);
                if (entry == null) {
                    return null;
                }
                if (i == parts.length - 1) {
                    // Blob
                    if (entry.getType() == ObjectType.CHUNK_TREE) {
                        ChunkTree ct = (ChunkTree) cas.readObject(entry.getHash());
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        for (String ch : ct.getChunkHashes()) {
                            Blob b = (Blob) cas.readObject(ch);
                            out.write(b.getContent());
                        }
                        return out.toByteArray();
                    } else {
                        Blob b = (Blob) cas.readObject(entry.getHash());
                        return b.getContent();
                    }
                } else {
                    // Subdirectory Tree
                    if (entry.getType() != ObjectType.TREE) {
                        return null;
                    }
                    currentTreeHash = entry.getHash();
                }
            }
            return null;
        }
    }

    public static byte[] getFileBytes(com.draftflow.core.CAS cas, String hash, com.draftflow.core.ObjectType type) throws IOException {
        if (type == com.draftflow.core.ObjectType.BLOB || type == com.draftflow.core.ObjectType.DELTA_BLOB) {
            com.draftflow.core.Blob b = (com.draftflow.core.Blob) cas.readObject(hash);
            byte[] content = b.getContent();
            String contentStr = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            com.draftflow.core.LFSManager.LfsPointer ptr = com.draftflow.core.LFSManager.parsePointer(contentStr);
            if (ptr != null) {
                Path lfsCache = cas.getRootDir().resolve(".draftflow").resolve("lfs");
                Path targetFile = lfsCache.resolve(ptr.oid.substring(0, 2)).resolve(ptr.oid.substring(2));
                if (Files.exists(targetFile)) {
                    return Files.readAllBytes(targetFile);
                }
            }
            return content;
        } else if (type == com.draftflow.core.ObjectType.CHUNK_TREE) {
            com.draftflow.core.ChunkTree ct = (com.draftflow.core.ChunkTree) cas.readObject(hash);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (String ch : ct.getChunkHashes()) {
                com.draftflow.core.Blob b = (com.draftflow.core.Blob) cas.readObject(ch);
                out.write(b.getContent());
            }
            return out.toByteArray();
        }
        throw new IOException("Cannot read bytes for type: " + type);
    }

    public static String runProcess(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = p.getInputStream().read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        p.waitFor();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Command(name = "git-import", description = "Import git history from a local git repository")
    public static class GitImportCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Path to local Git repository")
        private String gitRepoPath;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            Path gitDir = Paths.get(gitRepoPath).toAbsolutePath().normalize();
            if (!Files.exists(gitDir.resolve(".git"))) {
                System.err.println("Fatal: " + gitRepoPath + " is not a valid Git repository.");
                return 1;
            }

            // Check if git repo has any commits
            boolean hasCommits = false;
            try {
                Process p = new ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
                    .directory(gitDir.toFile())
                    .start();
                hasCommits = (p.waitFor() == 0);
            } catch (Exception e) {
                // Ignore and assume false
            }

            if (!hasCommits) {
                System.out.println("No commits found to import.");
                return 0;
            }

            // Get original git branch/commit HEAD to restore later
            String originalHead = runProcess(gitDir, "git", "rev-parse", "HEAD").trim();

            // Fetch commits in reverse order (oldest first)
            String logOutput = runProcess(gitDir, "git", "log", "--reverse", "--format=%H");
            String[] commits = logOutput.split("\\r?\\n");
            if (commits.length == 0 || logOutput.trim().isEmpty()) {
                System.out.println("No commits found to import.");
                return 0;
            }

            System.out.println("Importing " + commits.length + " commits from Git repository...");

            // Track git commit hash -> DraftFlow revision hash
            Map<String, String> gitToDraftflow = new HashMap<>();

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    for (String gitHash : commits) {
                        gitHash = gitHash.trim();
                        if (gitHash.isEmpty()) continue;

                        System.out.println("Importing Git commit: " + gitHash);

                        // Checkout git commit
                        runProcess(gitDir, "git", "checkout", "--force", gitHash);

                        // Get author, timestamp, message, parents
                        String author = runProcess(gitDir, "git", "log", "-1", "--format=%an <%ae>", gitHash).trim();
                        long timestamp = Long.parseLong(runProcess(gitDir, "git", "log", "-1", "--format=%at", gitHash).trim()) * 1000;
                        String message = runProcess(gitDir, "git", "log", "-1", "--format=%B", gitHash).trim();
                        String parentsStr = runProcess(gitDir, "git", "log", "-1", "--format=%P", gitHash).trim();

                        // Get tracked files in git
                        String filesOutput = runProcess(gitDir, "git", "ls-files");
                        String[] files = filesOutput.split("\\r?\\n");

                        // Clear DraftFlow workspace files (excluding .draftflow)
                        WorkspaceManager wm = new WorkspaceManager(cas, db);
                        DraftFlowConfig config = cas.getConfig();
                        com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());

                        Files.walkFileTree(cas.getRootDir(), new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                if (dir.equals(cas.getDraftFlowDir())) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (ignoreMatcher.isIgnored(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                if (!ignoreMatcher.isIgnored(file)) {
                                    Files.deleteIfExists(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });

                        // Copy tracked files from git repo to DraftFlow workspace
                        Set<Path> copiedPaths = new HashSet<>();
                        for (String f : files) {
                            f = f.trim();
                            if (f.isEmpty()) continue;
                            Path src = gitDir.resolve(f);
                            Path dest = cas.getRootDir().resolve(f);
                            if (Files.exists(src)) {
                                Files.createDirectories(dest.getParent());
                                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                                copiedPaths.add(dest);
                            }
                        }

                        // Build tree and create shadow commit
                        String shadowHash = wm.scanAndCreateShadowCommit(copiedPaths);
                        Revision shadow = (Revision) cas.readObject(shadowHash);

                        // Map parents to DraftFlow parents
                        List<String> dfParents = new ArrayList<>();
                        if (!parentsStr.isEmpty()) {
                            for (String gp : parentsStr.split("\\s+")) {
                                String dfp = gitToDraftflow.get(gp.trim());
                                if (dfp != null) {
                                    dfParents.add(dfp);
                                }
                            }
                        }

                        // Create permanent revision
                        Revision permanent = new Revision(
                                shadow.getTreeHash(),
                                dfParents,
                                shadow.getChangeId(),
                                author,
                                timestamp,
                                message,
                                false
                        );
                        permanent = SignatureHelper.signRevisionIfKeyExists(permanent, cas);
                        String permanentHash = cas.writeObject(permanent);

                        String activeHead = db.getConfig("activeHead");
                        if (activeHead != null) {
                            db.setRef(activeHead, permanentHash);
                        }
                        db.setConfig("activeRevisionHash", permanentHash);
                        db.setChangeRevision(shadow.getChangeId(), permanentHash);

                        // Recreate shadow commit
                        wm.scanAndCreateShadowCommit(copiedPaths);
                        db.commit();

                        gitToDraftflow.put(gitHash, permanentHash);

                        com.draftflow.core.ReflogManager.logTransition(
                                cas.getRootDir(),
                                null,
                                permanentHash,
                                author,
                                "git-import: " + message
                        );
                    }
                } finally {
                    // Restore HEAD in Git repository
                    runProcess(gitDir, "git", "checkout", "--force", originalHead);
                }
                return 0;
            });
        }
    }

    @Command(name = "git-export", description = "Export DraftFlow history to a local Git repository")
    public static class GitExportCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Path to target Git repository")
        private String targetGitPath;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            Path gitDir = Paths.get(targetGitPath).toAbsolutePath().normalize();
            if (!Files.exists(gitDir)) {
                Files.createDirectories(gitDir);
            }
            if (!Files.exists(gitDir.resolve(".git"))) {
                runProcess(gitDir, "git", "init");
            }

            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (MetadataStore db = new MetadataStore(dbPath)) {
                    db.open();

                    String activeHash = db.getConfig("activeRevisionHash");
                    if (activeHash == null) {
                        System.out.println("Nothing to export (repository is empty).");
                        return 0;
                    }

                    // Topological sort using DFS
                    List<String> sortedRevisions = new ArrayList<>();
                    Set<String> visited = new HashSet<>();
                    dfsSort(activeHash, cas, visited, sortedRevisions);

                    // Track DraftFlow revision -> Git commit hash
                    Map<String, String> dfToGit = new HashMap<>();

                    for (String dfHash : sortedRevisions) {
                        Revision rev = (Revision) cas.readObject(dfHash);
                        if (rev.isDraft()) {
                            continue; // Skip shadow drafts
                        }

                        System.out.println("Exporting revision: " + dfHash);

                        // Clear Git directory (except .git)
                        Files.walkFileTree(gitDir, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                if (dir.equals(gitDir.resolve(".git"))) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Files.deleteIfExists(file);
                                return FileVisitResult.CONTINUE;
                            }
                        });

                        // Write files from tree to target Git directory
                        writeTreeToDirectory(cas, rev.getTreeHash(), gitDir, "");

                        // Git add
                        runProcess(gitDir, "git", "add", "-A");

                        // Git write-tree
                        String gitTreeHash = runProcess(gitDir, "git", "write-tree").trim();

                        // Build commit-tree command
                        List<String> commitTreeArgs = new ArrayList<>(Arrays.asList("git", "commit-tree", gitTreeHash));
                        Set<String> resolvedParents = new LinkedHashSet<>();
                        for (String parent : rev.getParentHashes()) {
                            resolvedParents.addAll(resolveNonDraftAncestors(parent, cas));
                        }
                        for (String parent : resolvedParents) {
                            String gitParent = dfToGit.get(parent);
                            if (gitParent != null) {
                                commitTreeArgs.add("-p");
                                commitTreeArgs.add(gitParent);
                            }
                        }
                        commitTreeArgs.add("-m");
                        commitTreeArgs.add(rev.getMessage());

                        // Run commit-tree with author/committer env variables
                        ProcessBuilder pb = new ProcessBuilder(commitTreeArgs).directory(gitDir.toFile());
                        pb.environment().put("GIT_AUTHOR_NAME", rev.getAuthor());
                        pb.environment().put("GIT_COMMITTER_NAME", rev.getAuthor());
                        pb.environment().put("GIT_AUTHOR_DATE", String.valueOf(rev.getTimestamp() / 1000));
                        pb.environment().put("GIT_COMMITTER_DATE", String.valueOf(rev.getTimestamp() / 1000));

                        Process p = pb.start();
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                        String gitCommitHash = reader.readLine();
                        p.waitFor();

                        if (gitCommitHash == null || gitCommitHash.trim().isEmpty()) {
                            throw new IOException("Failed to create Git commit for revision " + dfHash);
                        }
                        gitCommitHash = gitCommitHash.trim();
                        dfToGit.put(dfHash, gitCommitHash);

                        // Update main branch
                        runProcess(gitDir, "git", "update-ref", "refs/heads/main", gitCommitHash);
                        runProcess(gitDir, "git", "symbolic-ref", "HEAD", "refs/heads/main");
                    }
                }
                return 0;
            });
        }

        private Set<String> resolveNonDraftAncestors(String hash, CAS cas) throws IOException, ClassNotFoundException {
            Set<String> result = new LinkedHashSet<>();
            Queue<String> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            queue.add(hash);
            visited.add(hash);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                Revision rev = (Revision) cas.readObject(current);
                if (!rev.isDraft()) {
                    result.add(current);
                } else {
                    for (String p : rev.getParentHashes()) {
                        if (!visited.contains(p)) {
                            visited.add(p);
                            queue.add(p);
                        }
                    }
                }
            }
            return result;
        }

        private void dfsSort(String hash, CAS cas, Set<String> visited, List<String> sorted) throws IOException, ClassNotFoundException {
            if (hash == null || visited.contains(hash)) return;
            visited.add(hash);
            Revision rev = (Revision) cas.readObject(hash);
            for (String p : rev.getParentHashes()) {
                dfsSort(p, cas, visited, sorted);
            }
            sorted.add(hash);
        }

        private void writeTreeToDirectory(CAS cas, String treeHash, Path destDir, String relPath) throws IOException, ClassNotFoundException {
            Tree tree = (Tree) cas.readObject(treeHash);
            for (TreeEntry entry : tree.getEntries()) {
                String entryRelPath = relPath.isEmpty() ? entry.getName() : relPath + "/" + entry.getName();
                Path entryDest = destDir.resolve(entryRelPath);
                if (entry.getType() == ObjectType.TREE) {
                    Files.createDirectories(entryDest);
                    writeTreeToDirectory(cas, entry.getHash(), destDir, entryRelPath);
                } else {
                    Files.createDirectories(entryDest.getParent());
                    byte[] bytes = getFileBytes(cas, entry.getHash(), entry.getType());
                    Files.write(entryDest, bytes);
                }
            }
        }
    }

    @Command(name = "hooks", description = "View, install, or toggle repository hooks")
    public static class HooksCmd implements Callable<Integer> {
        @Option(names = {"--list"}, description = "List all hooks and their status")
        private boolean list;

        @Option(names = {"--install"}, description = "Hook name to install (pre-commit, post-commit, pre-rebase, pre-push, post-checkout)")
        private String hookName;

        @Option(names = {"--script"}, description = "Path to hook script to install")
        private String scriptPath;

        @Option(names = {"--create-sample"}, description = "Create sample shell/bat scripts for all hooks")
        private boolean createSample;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }

            Path hooksDir = cas.getDraftFlowDir().resolve("hooks");
            if (!Files.exists(hooksDir)) {
                Files.createDirectories(hooksDir);
            }

            String[] allHooks = {"pre-commit", "post-commit", "pre-rebase", "pre-push", "post-checkout"};

            if (createSample) {
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
                for (String hook : allHooks) {
                    if (isWin) {
                        Path batPath = hooksDir.resolve(hook + ".bat");
                        Files.writeString(batPath, "@echo off\necho Running sample " + hook + " hook...\nexit /b 0");
                        System.out.println("Created sample batch script: " + batPath.getFileName());
                    } else {
                        Path shPath = hooksDir.resolve(hook);
                        Files.writeString(shPath, "#!/bin/sh\necho \"Running sample " + hook + " hook...\"\nexit 0");
                        shPath.toFile().setExecutable(true);
                        System.out.println("Created sample shell script: " + shPath.getFileName());
                    }
                }
                return 0;
            }

            if (hookName != null && scriptPath != null) {
                Path src = Paths.get(scriptPath);
                if (!Files.exists(src)) {
                    System.err.println("Error: Script file does not exist: " + scriptPath);
                    return 1;
                }
                boolean isValidHook = false;
                for (String h : allHooks) {
                    if (h.equals(hookName)) {
                        isValidHook = true;
                        break;
                    }
                }
                if (!isValidHook) {
                    System.err.println("Error: Invalid hook name. Must be one of: " + Arrays.toString(allHooks));
                    return 1;
                }
                
                String ext = "";
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    if (scriptPath.endsWith(".bat")) ext = ".bat";
                    else if (scriptPath.endsWith(".cmd")) ext = ".cmd";
                }
                Path dest = hooksDir.resolve(hookName + ext);
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                dest.toFile().setExecutable(true);
                System.out.println("Successfully installed script as hook: " + dest.getFileName());
                return 0;
            }

            // Default to list
            System.out.println("DraftFlow Repository Hooks Status:");
            for (String hook : allHooks) {
                boolean installed = Files.exists(hooksDir.resolve(hook)) ||
                                    Files.exists(hooksDir.resolve(hook + ".bat")) ||
                                    Files.exists(hooksDir.resolve(hook + ".cmd"));
                System.out.printf("  %-15s : %s\n", hook, installed ? "[Installed]" : "[Not Configured]");
            }
            return 0;
        }
    }

    @Command(name = "config", description = "Get and set repository configuration settings")
    public static class ConfigCmd implements Callable<Integer> {
        @Option(names = {"--set"}, arity = "2", description = "Set a configuration parameter (key value)")
        private String[] setParam;

        @Option(names = {"--get"}, description = "Get a configuration parameter")
        private String getParam;

        @Option(names = {"--list"}, description = "List all configuration parameters")
        private boolean listParams;

        @Override
        public Integer call() throws Exception {
            Path currentDir = getCurrentDir();
            CAS cas = new CAS(currentDir);
            if (!Files.exists(cas.getDraftFlowDir())) {
                System.err.println("Fatal: Not a draftflow repository.");
                return 1;
            }
            return runLockedCommand(cas, () -> {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                try (com.draftflow.db.MetadataStore db = new com.draftflow.db.MetadataStore(dbPath)) {
                    db.open();
                    if (setParam != null && setParam.length == 2) {
                        String key = setParam[0];
                        String val = setParam[1];
                        db.setConfig(key, val);
                        db.commit();
                        System.out.println("Config set: " + key + " = " + val);
                    } else if (getParam != null) {
                        String val = db.getConfig(getParam);
                        if (val != null) {
                            System.out.println(val);
                        } else {
                            System.err.println("Config parameter not found: " + getParam);
                            return 1;
                        }
                    } else if (listParams) {
                        Map<String, String> configs = db.getAllConfig();
                        if (configs.isEmpty()) {
                            System.out.println("No configuration parameters set.");
                        } else {
                            configs.forEach((k, v) -> System.out.println(k + " = " + v));
                        }
                    } else {
                        System.err.println("Error: Specify either --set, --get, or --list");
                        return 1;
                    }
                    return 0;
                }
            });
        }
    }
}

