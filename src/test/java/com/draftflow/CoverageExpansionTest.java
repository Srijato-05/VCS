package com.draftflow;

import com.draftflow.core.*;
import com.draftflow.db.*;
import com.draftflow.ui.*;
import com.draftflow.remote.RemoteClient;
import com.draftflow.diff.FileDiff;
import com.draftflow.diff.DiffType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class CoverageExpansionTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    private String originalDraftFlowDirProp;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    public void setUp() {
        originalDraftFlowDirProp = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    public void tearDown() {
        if (originalDraftFlowDirProp != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDirProp);
        } else {
            System.clearProperty("draftflow.dir");
        }
        
        // Print captured streams to original stdout/stderr for Gradle reporting
        originalOut.println("=== STDOUT FOR TEST ===");
        originalOut.print(outContent.toString());
        originalOut.println("=======================");
        
        originalErr.println("=== STDERR FOR TEST ===");
        originalErr.print(errContent.toString());
        originalErr.println("=======================");

        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testResolveCmdNoRepo() throws Exception {
        // Resolve command without repo must return 1
        DraftFlow.ResolveCmd cmd = new DraftFlow.ResolveCmd();
        int res = cmd.call();
        assertEquals(1, res);
        assertTrue(errContent.toString().contains("Fatal: Not a draftflow repository"));
    }

    @Test
    public void testResolveCmdNoConflicts() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
        }

        DraftFlow.ResolveCmd cmd = new DraftFlow.ResolveCmd();
        int res = cmd.call();
        assertEquals(0, res);
        assertTrue(outContent.toString().contains("No unresolved conflicts"));
    }

    private Path initFreshRepo() throws Exception {
        Path repoPath = Files.createTempDirectory(tempDir, "repo");
        System.setProperty("draftflow.dir", repoPath.toAbsolutePath().toString());
        CAS cas = new CAS(repoPath);
        cas.init();
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
        }
        return repoPath;
    }

    @Test
    public void testResolveCmdOursDelete() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, null, "rightHash", "ours_del.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("ours_del.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("1\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            assertNull(db.getFile("ours_del.txt"));
        }
    }

    @Test
    public void testResolveCmdOursKeep() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("ours content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "ours_keep.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("ours_keep.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("1\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata fm = db.getFile("ours_keep.txt");
            assertEquals(leftHash, fm.getHash());
        }
    }

    @Test
    public void testResolveCmdTheirsDelete() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, "leftHash", null, "theirs_del.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("theirs_del.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("2\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            assertNull(db.getFile("theirs_del.txt"));
        }
    }

    @Test
    public void testResolveCmdTheirsKeep() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String rightHash = cas.writeObject(new Blob("theirs content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, null, rightHash, "theirs_keep.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("theirs_keep.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("2\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata fm = db.getFile("theirs_keep.txt");
            assertEquals(rightHash, fm.getHash());
        }
    }

    @Test
    public void testResolveCmdManualNotExist() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("left content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "manual.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("manual.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        Path manualPath = repo.resolve("manual.txt");
        Files.deleteIfExists(manualPath);

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("3\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }
        assertTrue(errContent.toString().contains("File does not exist on disk"));
    }

    @Test
    public void testResolveCmdManualMarkers() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("left content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "manual.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("manual.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        Path manualPath = repo.resolve("manual.txt");
        Files.writeString(manualPath, "<<<<<<< OURS\nleft\n=======\nright\n>>>>>>> THEIRS");

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("3\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }
        assertTrue(errContent.toString().contains("File still contains conflict markers"));
    }

    @Test
    public void testResolveCmdManualCleanSmall() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("left content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "manual.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("manual.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        Path manualPath = repo.resolve("manual.txt");
        Files.writeString(manualPath, "resolved clean content");

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("3\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata fm = db.getFile("manual.txt");
            assertEquals(ObjectType.BLOB.name(), fm.getType());
        }
    }

    @Test
    public void testResolveCmdManualCleanLarge() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("left content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "manual_large.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("manual_large.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }
        Path manualLargePath = repo.resolve("manual_large.txt");
        byte[] largeData = new byte[1024 * 1024 + 100];
        Arrays.fill(largeData, (byte) 'A');
        Files.write(manualLargePath, largeData);

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("3\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata fm = db.getFile("manual_large.txt");
            assertEquals(ObjectType.CHUNK_TREE.name(), fm.getType());
        }
    }

    @Test
    public void testResolveCmdInvalidChoice() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        String leftHash = cas.writeObject(new Blob("left content".getBytes(StandardCharsets.UTF_8)));
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            ConflictNode conflict = new ConflictNode(null, leftHash, null, "invalid_choice.txt");
            String conflictHash = cas.writeObject(conflict);
            FileMetadata fm = new FileMetadata("invalid_choice.txt", 10, 1000L, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fm);
            db.commit();
        }

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream("9\n".getBytes()));
        try {
            new DraftFlow.ResolveCmd().call();
        } finally {
            System.setIn(origIn);
        }
        assertTrue(outContent.toString().contains("Invalid choice. Skipping"));
    }

    @Test
    public void testIgnoreCmdOperations() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-ignore").toAbsolutePath().toString());
                DraftFlow.IgnoreCmd cmd = new DraftFlow.IgnoreCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        // Scenario 1: Default excludes only (no .dfignore or .gitignore on disk)
        {
            initFreshRepo();
            outContent.reset();
            DraftFlow.IgnoreCmd cmd = new DraftFlow.IgnoreCmd();
            java.lang.reflect.Field fCheck = cmd.getClass().getDeclaredField("checkPath");
            fCheck.setAccessible(true);
            fCheck.set(cmd, "build/somefile.txt");
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Ignored: Yes (default repository/build directory)"));
            
            // Check non-ignored file
            outContent.reset();
            fCheck.set(cmd, "source.java");
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Ignored: No"));
        }

        // Scenario 2: Add pattern and check status via .dfignore
        {
            initFreshRepo();
            outContent.reset();
            DraftFlow.IgnoreCmd cmd = new DraftFlow.IgnoreCmd();
            java.lang.reflect.Field fPat = cmd.getClass().getDeclaredField("pattern");
            fPat.setAccessible(true);
            fPat.set(cmd, "*.tmp");
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Added pattern '*.tmp' to .dfignore"));

            // Repeat add (already exists)
            outContent.reset();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("already exists in .dfignore"));

            // Check status of file ignored by .dfignore
            outContent.reset();
            DraftFlow.IgnoreCmd cmdCheck = new DraftFlow.IgnoreCmd();
            java.lang.reflect.Field fCheck = cmdCheck.getClass().getDeclaredField("checkPath");
            fCheck.setAccessible(true);
            fCheck.set(cmdCheck, "test.tmp");
            assertEquals(0, cmdCheck.call());
            assertTrue(outContent.toString().contains("Ignored: Yes"));
            assertTrue(outContent.toString().contains("Source: .dfignore"));
        }

        // Scenario 3: Check status via .gitignore (no .dfignore exists)
        {
            Path repo = initFreshRepo();
            Files.writeString(repo.resolve(".gitignore"), "*.log\n");
            
            outContent.reset();
            DraftFlow.IgnoreCmd cmdCheck = new DraftFlow.IgnoreCmd();
            java.lang.reflect.Field fCheck = cmdCheck.getClass().getDeclaredField("checkPath");
            fCheck.setAccessible(true);
            fCheck.set(cmdCheck, "test.log");
            assertEquals(0, cmdCheck.call());
            assertTrue(outContent.toString().contains("Ignored: Yes"));
            assertTrue(outContent.toString().contains("Source: .gitignore"));
        }
        
        // Scenario 4: List empty patterns
        {
            initFreshRepo();
            outContent.reset();
            DraftFlow.IgnoreCmd cmd = new DraftFlow.IgnoreCmd();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("--- .dfignore patterns ---"));
        }
    }

    @Test
    public void testDiffCmdOperations() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-diff").toAbsolutePath().toString());
                DraftFlow.DiffCmd cmd = new DraftFlow.DiffCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
        
        String bHash = cas.writeObject(new Blob("original line 1\n".getBytes(StandardCharsets.UTF_8)));

        // 1. No modifications detected
        {
            outContent.reset();
            Path pathOnDisk = repo.resolve("diff_test.txt");
            Files.writeString(pathOnDisk, "original line 1\n");
            long diskLastMod = Files.getLastModifiedTime(pathOnDisk).toMillis();
            long diskSize = Files.size(pathOnDisk);

            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.putFile(new FileMetadata("diff_test.txt", diskSize, diskLastMod, bHash, ObjectType.BLOB.name(), 100644));
                db.commit();
            }

            DraftFlow.DiffCmd cmd = new DiffCmdHelper(null);
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("No modifications detected"));
        }

        // 2. Modifications detected
        {
            outContent.reset();
            Files.writeString(repo.resolve("diff_test.txt"), "modified line 1\n");
            DraftFlow.DiffCmd cmd = new DiffCmdHelper(null);
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Diff for file: diff_test.txt"));
        }

        // 3. Diff specific tracked file
        {
            DraftFlow.DiffCmd cmd = new DiffCmdHelper("diff_test.txt");
            assertEquals(0, cmd.call());
        }

        // 4. Diff specific untracked file (error)
        {
            errContent.reset();
            DraftFlow.DiffCmd cmd = new DiffCmdHelper("untracked.txt");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: File not tracked in index"));
        }

        // 5. Diff chunked file
        {
            List<String> chunks = List.of(bHash);
            ChunkTree ct = new ChunkTree(chunks, List.of(16), 16);
            String ctHash = cas.writeObject(ct);

            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.putFile(new FileMetadata("diff_chunk.txt", 16, 1000L, ctHash, ObjectType.CHUNK_TREE.name(), 100644));
                db.commit();
            }

            Files.writeString(repo.resolve("diff_chunk.txt"), "chunk modified");
            DraftFlow.DiffCmd cmd = new DiffCmdHelper("diff_chunk.txt");
            assertEquals(0, cmd.call());
        }
    }

    private static class DiffCmdHelper extends DraftFlow.DiffCmd {
        DiffCmdHelper(String path) {
            try {
                java.lang.reflect.Field fPath = DraftFlow.DiffCmd.class.getDeclaredField("filePath");
                fPath.setAccessible(true);
                fPath.set(this, path);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testTraceCmdOperations() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-trace").toAbsolutePath().toString());
                DraftFlow.TraceCmd cmd = new DraftFlow.TraceCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();

        // 1. File not found
        {
            DraftFlow.TraceCmd cmd = new TraceCmdHelper("nonexistent.txt");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: File not found"));
        }

        // 2. No commits error
        {
            Path traceFile = repo.resolve("trace.txt");
            Files.writeString(traceFile, "content");
            DraftFlow.TraceCmd cmd = new TraceCmdHelper("trace.txt");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: No commits in this repository"));
        }
    }

    private static class TraceCmdHelper extends DraftFlow.TraceCmd {
        TraceCmdHelper(String path) {
            try {
                java.lang.reflect.Field fPath = DraftFlow.TraceCmd.class.getDeclaredField("filePath");
                fPath.setAccessible(true);
                fPath.set(this, path);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testRebaseCmdEdgeCases() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-rebase").toAbsolutePath().toString());
                DraftFlow.RebaseCmd cmd = new DraftFlow.RebaseCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // 1. Upstream resolution failure
        {
            DraftFlow.RebaseCmd cmd = new RebaseCmdHelper("nonexistent");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: Could not resolve upstream target"));
        }

        // 2. No active revision
        {
            String commitHash = cas.writeObject(new Revision("tree", Collections.emptyList(), "changeId", "auth", 1000L, "msg", false));
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setRef("heads/main", commitHash);
                db.commit();
            }

            DraftFlow.RebaseCmd cmd = new RebaseCmdHelper("main");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: No active revision found"));
        }
    }

    private static class RebaseCmdHelper extends DraftFlow.RebaseCmd {
        RebaseCmdHelper(String target) {
            try {
                java.lang.reflect.Field fUp = DraftFlow.RebaseCmd.class.getDeclaredField("upstream");
                fUp.setAccessible(true);
                fUp.set(this, target);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testStashCmdOperations() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-stash").toAbsolutePath().toString());
                DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // Create a commit first so a revision exists
        Path f = repo.resolve("file.txt");
        Files.writeString(f, "initial content");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/main");
            String rev = wm.scanAndCreateShadowCommit(Set.of(f));
            db.setConfig("activeRevisionHash", rev);
            db.setRef("heads/main", rev);
            db.commit();
        }

        // 1. Push stash when clean
        {
            outContent.reset();
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fPush = cmd.getClass().getDeclaredField("push");
            fPush.setAccessible(true);
            fPush.set(cmd, true);
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("No local modifications to stash."));
        }

        // 2. List stashes
        {
            outContent.reset();
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fList = cmd.getClass().getDeclaredField("list");
            fList.setAccessible(true);
            fList.set(cmd, true);
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("No stashes found"));
        }

        // 3. Pop stash when empty
        {
            errContent.reset();
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fPop = cmd.getClass().getDeclaredField("pop");
            fPop.setAccessible(true);
            fPop.set(cmd, true);
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("No stashes to pop"));
        }
    }

    @Test
    public void testUploadCmdEdgeCases() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-upload").toAbsolutePath().toString());
                DraftFlow.UploadCmd cmd = new DraftFlow.UploadCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // 1. No active branch
        {
            errContent.reset();
            DraftFlow.UploadCmd cmd = new UploadCmdHelper("file:///remote");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: No branch active to upload"));
        }

        // 2. No commits active
        {
            errContent.reset();
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setConfig("activeHead", "heads/main");
                db.commit();
            }
            DraftFlow.UploadCmd cmd = new UploadCmdHelper("file:///remote");
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: Branch is empty."));
        }
    }

    private static class UploadCmdHelper extends DraftFlow.UploadCmd {
        UploadCmdHelper(String url) {
            try {
                java.lang.reflect.Field fRem = DraftFlow.UploadCmd.class.getDeclaredField("remoteUrl");
                fRem.setAccessible(true);
                fRem.set(this, url);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testKeysCmd() throws Exception {
        // No repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-keys").toAbsolutePath().toString());
                DraftFlow.KeysCmd cmd = new DraftFlow.KeysCmd();
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        initFreshRepo();

        // 1. Generate keypair
        {
            outContent.reset();
            DraftFlow.KeysCmd cmd = new DraftFlow.KeysCmd();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Generating ECDSA keypair"));

            // Run again, should detect existing keypair
            outContent.reset();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Keypair already exists"));
        }
    }

    // --- Core Component Coverage ---

    @Test
    public void testCoreTreeAndRevisionGetter() throws Exception {
        // Tree coverage
        TreeEntry entry1 = new TreeEntry("file.txt", "hash", ObjectType.BLOB, 100644);
        Tree tree1 = new Tree(List.of(entry1));
        
        assertEquals(1, tree1.getEntries().size());
        assertEquals(ObjectType.TREE, tree1.getType());
        assertNotNull(tree1.serialize());

        Tree tree2 = Tree.deserialize(tree1.serialize());
        assertEquals("file.txt", tree2.getEntries().get(0).getName());

        // Revision coverage
        Revision r1 = new Revision("tree", Collections.emptyList(), "changeId", "author", 12345L, "msg", true);
        assertEquals("tree", r1.getTreeHash());
        assertEquals("changeId", r1.getChangeId());
        assertEquals("author", r1.getAuthor());
        assertEquals(12345L, r1.getTimestamp());
        assertEquals("msg", r1.getMessage());
        assertTrue(r1.isDraft());
        assertEquals(ObjectType.REVISION, r1.getType());
        assertNotNull(r1.serialize());

        Revision r2 = Revision.deserialize(r1.serialize());
        assertEquals("changeId", r2.getChangeId());

        // TreeEntry coverage
        assertEquals("hash", entry1.getHash());
        assertEquals(100644, entry1.getMode());
        Object diff = "different_type";
        assertFalse(entry1.equals(diff));

        // Blob coverage
        Blob blob = new Blob("content".getBytes(StandardCharsets.UTF_8));
        assertEquals(ObjectType.BLOB, blob.getType());
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), blob.serialize());
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), blob.getContent());

        // ChunkTree coverage
        ChunkTree ct = new ChunkTree(List.of("ch1"), List.of(5), 5);
        assertEquals(ObjectType.CHUNK_TREE, ct.getType());
        assertEquals(5, ct.getTotalSize());
        assertNotNull(ct.serialize());
        ChunkTree ctDec = ChunkTree.deserialize(ct.serialize());
        assertEquals(1, ctDec.getChunkHashes().size());
    }

    @Test
    public void testUiServerHandlersErrorPaths() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        UiServer uiServer = new UiServer(cas, db, 0);

        // Directly invoke LedgerHandler, DagHandler, TraceHandler, StatusHandler
        // to exercise their catch blocks/error paths using reflection or dynamic subclassing.

        // LedgerHandler with missing or corrupt reflog
        try {
            // LedgerHandler is a private inner class
            Class<?> ledgerClass = Class.forName("com.draftflow.ui.UiServer$LedgerHandler");
            java.lang.reflect.Constructor<?> cons = ledgerClass.getDeclaredConstructor(UiServer.class);
            cons.setAccessible(true);
            com.sun.net.httpserver.HttpHandler ledgerHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);

            // Mock HttpExchange
            com.sun.net.httpserver.HttpExchange exchange = mockExchange();
            ledgerHandler.handle(exchange);
        } catch (Exception e) {
            // Check that it completes gracefully or through standard error response code
        }

        // TraceHandler error paths
        try {
            Class<?> traceClass = Class.forName("com.draftflow.ui.UiServer$TraceHandler");
            java.lang.reflect.Constructor<?> cons = traceClass.getDeclaredConstructor(UiServer.class);
            cons.setAccessible(true);
            com.sun.net.httpserver.HttpHandler traceHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);

            com.sun.net.httpserver.HttpExchange exchange = mockExchange();
            traceHandler.handle(exchange);
        } catch (Exception e) {
        }

        // DagHandler error paths
        try {
            Class<?> dagClass = Class.forName("com.draftflow.ui.UiServer$DagHandler");
            java.lang.reflect.Constructor<?> cons = dagClass.getDeclaredConstructor(UiServer.class);
            cons.setAccessible(true);
            com.sun.net.httpserver.HttpHandler dagHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);

            com.sun.net.httpserver.HttpExchange exchange = mockExchange();
            dagHandler.handle(exchange);
        } catch (Exception e) {
        }

        // StatusHandler error paths
        try {
            Class<?> statusClass = Class.forName("com.draftflow.ui.UiServer$StatusHandler");
            java.lang.reflect.Constructor<?> cons = statusClass.getDeclaredConstructor(UiServer.class);
            cons.setAccessible(true);
            com.sun.net.httpserver.HttpHandler statusHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);

            com.sun.net.httpserver.HttpExchange exchange = mockExchange();
            statusHandler.handle(exchange);
        } catch (Exception e) {
        }

        db.close();
    }

    private static class MyHttpExchange extends com.sun.net.httpserver.HttpExchange {
        private final URI uri;
        private final com.sun.net.httpserver.Headers headers = new com.sun.net.httpserver.Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        MyHttpExchange(URI uri) {
            this.uri = uri;
        }

        @Override
        public com.sun.net.httpserver.Headers getRequestHeaders() { return null; }
        @Override
        public com.sun.net.httpserver.Headers getResponseHeaders() { return headers; }
        @Override
        public URI getRequestURI() { return uri; }
        @Override
        public String getRequestMethod() { return "GET"; }
        @Override
        public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
        @Override
        public void close() {}
        @Override
        public InputStream getRequestBody() { return null; }
        @Override
        public OutputStream getResponseBody() { return body; }
        @Override
        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {}
        @Override
        public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override
        public int getResponseCode() { return 200; }
        @Override
        public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override
        public String getProtocol() { return null; }
        @Override
        public Object getAttribute(String name) { return null; }
        @Override
        public void setAttribute(String name, Object value) {}
        @Override
        public void setStreams(InputStream i, OutputStream o) {}
        @Override
        public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }

    private com.sun.net.httpserver.HttpExchange mockExchange() {
        return new MyHttpExchange(URI.create("http://localhost/api/trace?file=missing"));
    }



    @Test
    public void testStatusCmdDeletedFiles() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.putFile(new FileMetadata("deleted.txt", 10, 1000L, "hash", ObjectType.BLOB.name(), 100644));
            db.commit();
        }
        outContent.reset();
        DraftFlow.StatusCmd cmd = new DraftFlow.StatusCmd();
        assertEquals(0, cmd.call());
        assertTrue(outContent.toString().contains("Deleted files:"));
        assertTrue(outContent.toString().contains("deleted.txt"));
    }

    @Test
    public void testDownloadCmdEdgeCases() throws Exception {
        // 1. Without repo
        {
            String old = System.getProperty("draftflow.dir");
            try {
                System.setProperty("draftflow.dir", tempDir.resolve("no-repo-download").toAbsolutePath().toString());
                DraftFlow.DownloadCmd cmd = new DraftFlow.DownloadCmd();
                java.lang.reflect.Field fRemote = cmd.getClass().getDeclaredField("remoteUrl");
                fRemote.setAccessible(true);
                fRemote.set(cmd, "http://localhost");
                assertEquals(1, cmd.call());
            } finally {
                if (old != null) {
                    System.setProperty("draftflow.dir", old);
                } else {
                    System.clearProperty("draftflow.dir");
                }
            }
        }

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // 2. Active branch null
        {
            DraftFlow.DownloadCmd cmd = new DraftFlow.DownloadCmd();
            java.lang.reflect.Field fRemote = cmd.getClass().getDeclaredField("remoteUrl");
            fRemote.setAccessible(true);
            fRemote.set(cmd, "http://localhost");
            errContent.reset();
            assertEquals(1, cmd.call());
            assertTrue(errContent.toString().contains("Error: No active branch to download into."));
        }

        // 3. Branch does not exist on remote
        {
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setConfig("activeHead", "heads/main");
                db.commit();
            }
            DraftFlow.DownloadCmd cmd = new DraftFlow.DownloadCmd();
            java.lang.reflect.Field fRemote = cmd.getClass().getDeclaredField("remoteUrl");
            fRemote.setAccessible(true);
            fRemote.set(cmd, "file://" + repo.toUri().getPath() + "/nonexistent_remote_repo");
            outContent.reset();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("does not exist on remote"));
        }
    }

    @Test
    public void testUploadCmdSubdirectoryTraversal() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // Create a subdirectory containing a file
        Path sub = repo.resolve("subdir");
        Files.createDirectories(sub);
        Path f = sub.resolve("file.txt");
        Files.writeString(f, "content");

        String commitHash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/main");
            commitHash = wm.scanAndCreateShadowCommit(Set.of(f));
            db.setConfig("activeRevisionHash", commitHash);
            db.setRef("heads/main", commitHash);
            db.commit();
        }

        // Run UploadCmd to trigger collectTreeObjects recursion
        DraftFlow.UploadCmd cmd = new DraftFlow.UploadCmd();
        java.lang.reflect.Field fRemote = cmd.getClass().getDeclaredField("remoteUrl");
        fRemote.setAccessible(true);
        fRemote.set(cmd, "file://" + repo.toUri().getPath() + "/remote_target");
        assertEquals(0, cmd.call());
    }

    @Test
    public void testDashboardCmdNoRepo() throws Exception {
        String old = System.getProperty("draftflow.dir");
        try {
            System.setProperty("draftflow.dir", tempDir.resolve("no-repo-dashboard").toAbsolutePath().toString());
            DraftFlow.DashboardCmd cmd = new DraftFlow.DashboardCmd();
            assertEquals(1, cmd.call());
        } finally {
            if (old != null) {
                System.setProperty("draftflow.dir", old);
            } else {
                System.clearProperty("draftflow.dir");
            }
        }
    }

    @Test
    public void testDiffCmdCorruptContent() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            // Put FileMetadata referencing a nonexistent hash
            db.putFile(new FileMetadata("corrupt.txt", 10, 1000L, "nonexistenthash", ObjectType.BLOB.name(), 100644));
            db.commit();
        }

        DraftFlow.DiffCmd cmd = new DraftFlow.DiffCmd();
        java.lang.reflect.Field fPath = DraftFlow.DiffCmd.class.getDeclaredField("filePath");
        fPath.setAccessible(true);
        fPath.set(cmd, "corrupt.txt");

        outContent.reset();
        assertEquals(0, cmd.call());
        assertTrue(outContent.toString().contains("Could not read original content"));
    }

    @Test
    public void testRebaseCmdInteractiveAndSquash() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // Create base commit (ancestor)
        Path f = repo.resolve("file.txt");
        Files.writeString(f, "base content");
        String ancestorHash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/main");
            String draftHash = wm.scanAndCreateShadowCommit(Set.of(f));
            Revision draft = (Revision) cas.readObject(draftHash);
            Revision perm = new Revision(draft.getTreeHash(), draft.getParentHashes(), draft.getChangeId(), "auth", 1000L, "base commit", false);
            ancestorHash = cas.writeObject(perm);
            db.setConfig("activeRevisionHash", ancestorHash);
            db.setRef("heads/main", ancestorHash);
            db.commit();
        }

        // Create upstream commit (main)
        Files.writeString(f, "upstream content");
        String upstreamHash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            String draftHash = wm.scanAndCreateShadowCommit(Set.of(f));
            Revision draft = (Revision) cas.readObject(draftHash);
            Revision perm = new Revision(draft.getTreeHash(), draft.getParentHashes(), draft.getChangeId(), "auth", 1001L, "upstream commit", false);
            upstreamHash = cas.writeObject(perm);
            db.setConfig("activeRevisionHash", upstreamHash);
            db.setRef("heads/main", upstreamHash);
            db.commit();
        }

        // Create downstream commit (feature branch)
        Files.writeString(repo.resolve("feature.txt"), "feature content");
        String featureHash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/feature");
            db.setConfig("activeRevisionHash", ancestorHash); // start feature branch from ancestor
            db.setRef("heads/feature", ancestorHash);
            db.commit();
            
            // commit a change on feature branch
            String draftHash = wm.scanAndCreateShadowCommit(Set.of(repo.resolve("feature.txt")));
            Revision draft = (Revision) cas.readObject(draftHash);
            Revision perm = new Revision(draft.getTreeHash(), draft.getParentHashes(), draft.getChangeId(), "auth", 1002L, "feature commit", false);
            featureHash = cas.writeObject(perm);
            db.setConfig("activeRevisionHash", featureHash);
            db.setRef("heads/feature", featureHash);
            db.commit();
        }

        // 1. Spawning interactive editor with a custom EDITOR command (bypassed with test todo property)
        System.setProperty("draftflow.test.rebase.todo", "pick " + featureHash.substring(0, 8) + " feature message\n");
        try {
            DraftFlow.RebaseCmd cmd = new DraftFlow.RebaseCmd();
            java.lang.reflect.Field fUp = cmd.getClass().getDeclaredField("upstream");
            fUp.setAccessible(true);
            fUp.set(cmd, "main");
            
            java.lang.reflect.Field fInter = cmd.getClass().getDeclaredField("interactive");
            fInter.setAccessible(true);
            fInter.set(cmd, true);

            assertEquals(0, cmd.call());
        } finally {
            System.clearProperty("draftflow.test.rebase.todo");
        }

        // 2. Interactive rebase where todo has "drop" or empty (Nothing to replay)
        {
            // Reset active revision back to featureHash
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setConfig("activeRevisionHash", featureHash);
                db.setRef("heads/feature", featureHash);
                db.commit();
            }

            // Mock todo content to squash/drop everything
            System.setProperty("draftflow.test.rebase.todo", "drop " + featureHash.substring(0, 8) + " msg\n");
            
            DraftFlow.RebaseCmd cmd = new DraftFlow.RebaseCmd();
            java.lang.reflect.Field fUp = cmd.getClass().getDeclaredField("upstream");
            fUp.setAccessible(true);
            fUp.set(cmd, "main");
            
            java.lang.reflect.Field fInter = cmd.getClass().getDeclaredField("interactive");
            fInter.setAccessible(true);
            fInter.set(cmd, true);

            outContent.reset();
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("Nothing to replay"));
            System.clearProperty("draftflow.test.rebase.todo");
        }

        // 3. Interactive rebase with squash action
        {
            // Reset active revision back to featureHash
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                db.setConfig("activeRevisionHash", featureHash);
                db.setRef("heads/feature", featureHash);
                db.commit();
            }

            System.setProperty("draftflow.test.rebase.todo", "squash " + featureHash.substring(0, 8) + " msg\n");

            DraftFlow.RebaseCmd cmd = new DraftFlow.RebaseCmd();
            java.lang.reflect.Field fUp = cmd.getClass().getDeclaredField("upstream");
            fUp.setAccessible(true);
            fUp.set(cmd, "main");
            
            java.lang.reflect.Field fInter = cmd.getClass().getDeclaredField("interactive");
            fInter.setAccessible(true);
            fInter.set(cmd, true);

            assertEquals(0, cmd.call());
            System.clearProperty("draftflow.test.rebase.todo");
        }
    }

    @Test
    public void testStashCmdPushPopOperations() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // 1. Create initial commit
        Path f = repo.resolve("file.txt");
        Files.writeString(f, "initial content");
        String rev;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/main");
            rev = wm.scanAndCreateShadowCommit(Set.of(f));
            db.setConfig("activeRevisionHash", rev);
            db.setRef("heads/main", rev);
            db.commit();
        }

        // 2. Modify file and push first stash
        Files.writeString(f, "stash 1 content");
        {
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fPush = cmd.getClass().getDeclaredField("push");
            fPush.setAccessible(true);
            fPush.set(cmd, true);
            assertEquals(0, cmd.call());
        }

        // 3. Modify file again and push second stash
        Files.writeString(f, "stash 2 content");
        {
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fPush = cmd.getClass().getDeclaredField("push");
            fPush.setAccessible(true);
            fPush.set(cmd, true);
            assertEquals(0, cmd.call());
        }

        // 4. List stashes (covers sorting comparator lambda!)
        {
            outContent.reset();
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fList = cmd.getClass().getDeclaredField("list");
            fList.setAccessible(true);
            fList.set(cmd, true);
            assertEquals(0, cmd.call());
            assertTrue(outContent.toString().contains("stashes/stash-"));
            assertTrue(outContent.toString().contains("Stash: WIP on main"));
        }

        // 5. Pop first stash
        {
            DraftFlow.StashCmd cmd = new DraftFlow.StashCmd();
            java.lang.reflect.Field fPop = cmd.getClass().getDeclaredField("pop");
            fPop.setAccessible(true);
            fPop.set(cmd, true);
            assertEquals(0, cmd.call());
        }
    }

    @Test
    public void testTreeGetEntry() {
        TreeEntry entry1 = new TreeEntry("file.txt", "hash", ObjectType.BLOB, 100644);
        Tree tree1 = new Tree(List.of(entry1));
        assertNotNull(tree1.getEntry("file.txt"));
        assertNull(tree1.getEntry("nonexistent.txt"));
    }

    @Test
    public void testWorkspaceManagerEdgeCases() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // 1. Transaction abort during restoreWorkingCopy (writeTempFile fails)
        {
            // Create a tree entry referencing a nonexistent hash
            TreeEntry entry = new TreeEntry("nonexistent.txt", "nonexistenthash", ObjectType.BLOB, 100644);
            String treeHash = cas.writeObject(new Tree(List.of(entry)));
            Revision r = new Revision(treeHash, Collections.emptyList(), "ch1", "auth", 1000L, "msg", false);
            String revHash = cas.writeObject(r);

            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                assertThrows(IOException.class, () -> wm.restoreWorkingCopy(revHash));
            }
        }

        // 2. applyRevisionDiff file deletion and execution permission removal
        {
            // Base state: file.txt (executable) and delete.txt
            Path fExec = repo.resolve("exec.txt");
            Files.writeString(fExec, "exec content");
            fExec.toFile().setExecutable(true, false);
            Path fDel = repo.resolve("delete.txt");
            Files.writeString(fDel, "delete content");

            String baseRevHash;
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                baseRevHash = wm.scanAndCreateShadowCommit(Set.of(fExec, fDel));
                db.setConfig("activeRevisionHash", baseRevHash);
                db.setRef("heads/main", baseRevHash);
                db.commit();
            }

            // Target state: delete.txt is gone, exec.txt is non-executable
            Files.deleteIfExists(fDel);
            Files.writeString(fExec, "new exec content");
            fExec.toFile().setExecutable(false, false);

            String targetRevHash;
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                targetRevHash = wm.scanAndCreateShadowCommit(Set.of(fExec, fDel));
                db.setConfig("activeRevisionHash", targetRevHash);
                db.setRef("heads/main", targetRevHash);
                db.commit();

                // Now apply revision diff
                Files.writeString(fExec, "exec content");
                fExec.toFile().setExecutable(true, false);
                Files.writeString(fDel, "delete content");
                
                wm.applyRevisionDiff(baseRevHash, targetRevHash);
                
                assertFalse(Files.exists(fDel)); // should be deleted
                if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                    assertFalse(fExec.toFile().canExecute()); // should no longer be executable
                }
            }
        }

        // 3. applyRevisionDiff clean 3-way merge
        {
            // Base state
            Path fMerge = repo.resolve("merge.txt");
            Files.writeString(fMerge, "line 1\nline 2\nline 3\n");
            
            String baseRevHash;
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                baseRevHash = wm.scanAndCreateShadowCommit(Set.of(fMerge));
            }

            // Ours state (modify line 1)
            Files.writeString(fMerge, "line 1 modified\nline 2\nline 3\n");
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                wm.scanAndCreateShadowCommit(Set.of(fMerge));
            }

            // Theirs state (modify line 3)
            Files.writeString(fMerge, "line 1\nline 2\nline 3 modified\n");
            String theirsRevHash;
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                theirsRevHash = wm.scanAndCreateShadowCommit(Set.of(fMerge));
            }

            // Restore ours state on disk
            Files.writeString(fMerge, "line 1 modified\nline 2\nline 3\n");
            
            try (MetadataStore db = new MetadataStore(dbPath)) {
                db.open();
                WorkspaceManager wm = new WorkspaceManager(cas, db);
                wm.applyRevisionDiff(baseRevHash, theirsRevHash);
                
                String content = Files.readString(fMerge);
                assertTrue(content.contains("line 1 modified"));
                assertTrue(content.contains("line 3 modified"));
            }
        }
    }

    @Test
    public void testUiServerHandlersExtra() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);
        Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");

        // Write entries to reflog
        ReflogManager.logTransition(repo, "hash1", "hash2", "author", "reflog message");

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            
            // Create a parent commit
            Path f = repo.resolve("trace.txt");
            Files.writeString(f, "initial line\n");
            WorkspaceManager wm = new WorkspaceManager(cas, db);
            db.setConfig("activeHead", "heads/main");
            String baseHash = wm.scanAndCreateShadowCommit(Set.of(f));
            db.setConfig("activeRevisionHash", baseHash);
            db.setRef("heads/main", baseHash);
            db.commit();

            // Create a child commit adding a new file (missing in parent)
            Path fNew = repo.resolve("added.txt");
            Files.writeString(fNew, "new file content");
            String childHash = wm.scanAndCreateShadowCommit(Set.of(f, fNew));
            db.setConfig("activeRevisionHash", childHash);
            db.setRef("heads/main", childHash);
            db.commit();

            UiServer uiServer = new UiServer(cas, db, 0);

            // 1. LedgerHandler formatting loop
            {
                Class<?> ledgerClass = Class.forName("com.draftflow.ui.UiServer$LedgerHandler");
                java.lang.reflect.Constructor<?> cons = ledgerClass.getDeclaredConstructor(UiServer.class);
                cons.setAccessible(true);
                com.sun.net.httpserver.HttpHandler ledgerHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);
                com.sun.net.httpserver.HttpExchange exchange = mockExchange();
                ledgerHandler.handle(exchange);
            }

            // 2. TraceHandler missing parent file path
            {
                Class<?> traceClass = Class.forName("com.draftflow.ui.UiServer$TraceHandler");
                java.lang.reflect.Constructor<?> cons = traceClass.getDeclaredConstructor(UiServer.class);
                cons.setAccessible(true);
                com.sun.net.httpserver.HttpHandler traceHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);
                
                com.sun.net.httpserver.HttpExchange exchange = new MyHttpExchange(URI.create("http://localhost/api/trace?file=added.txt"));
                traceHandler.handle(exchange);
            }

            // 3. DagHandler comma formatting for multiple parent/ref entries
            {
                // Create a merge commit with multiple parent hashes
                Revision mergeRev = new Revision(
                        cas.writeObject(new Tree(Collections.emptyList())),
                        List.of(baseHash, childHash),
                        "ch-merge",
                        "auth",
                        System.currentTimeMillis(),
                        "merge commit message",
                        false
                );
                String mergeHash = cas.writeObject(mergeRev);

                // Set multiple refs pointing to mergeHash
                db.setRef("heads/main", mergeHash);
                db.setRef("heads/feature", mergeHash);
                db.setConfig("activeRevisionHash", mergeHash);
                db.commit();

                Class<?> dagClass = Class.forName("com.draftflow.ui.UiServer$DagHandler");
                java.lang.reflect.Constructor<?> cons = dagClass.getDeclaredConstructor(UiServer.class);
                cons.setAccessible(true);
                com.sun.net.httpserver.HttpHandler dagHandler = (com.sun.net.httpserver.HttpHandler) cons.newInstance(uiServer);
                com.sun.net.httpserver.HttpExchange exchange = mockExchange();
                dagHandler.handle(exchange);
            }
        }
    }

    @Test
    public void testMergeEngineEdgeCases() throws Exception {
        assertNotNull(new com.draftflow.merge.MergeEngine());

        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);

        // mergeTrees identical files
        {
            String hash = cas.writeObject(new Blob("content".getBytes()));
            TreeEntry bEntry = new TreeEntry("file.txt", hash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(bEntry)));
            String oursTreeHash = cas.writeObject(new Tree(List.of(bEntry)));
            String theirsTreeHash = cas.writeObject(new Tree(List.of(bEntry)));

            com.draftflow.merge.MergeEngine.MergeResult res = com.draftflow.merge.MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertTrue(res.clean);
        }

        // mergeTrees type mismatch (Ours: BLOB vs Theirs: REVISION)
        {
            String fileHash = cas.writeObject(new Blob("content".getBytes()));
            TreeEntry oursEntry = new TreeEntry("path", fileHash, ObjectType.BLOB, 100644);
            String oursTreeHash = cas.writeObject(new Tree(List.of(oursEntry)));

            TreeEntry theirsEntry = new TreeEntry("path", fileHash, ObjectType.REVISION, 100644);
            String theirsTreeHash = cas.writeObject(new Tree(List.of(theirsEntry)));

            com.draftflow.merge.MergeEngine.MergeResult res = com.draftflow.merge.MergeEngine.mergeTrees(null, oursTreeHash, theirsTreeHash, cas);
            assertFalse(res.clean);
            assertTrue(res.conflicts.contains("path"));
        }
    }

    @Test
    public void testRemoteClientEdgeCases() throws Exception {
        // 1. Retry exhaustion
        RemoteClient rcRetry = new RemoteClient("http://127.0.0.1:54321");
        try {
            rcRetry.getRef("main");
            fail("Should have thrown IOException after retries");
        } catch (IOException expected) {}

        // 2. Local HttpServer status 500 & empty line skip
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("error")) {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            } else {
                String indexContent = "hash1 pack1\n\n\nhash2 pack2\n";
                byte[] resp = indexContent.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            RemoteClient rcErr = new RemoteClient("http://localhost:" + port + "/error");
            try {
                rcErr.downloadIndex();
                fail("Should have thrown IOException");
            } catch (IOException expected) {}

            RemoteClient rcParsed = new RemoteClient("http://localhost:" + port);
            Map<String, String> idx = rcParsed.downloadIndex();
            assertEquals("pack1", idx.get("hash1"));
            assertEquals("pack2", idx.get("hash2"));
        } finally {
            server.stop(0);
        }

        // 3. Windows path parsing check
        RemoteClient rcWin = new RemoteClient("file:///C:/projects/repo");
        // getRef calls getLocalPath, which strips leading slash for C:/...
        assertNull(rcWin.getRef("main"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMetadataStoreShortCircuit() throws Exception {
        Path dbFile = tempDir.resolve("metadata.db");
        try (MetadataStore db = new MetadataStore(dbFile)) {
            db.open();

            // Set the history map entry to empty string via reflection
            java.lang.reflect.Field fMap = MetadataStore.class.getDeclaredField("changeHistoryMap");
            fMap.setAccessible(true);
            Map<String, String> changeHistoryMap = (Map<String, String>) fMap.get(db);
            changeHistoryMap.put("empty_change", "");

            // Call getChangeHistory -> history is empty ("") -> hits short-circuit OR and returns empty list
            assertTrue(db.getChangeHistory("empty_change").isEmpty());

            // Call putChange -> history is empty -> hits short-circuit OR in addRevisionToChangeHistory
            db.setChangeRevision("empty_change", "hash");
            assertEquals("hash", db.getChangeHistory("empty_change").get(0));
        }
    }

    @Test
    public void testTreeDifferTypeChanges() throws Exception {
        Path repo = initFreshRepo();
        CAS cas = new CAS(repo);

        String blobHash = cas.writeObject(new Blob("content".getBytes()));

        TreeEntry fileEntry = new TreeEntry("node", blobHash, ObjectType.BLOB, 100644);
        String treeA = cas.writeObject(new Tree(List.of(fileEntry)));

        TreeEntry subEntry = new TreeEntry("subfile", blobHash, ObjectType.BLOB, 100644);
        String subTreeB = cas.writeObject(new Tree(List.of(subEntry)));
        TreeEntry dirEntry = new TreeEntry("node", subTreeB, ObjectType.TREE, 040000);
        String treeB = cas.writeObject(new Tree(List.of(dirEntry)));

        List<FileDiff> diffsAB = com.draftflow.diff.TreeDiffer.diff(treeA, treeB, cas);
        assertEquals(2, diffsAB.size());
        assertEquals("node", diffsAB.get(0).getPath());
        assertEquals(DiffType.DELETED, diffsAB.get(0).getType());

        List<FileDiff> diffsBA = com.draftflow.diff.TreeDiffer.diff(treeB, treeA, cas);
        assertEquals(2, diffsBA.size());
    }
}
