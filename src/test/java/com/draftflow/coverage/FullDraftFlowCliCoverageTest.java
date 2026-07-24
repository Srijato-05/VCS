package com.draftflow.coverage;

import com.draftflow.DraftFlow;
import com.draftflow.core.CAS;
import picocli.CommandLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FullDraftFlowCliCoverageTest {

    @TempDir
    Path tempDir;

    private String originalDraftFlowDir;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    public void setUp() throws Exception {
        originalDraftFlowDir = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Initialize repository structure
        CAS cas = new CAS(tempDir);
        cas.init();
    }

    @AfterEach
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);

        if (originalDraftFlowDir != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDir);
        } else {
            System.clearProperty("draftflow.dir");
        }
    }

    private int executeCli(String... args) {
        outContent.reset();
        errContent.reset();
        return new CommandLine(new DraftFlow()).execute(args);
    }

    @Test
    public void testMainMethodAndVersionOptions() {
        // Run main with --version
        DraftFlow.main(new String[]{"--version"});
        assertTrue(outContent.toString().contains("draftflow 1.0"));

        // Run main with --help
        outContent.reset();
        DraftFlow.main(new String[]{"--help"});
        assertTrue(outContent.toString().contains("DraftFlow: High-Performance"));
    }

    @Test
    public void testStatusAndSaveSubcommands() throws Exception {
        // Status on empty repo
        int exitCodeStatus = executeCli("status");
        assertEquals(0, exitCodeStatus);

        // Add file
        Files.writeString(tempDir.resolve("file1.txt"), "hello world");

        // Save command with message
        int exitCodeSave = executeCli("save", "-m", "First commit message");
        assertEquals(0, exitCodeSave);

        // Amend commit with save
        Files.writeString(tempDir.resolve("file1.txt"), "hello amended world");
        int exitCodeAmend = executeCli("save", "-m", "Amended commit message", "--amend");
        assertEquals(0, exitCodeAmend);
    }

    @Test
    public void testBranchAndSwitchSubcommands() throws Exception {
        Files.writeString(tempDir.resolve("file1.txt"), "content");
        executeCli("save", "-m", "Initial commit");

        // List branches
        int exitCodeBranchList = executeCli("branch");
        assertEquals(0, exitCodeBranchList);

        // Create new branch
        int exitCodeCreateBranch = executeCli("branch", "feature-xyz");
        assertEquals(0, exitCodeCreateBranch);

        // Switch to branch
        int exitCodeSwitch = executeCli("switch", "feature-xyz");
        assertEquals(0, exitCodeSwitch);

        // Delete branch
        executeCli("switch", "main");
        int exitCodeDeleteBranch = executeCli("branch", "-d", "feature-xyz");
        assertEquals(0, exitCodeDeleteBranch);
    }

    @Test
    public void testMergeAndRebaseOptions() throws Exception {
        Files.writeString(tempDir.resolve("base.txt"), "base content");
        executeCli("save", "-m", "Base commit");

        executeCli("branch", "feature");
        executeCli("switch", "feature");
        Files.writeString(tempDir.resolve("feature.txt"), "feature content");
        executeCli("save", "-m", "Feature commit");

        executeCli("switch", "main");
        Files.writeString(tempDir.resolve("main.txt"), "main content");
        executeCli("save", "-m", "Main commit");

        // Merge feature branch
        int exitCodeMerge = executeCli("merge", "feature");
        assertEquals(0, exitCodeMerge);

        // Rebase main onto feature
        int exitCodeRebase = executeCli("rebase", "feature");
        assertEquals(0, exitCodeRebase);
    }

    @Test
    public void testStashAndCleanSubcommands() throws Exception {
        Files.writeString(tempDir.resolve("tracked.txt"), "tracked content");
        executeCli("save", "-m", "Tracked file commit");

        // Modify file and stash
        Files.writeString(tempDir.resolve("tracked.txt"), "modified content");
        int exitCodeStashPush = executeCli("stash", "push");
        assertEquals(0, exitCodeStashPush);

        int exitCodeStashList = executeCli("stash", "list");
        assertEquals(0, exitCodeStashList);

        int exitCodeStashPop = executeCli("stash", "pop");
        assertEquals(0, exitCodeStashPop);

        // Untracked file clean
        Files.writeString(tempDir.resolve("untracked.txt"), "untracked content");
        int exitCodeCleanDryRun = executeCli("clean", "-n");
        assertEquals(0, exitCodeCleanDryRun);

        int exitCodeCleanForce = executeCli("clean", "-f");
        assertEquals(0, exitCodeCleanForce);
        assertFalse(Files.exists(tempDir.resolve("untracked.txt")));
    }

    @Test
    public void testHooksAndConfigSubcommands() throws Exception {
        // Config set & get
        int exitCodeConfigSet = executeCli("config", "user.name", "TestUser");
        assertEquals(0, exitCodeConfigSet);

        int exitCodeConfigGet = executeCli("config", "user.name");
        assertEquals(0, exitCodeConfigGet);
        assertTrue(outContent.toString().contains("TestUser"));

        // Hooks status & install
        int exitCodeHooksStatus = executeCli("hooks", "--status");
        assertEquals(0, exitCodeHooksStatus);

        int exitCodeHooksInstall = executeCli("hooks", "install", "pre-commit");
        assertEquals(0, exitCodeHooksInstall);
    }

    @Test
    public void testIgnoreTraceVerifyPruneSubcommands() throws Exception {
        Files.writeString(tempDir.resolve("tracked.txt"), "line 1\nline 2\n");
        executeCli("save", "-m", "Commit line content");

        // Ignore subcommands
        executeCli("ignore", "*.log");
        executeCli("ignore", "--list");

        // Trace subcommand
        int exitCodeTrace = executeCli("trace", "tracked.txt");
        assertEquals(0, exitCodeTrace);

        // Verify subcommand
        int exitCodeVerify = executeCli("verify");
        assertEquals(0, exitCodeVerify);

        // Prune subcommand
        int exitCodePrune = executeCli("prune");
        assertEquals(0, exitCodePrune);
    }
}
