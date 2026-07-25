package com.draftflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class DraftFlowCliUnitTest {

    @TempDir
    Path tempDir;

    private String originalDraftFlowDirProp;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    public void setUp() throws Exception {
        originalDraftFlowDirProp = System.getProperty("draftflow.dir");
        Path workDir = tempDir.resolve("cli-repo");
        Files.createDirectories(workDir);
        System.setProperty("draftflow.dir", workDir.toAbsolutePath().toString());

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Initialize empty draftflow repo
        new CommandLine(new DraftFlow()).execute("setup");
    }

    @AfterEach
    public void tearDown() {
        if (originalDraftFlowDirProp != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDirProp);
        } else {
            System.clearProperty("draftflow.dir");
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int execute(String... args) {
        return new CommandLine(new DraftFlow()).execute(args);
    }

    @Test public void testSetupAlreadyExists() { assertEquals(0, execute("setup")); }
    @Test public void testStatusClean() { assertEquals(0, execute("status")); }
    @Test public void testStatusUntracked() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("untracked.txt"), "hello");
        assertEquals(0, execute("status"));
    }

    @Test public void testSaveSimpleCommit() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        assertEquals(0, execute("save", "-m", "Commit 1"));
    }

    @Test public void testSaveAmend() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        Files.writeString(repoDir.resolve("f1.txt"), "content 1 mod");
        assertEquals(0, execute("save", "-m", "Commit 1 Amended", "--amend"));
    }

    @Test public void testSaveAllFlag() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        assertEquals(0, execute("save", "-a", "-m", "Commit All"));
    }

    @Test public void testBranchList() { assertEquals(0, execute("branch")); }
    @Test public void testBranchCreate() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        assertEquals(0, execute("branch", "-c", "feat1"));
    }

    @Test public void testBranchDelete() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        execute("branch", "-c", "feat2");
        assertEquals(0, execute("branch", "-d", "feat2"));
    }

    @Test public void testSwitchBranch() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        execute("branch", "-c", "feat3");
        assertEquals(0, execute("switch", "feat3"));
    }

    @Test public void testHistoryLog() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        assertEquals(0, execute("history"));
    }

    @Test public void testStashPushPopListClear() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");

        Files.writeString(repoDir.resolve("f1.txt"), "dirty modification");
        assertEquals(0, execute("stash", "push"));
        assertEquals(0, execute("stash", "list"));
        assertEquals(0, execute("stash", "pop"));
        assertEquals(0, execute("stash", "clear"));
    }

    @Test public void testCleanDryRunAndForce() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("junk.tmp"), "junk");
        assertEquals(0, execute("clean", "-n"));
        assertEquals(0, execute("clean", "-f"));
    }

    @Test public void testDiffCmd() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "line 1");
        execute("save", "-m", "Commit 1");
        Files.writeString(repoDir.resolve("f1.txt"), "line 1 changed");
        assertEquals(0, execute("diff"));
    }

    @Test public void testIgnoreCmd() {
        assertEquals(0, execute("ignore", "*.log"));
        assertEquals(0, execute("ignore"));
    }

    @Test public void testKeysCmd() { assertEquals(0, execute("keys")); }
    @Test public void testVerifyCmd() { assertEquals(0, execute("verify")); }
    @Test public void testPruneCmd() { assertEquals(0, execute("prune")); }
    @Test public void testLedgerCmd() { assertEquals(0, execute("ledger")); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "invalidcmd1", "unknownsubcmd", "badcmd", "nonexistent", "foo-bar"
    })
    public void testInvalidSubcommands(String invalidCmd) {
        assertNotEquals(0, execute(invalidCmd));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "user.name", "user.email", "core.autocrlf", "core.editor", "diff.tool"
    })
    public void testConfigKeys(String configKey) {
        assertEquals(0, execute("config", configKey, "value-" + configKey.hashCode()));
        assertEquals(0, execute("config", configKey));
    }

    @Test public void testHooksCmdList() { assertEquals(0, execute("hooks", "list")); }
    @Test public void testHooksCmdInstall() { assertEquals(0, execute("hooks", "install", "pre-commit")); }

    @Test public void testConfigCmdList() { assertEquals(0, execute("config", "--list")); }
    @Test public void testConfigCmdGetSet() {
        assertEquals(0, execute("config", "user.name", "Tester"));
        assertEquals(0, execute("config", "user.name"));
    }

    @Test public void testUndoCmdNoParent() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        assertEquals(1, execute("undo")); // cannot undo root commit
    }

    @Test public void testUndoCmdWithParent() throws Exception {
        Path repoDir = Path.of(System.getProperty("draftflow.dir"));
        Files.writeString(repoDir.resolve("f1.txt"), "content 1");
        execute("save", "-m", "Commit 1");
        Files.writeString(repoDir.resolve("f1.txt"), "content 2");
        execute("save", "-m", "Commit 2");
        assertEquals(0, execute("undo"));
    }
}
