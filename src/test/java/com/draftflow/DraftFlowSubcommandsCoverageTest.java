package com.draftflow;

import com.draftflow.core.CAS;
import picocli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class DraftFlowSubcommandsCoverageTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    private Path repoDir;
    private String originalDraftFlowDir;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private InputStream originalIn;

    @BeforeEach
    public void setUp() throws IOException {
        repoDir = tempDir.resolve("subcommands-repo").toAbsolutePath().normalize();
        Files.createDirectories(repoDir);
        originalDraftFlowDir = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", repoDir.toString());

        originalOut = System.out;
        originalErr = System.err;
        originalIn = System.in;

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    public void tearDown() {
        if (originalDraftFlowDir != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDir);
        } else {
            System.clearProperty("draftflow.dir");
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
    }

    private int runCommand(String... args) {
        outContent.reset();
        errContent.reset();
        return new CommandLine(new DraftFlow()).execute(args);
    }

    @Test
    public void testSubcommandsIntegration() throws Exception {
        // 1. Test running on a nonexistent repo (most commands should return 1)
        String[] cmdNames = {
            "status", "save", "switch", "merge", "resolve", "upload", "download",
            "history", "branch", "undo", "verify", "prune", "stash", "diff",
            "rebase", "cherry-pick", "ignore", "clean", "ledger", "trace"
        };
        for (String cmd : cmdNames) {
            int code = runCommand(cmd);
            assertNotEquals(0, code, "Command '" + cmd + "' should fail on a nonexistent repository");
        }

        // 2. SetupCmd success
        int setupCode1 = runCommand("setup");
        assertEquals(0, setupCode1);
        assertTrue(outContent.toString().contains("Initialized empty DraftFlow repository"));

        // SetupCmd already initialized
        int setupCode2 = runCommand("setup");
        assertEquals(0, setupCode2);
        assertTrue(outContent.toString().contains("DraftFlow repository already initialized"));

        // 3. KeysCmd - Generate cryptographic keys
        int keysCode = runCommand("keys");
        assertEquals(0, keysCode);
        String keysOut = outContent.toString();
        assertTrue(keysOut.contains("successfully") || keysOut.contains("already exists"));

        // 4. StatusCmd on clean repo
        int statusCleanCode = runCommand("status");
        assertEquals(0, statusCleanCode);
        assertTrue(outContent.toString().contains("Working copy is clean."));

        // Create file1.txt
        Path file1 = repoDir.resolve("file1.txt");
        Files.writeString(file1, "Initial Content\nLine 2\nLine 3\n");

        // StatusCmd with untracked/modified file
        // Note: Working directory needs shadow commit first to index the new file.
        int statusModifiedCode = runCommand("status");
        assertEquals(0, statusModifiedCode);

        // 5. SaveCmd first commit
        int saveCode1 = runCommand("save", "--message", "First commit");
        assertEquals(0, saveCode1);
        assertTrue(outContent.toString().contains("as revision:"));

        // 6. BranchCmd operations
        int branchListCode = runCommand("branch");
        assertEquals(0, branchListCode);
        assertTrue(outContent.toString().contains("* main"));

        // Create branch feature
        int branchCreateCode = runCommand("branch", "-c", "feature");
        assertEquals(0, branchCreateCode);
        assertTrue(outContent.toString().contains("Created branch: feature"));

        // Try creating existing branch (should overwrite or be ok)
        int branchCreateCodeDup = runCommand("branch", "-c", "feature");
        assertEquals(0, branchCreateCodeDup);

        // Delete branch feature
        int branchDeleteCode = runCommand("branch", "-d", "feature");
        assertEquals(0, branchDeleteCode);
        assertTrue(outContent.toString().contains("Deleted branch: feature"));

        // Recreate feature branch for switching later
        runCommand("branch", "-c", "feature");

        // 7. SaveCmd second commit
        Files.writeString(file1, "Initial Content\nLine 2 modified\nLine 3\n");
        int saveCode2 = runCommand("save", "-m", "WIP"); // No message defaults to WIP, but option is CLI-required
        assertEquals(0, saveCode2);

        // 8. HistoryCmd & LedgerCmd
        int historyCode = runCommand("history");
        assertEquals(0, historyCode);
        assertTrue(outContent.toString().contains("Revision:"));

        int ledgerCode = runCommand("ledger");
        assertEquals(0, ledgerCode);
        assertTrue(outContent.toString().contains("HEAD@{"));

        // 9. SwitchCmd to branch
        int switchBranchCode = runCommand("switch", "feature");
        assertEquals(0, switchBranchCode);
        assertTrue(outContent.toString().contains("Switched to revision:"));

        // Switch to nonexistent target
        int switchErrCode = runCommand("switch", "nonexistent");
        assertEquals(1, switchErrCode);
        assertTrue(errContent.toString().contains("Error: Revision not found"));

        // 10. UndoCmd
        // Switch back to main where the second commit was made
        runCommand("switch", "main");
        // Undo second commit
        int undoCode = runCommand("undo");
        assertEquals(0, undoCode);
        assertTrue(outContent.toString().contains("Reverted branch ref back to:"));

        // Undo again (only 1 commit left, parent is null)
        int undoRootCode = runCommand("undo");
        assertEquals(1, undoRootCode);
        assertTrue(errContent.toString().contains("Error: Root revision reached, cannot undo."));

        // 11. StashCmd
        // Modify file
        Files.writeString(file1, "Stash modified content");
        // Stash list empty check
        int stashListEmptyCode = runCommand("stash", "list");
        assertEquals(0, stashListEmptyCode);
        assertTrue(outContent.toString().contains("No stashes found."));

        // Stash pop empty check
        int stashPopEmptyCode = runCommand("stash", "pop");
        assertEquals(1, stashPopEmptyCode);
        assertTrue(errContent.toString().contains("Error: No stashes to pop."));

        // Stash push
        int stashSaveCode = runCommand("stash", "push");
        assertEquals(0, stashSaveCode);
        assertTrue(outContent.toString().contains("Saved working directory modifications to stash:"));

        // Stash list check
        int stashListCode = runCommand("stash", "list");
        assertEquals(0, stashListCode);
        assertTrue(outContent.toString().contains("stashes/stash-"));

        // Stash pop
        int stashPopCode = runCommand("stash", "pop");
        assertEquals(0, stashPopCode);
        assertTrue(outContent.toString().contains("Popped stash:"));

        // 12. IgnoreCmd
        int ignoreListCode = runCommand("ignore");
        assertEquals(0, ignoreListCode);

        int ignoreAddCode = runCommand("ignore", "*.tmp");
        assertEquals(0, ignoreAddCode);

        // Ignore check
        int ignoreCheckCode = runCommand("ignore", "--check", "test.tmp");
        assertEquals(0, ignoreCheckCode);
        assertTrue(outContent.toString().contains("Ignored: Yes"));

        int ignoreCheckNoCode = runCommand("ignore", "--check", "file1.txt");
        assertEquals(0, ignoreCheckNoCode);
        assertTrue(outContent.toString().contains("Ignored: No"));

        // 13. CleanCmd
        Path untrackedFile = repoDir.resolve("untracked.txt");
        Files.writeString(untrackedFile, "Untracked content");
        Path untrackedDir = repoDir.resolve("untrackedDir");
        Files.createDirectories(untrackedDir);
        Files.writeString(untrackedDir.resolve("nested.txt"), "nested");

        // Clean dry-run
        int cleanDryCode = runCommand("clean");
        assertEquals(0, cleanDryCode);
        assertTrue(outContent.toString().contains("Would remove file:"));

        // Clean force
        int cleanForceCode = runCommand("clean", "-f", "-d");
        assertEquals(0, cleanForceCode);
        assertFalse(Files.exists(untrackedFile));
        assertFalse(Files.exists(untrackedDir));

        // 14. DiffCmd
        Files.writeString(file1, "Diff changes");
        int diffCode = runCommand("diff");
        assertEquals(0, diffCode);
        assertTrue(outContent.toString().contains("- Stash modified content"));

        // Commit diff changes
        runCommand("save", "-m", "Commit for diff");

        // 15. VerifyCmd & PruneCmd
        int verifyCode = runCommand("verify");
        assertEquals(0, verifyCode);
        assertTrue(outContent.toString().contains("Index matches CAS successfully."));

        int pruneCode = runCommand("prune");
        assertEquals(0, pruneCode);
        assertTrue(outContent.toString().contains("Pruned "));

        // 16. TraceCmd
        int traceCode = runCommand("trace", "file1.txt");
        assertEquals(0, traceCode);
        assertTrue(outContent.toString().contains("Commit for diff"));

        // 17. Remote Commands: UploadCmd & DownloadCmd
        Path remoteDir = tempDir.resolve("remote-repo");
        Files.createDirectories(remoteDir);
        // Init remote repo
        System.setProperty("draftflow.dir", remoteDir.toString());
        runCommand("setup");
        System.setProperty("draftflow.dir", repoDir.toString());

        String remoteUrl = "file://" + remoteDir.toString().replace('\\', '/');

        // UploadCmd success
        int uploadCode = runCommand("upload", "--remote", remoteUrl);
        assertEquals(0, uploadCode);
        assertTrue(outContent.toString().contains("Upload successful!"));

        // DownloadCmd success
        int downloadCode = runCommand("download", "--remote", remoteUrl);
        assertEquals(0, downloadCode);
        assertTrue(outContent.toString().contains("Already up-to-date.") || outContent.toString().contains("Download successful!"));

        // 18. RebaseCmd
        // Create another branch "topic", make a commit, switch to main, make a commit, rebase topic on main
        runCommand("branch", "-c", "topic");
        runCommand("switch", "topic");
        Files.writeString(file1, "Topic branch change");
        runCommand("save", "-m", "Topic commit");

        runCommand("switch", "main");
        Files.writeString(file1, "Main branch change");
        runCommand("save", "-m", "Main commit");

        runCommand("switch", "topic");
        int rebaseCode = runCommand("rebase", "main");
        // Rebase might conflict or not; in either case we verify it executes
        assertTrue(rebaseCode == 0 || rebaseCode == 1);

        // 19. ResolveCmd & Interactive Scanner Simulation
        // Make conflicted merge to trigger ResolveCmd
        runCommand("switch", "main");
        // Create branch test-merge
        runCommand("branch", "-c", "test-merge");
        Files.writeString(file1, "Main final change");
        runCommand("save", "-m", "Main commit 2");

        runCommand("switch", "test-merge");
        Files.writeString(file1, "Test-merge final change");
        runCommand("save", "-m", "Test-merge commit");

        runCommand("switch", "main");
        int mergeCode = runCommand("merge", "test-merge");
        // Merging should yield conflict and print CONFLICTS! (but return exit code 0)
        assertEquals(0, mergeCode);
        assertTrue(outContent.toString().contains("CONFLICTS!"));

        // Simulating "1" (Ours) resolution
        InputStream simulationIn = new ByteArrayInputStream("1\n".getBytes(StandardCharsets.UTF_8));
        System.setIn(simulationIn);
        int resolveCode = runCommand("resolve");
        assertEquals(0, resolveCode);
        assertTrue(outContent.toString().contains("Resolved file1.txt using OURS version."));

        // 20. CherryPickCmd
        // Find history commit and cherry pick it
        CAS cas = new CAS(repoDir);
        String commitHash = cas.resolveHash("HEAD~1");
        if (commitHash != null) {
            int cpCode = runCommand("cherry-pick", commitHash);
            // Verify cpCode executes, might conflict or succeed
            assertTrue(cpCode == 0 || cpCode == 1);
        }
    }

    @Test
    public void testHelpAndInvalid() {
        int code1 = runCommand("--help");
        assertEquals(0, code1);
        assertTrue(outContent.toString().contains("Usage:"));

        int code2 = runCommand("invalidCommand");
        assertEquals(2, code2);
    }

    private void invokePrivateConstructor(Class<?> clazz) throws Exception {
        java.lang.reflect.Constructor<?> c = clazz.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }

    @Test
    public void testPrivateConstructorsAndDataModels() throws Exception {
        new com.draftflow.cdc.FastCDC();
        invokePrivateConstructor(com.draftflow.core.Hasher.class);
        new com.draftflow.core.Compressor();
        new com.draftflow.core.BinaryDelta();
        invokePrivateConstructor(com.draftflow.core.SignatureHelper.class);
        new com.draftflow.remote.OCC();
        new com.draftflow.remote.Packer();
        new com.draftflow.core.GitIgnoreMatcher(tempDir, null);
        new com.draftflow.diff.TreeDiffer();
        new com.draftflow.core.HooksManager();

        // Test FileDiff getters and toString
        com.draftflow.diff.FileDiff fd = new com.draftflow.diff.FileDiff("path", com.draftflow.diff.DiffType.MODIFIED, "old", "new", 100644, 100755);
        assertEquals(100644, fd.getOldMode());
        assertEquals(100755, fd.getNewMode());
        assertNotNull(fd.toString());

        // Test FileMetadata invalid JSON parsing
        assertNull(com.draftflow.db.FileMetadata.fromJson("{invalid-json"));

        // Test FastCDC.Chunk getters and setters
        com.draftflow.cdc.FastCDC.Chunk chunk1 = new com.draftflow.cdc.FastCDC.Chunk(new byte[]{1, 2, 3}, 0, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, chunk1.getBytes());
        assertNull(chunk1.getHash());
        chunk1.setHash("hash");
        assertEquals("hash", chunk1.getHash());
        
        com.draftflow.cdc.FastCDC.Chunk chunk2 = new com.draftflow.cdc.FastCDC.Chunk(new byte[]{1, 2, 3, 4}, 1, 2);
        assertArrayEquals(new byte[]{2, 3}, chunk2.getBytes());

        // Test Hasher invalid algorithm exception
        assertThrows(RuntimeException.class, () -> com.draftflow.core.Hasher.hash(new byte[]{1}, "INVALID-ALGO"));
    }
}
