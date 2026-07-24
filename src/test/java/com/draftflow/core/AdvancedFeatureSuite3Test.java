package com.draftflow.core;

import com.draftflow.DraftFlow;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


import static org.junit.jupiter.api.Assertions.*;

public class AdvancedFeatureSuite3Test {

    @TempDir
    Path tempDir;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void testInteractiveRebase() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        // 1. Commit on main
        Path fileA = tempDir.resolve("fileA.txt");
        Files.writeString(fileA, "Base Line 1\n");

        DraftFlow.SaveCmd save = new DraftFlow.SaveCmd();
        setField(save, "message", "C1 Message");
        assertEquals(0, save.call());

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
        }

        // 2. Create branch feature and commit 2 commits
        DraftFlow.BranchCmd branch = new DraftFlow.BranchCmd();
        setField(branch, "newBranch", "feature");
        assertEquals(0, branch.call());

        DraftFlow.SwitchCmd switchFeature = new DraftFlow.SwitchCmd();
        setField(switchFeature, "revisionHash", "feature");
        assertEquals(0, switchFeature.call());

        Files.writeString(fileA, "Base Line 1\nFeature Line 2\n");
        DraftFlow.SaveCmd save2 = new DraftFlow.SaveCmd();
        setField(save2, "message", "C2 Message");
        assertEquals(0, save2.call());

        String c2Hash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            c2Hash = getPermanentHash(db.getConfig("activeRevisionHash"), cas);
        }

        Files.writeString(fileA, "Base Line 1\nFeature Line 2\nFeature Line 3\n");
        DraftFlow.SaveCmd save3 = new DraftFlow.SaveCmd();
        setField(save3, "message", "C3 Message");
        assertEquals(0, save3.call());

        String c3Hash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            c3Hash = getPermanentHash(db.getConfig("activeRevisionHash"), cas);
        }

        // 3. Switch back to main and commit upstream change
        DraftFlow.SwitchCmd switchCmd = new DraftFlow.SwitchCmd();
        setField(switchCmd, "revisionHash", "main");
        assertEquals(0, switchCmd.call());

        Path fileB = tempDir.resolve("fileB.txt");
        Files.writeString(fileB, "Upstream Content\n");
        DraftFlow.SaveCmd saveUp = new DraftFlow.SaveCmd();
        setField(saveUp, "message", "Upstream Message");
        assertEquals(0, saveUp.call());

        String upstreamHash;
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            upstreamHash = getPermanentHash(db.getRef("heads/main"), cas);
        }

        // 4. Switch to feature branch and perform interactive rebase onto main
        // We simulate editing the todo file via system properties
        // We will drop C3, squash C2, reword C2 to "Reworded C2 Message"
        // Since we drop C3 and reword/squash C2, we expect only C2 (reworded) to be replayed.
        DraftFlow.SwitchCmd switchCmd2 = new DraftFlow.SwitchCmd();
        setField(switchCmd2, "revisionHash", "feature");
        assertEquals(0, switchCmd2.call());

        String todoContent = String.format("reword %s Reworded C2 Message\ndrop %s C3 Message\n", c2Hash.substring(0, 8), c3Hash.substring(0, 8));
        System.setProperty("draftflow.test.rebase.todo", todoContent);

        try {
            DraftFlow.RebaseCmd rebase = new DraftFlow.RebaseCmd();
            setField(rebase, "upstream", "main");
            setField(rebase, "interactive", true);
            assertEquals(0, rebase.call());
        } finally {
            System.clearProperty("draftflow.test.rebase.todo");
        }

        // Verify rebase result
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            String activeRev = db.getConfig("activeRevisionHash");
            Revision rev = (Revision) cas.readObject(getPermanentHash(activeRev, cas));

            // Commit C3 was dropped, C2 was reworded
            assertEquals("Reworded C2 Message", rev.getMessage());
            // Its parent must be the upstreamHash
            assertEquals(upstreamHash, rev.getParentHashes().get(0));
        }
    }

    @Test
    public void testCommitHooks() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        Path hooksDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hooksDir);

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        Path preCommitFile = isWin ? hooksDir.resolve("pre-commit.bat") : hooksDir.resolve("pre-commit");

        // 1. Test failing pre-commit hook
        Files.writeString(preCommitFile, isWin ? "@echo off\nexit /b 1\n" : "#!/bin/sh\nexit 1\n");
        if (!isWin) {
            preCommitFile.toFile().setExecutable(true);
        }

        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hook test content");

        DraftFlow.SaveCmd saveFail = new DraftFlow.SaveCmd();
        setField(saveFail, "message", "Failing hook commit");
        // Save must return non-zero exit code due to failing pre-commit hook
        assertEquals(1, saveFail.call());

        // 2. Test successful hook
        Files.writeString(preCommitFile, isWin ? "@echo off\nexit /b 0\n" : "#!/bin/sh\nexit 0\n");
        DraftFlow.SaveCmd saveSuccess = new DraftFlow.SaveCmd();
        setField(saveSuccess, "message", "Succeeding hook commit");
        assertEquals(0, saveSuccess.call());
    }

    @Test
    public void testWorkspaceClean() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        // Commit one file
        Path trackedFile = tempDir.resolve("tracked.txt");
        Files.writeString(trackedFile, "Tracked");
        DraftFlow.SaveCmd save = new DraftFlow.SaveCmd();
        setField(save, "message", "Initial commit");
        assertEquals(0, save.call());

        // Create untracked file
        Path untrackedFile = tempDir.resolve("untracked.txt");
        Files.writeString(untrackedFile, "Untracked");

        // Create ignored file (dfignore)
        Path dfIgnore = tempDir.resolve(".dfignore");
        Files.writeString(dfIgnore, "ignored.txt\n");

        Path ignoredFile = tempDir.resolve("ignored.txt");
        Files.writeString(ignoredFile, "Ignored Content");

        // Create untracked dir
        Path untrackedDir = tempDir.resolve("untracked_dir");
        Files.createDirectories(untrackedDir);
        Files.writeString(untrackedDir.resolve("nested.txt"), "Nested");

        // 1. Dry run clean (nothing should be deleted)
        DraftFlow.CleanCmd cleanDry = new DraftFlow.CleanCmd();
        setField(cleanDry, "force", false);
        setField(cleanDry, "removeDirs", true);
        setField(cleanDry, "cleanIgnored", true);
        assertEquals(0, cleanDry.call());

        assertTrue(Files.exists(untrackedFile));
        assertTrue(Files.exists(ignoredFile));
        assertTrue(Files.exists(untrackedDir));

        // 2. Force Clean untracked files (but not directories or ignored files)
        DraftFlow.CleanCmd cleanFiles = new DraftFlow.CleanCmd();
        setField(cleanFiles, "force", true);
        setField(cleanFiles, "removeDirs", false);
        setField(cleanFiles, "cleanIgnored", false);
        assertEquals(0, cleanFiles.call());

        assertFalse(Files.exists(untrackedFile)); // Deleted
        assertTrue(Files.exists(ignoredFile)); // Spared (ignored)
        assertTrue(Files.exists(untrackedDir)); // Spared (dir)

        // 3. Force Clean directories
        DraftFlow.CleanCmd cleanDirs = new DraftFlow.CleanCmd();
        setField(cleanDirs, "force", true);
        setField(cleanDirs, "removeDirs", true);
        setField(cleanDirs, "cleanIgnored", false);
        assertEquals(0, cleanDirs.call());

        assertFalse(Files.exists(untrackedDir)); // Deleted

        // 4. Force Clean ignored
        DraftFlow.CleanCmd cleanAll = new DraftFlow.CleanCmd();
        setField(cleanAll, "force", true);
        setField(cleanAll, "removeDirs", true);
        setField(cleanAll, "cleanIgnored", true);
        assertEquals(0, cleanAll.call());

        assertFalse(Files.exists(ignoredFile)); // Deleted
        assertTrue(Files.exists(trackedFile)); // Keep tracked
    }

    @Test
    public void testLedger() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        // Commit 1
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "Line 1");
        DraftFlow.SaveCmd save1 = new DraftFlow.SaveCmd();
        setField(save1, "message", "Commit 1");
        assertEquals(0, save1.call());

        // Commit 2
        Files.writeString(file, "Line 1\nLine 2");
        DraftFlow.SaveCmd save2 = new DraftFlow.SaveCmd();
        setField(save2, "message", "Commit 2");
        assertEquals(0, save2.call());

        // Undo last commit
        DraftFlow.UndoCmd undo = new DraftFlow.UndoCmd();
        assertEquals(0, undo.call());

        // Read ledger output
        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            DraftFlow.LedgerCmd ledger = new DraftFlow.LedgerCmd();
            assertEquals(0, ledger.call());
        } finally {
            System.setOut(oldOut);
        }

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("commit: Commit 1"));
        assertTrue(output.contains("commit: Commit 2"));
        assertTrue(output.contains("undo: revert to parent"));
    }

    @Test
    public void testTraceCommand() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        // 1. Commit original file
        Path testFile = tempDir.resolve("blame_test.txt");
        Files.writeString(testFile, "Line A\nLine B\n");
        DraftFlow.SaveCmd save1 = new DraftFlow.SaveCmd();
        setField(save1, "message", "First Commit");
        assertEquals(0, save1.call());

        // 2. Commit modified file
        Files.writeString(testFile, "Line A\nLine B\nLine C Added\n");
        DraftFlow.SaveCmd save2 = new DraftFlow.SaveCmd();
        setField(save2, "message", "Second Commit");
        assertEquals(0, save2.call());

        // Redirect out to verify trace output
        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            DraftFlow.TraceCmd trace = new DraftFlow.TraceCmd();
            setField(trace, "filePath", "blame_test.txt");
            assertEquals(0, trace.call());
        } finally {
            System.setOut(oldOut);
        }

        String output = baos.toString(StandardCharsets.UTF_8);
        // "Line A" should be blamed on First Commit, "Line C Added" should be blamed on Second Commit
        assertTrue(output.contains("First Commit"));
        assertTrue(output.contains("Line A"));
        assertTrue(output.contains("Second Commit"));
        assertTrue(output.contains("Line C Added"));
    }

    private String getPermanentHash(String hash, CAS cas) throws Exception {
        if (hash == null) return null;
        Revision r = (Revision) cas.readObject(hash);
        while (r.isDraft() && !r.getParentHashes().isEmpty()) {
            hash = r.getParentHashes().get(0);
            r = (Revision) cas.readObject(hash);
        }
        return hash;
    }
}
