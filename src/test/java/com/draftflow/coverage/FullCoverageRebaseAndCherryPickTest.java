package com.draftflow.coverage;

import com.draftflow.DraftFlow;
import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.Revision;
import com.draftflow.core.Tree;
import com.draftflow.core.TreeEntry;
import com.draftflow.core.ObjectType;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FullCoverageRebaseAndCherryPickTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private String baseCommitHash;
    private String featureCommitHash;
    private String mainCommitHash;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        // Base Commit
        Blob blob1 = new Blob("base content".getBytes(StandardCharsets.UTF_8));
        String blobHash1 = cas.writeObject(blob1);
        TreeEntry entry1 = new TreeEntry("base.txt", blobHash1, ObjectType.BLOB, 100644);
        Tree tree1 = new Tree(List.of(entry1));
        String treeHash1 = cas.writeObject(tree1);

        Revision baseRev = new Revision(treeHash1, new ArrayList<>(), "base-change", "Author <auth@test.com>", System.currentTimeMillis(), "base commit", false);
        baseCommitHash = cas.writeObject(baseRev);

        // Feature Commit
        Blob blob2 = new Blob("feature content".getBytes(StandardCharsets.UTF_8));
        String blobHash2 = cas.writeObject(blob2);
        TreeEntry entry2 = new TreeEntry("feature.txt", blobHash2, ObjectType.BLOB, 100644);
        Tree tree2 = new Tree(List.of(entry1, entry2));
        String treeHash2 = cas.writeObject(tree2);

        Revision featureRev = new Revision(treeHash2, List.of(baseCommitHash), "feature-change", "Author <auth@test.com>", System.currentTimeMillis() + 1000, "feature commit", false);
        featureCommitHash = cas.writeObject(featureRev);

        // Main Branch Commit
        Blob blob3 = new Blob("main content".getBytes(StandardCharsets.UTF_8));
        String blobHash3 = cas.writeObject(blob3);
        TreeEntry entry3 = new TreeEntry("main.txt", blobHash3, ObjectType.BLOB, 100644);
        Tree tree3 = new Tree(List.of(entry1, entry3));
        String treeHash3 = cas.writeObject(tree3);

        Revision mainRev = new Revision(treeHash3, List.of(baseCommitHash), "main-change", "Author <auth@test.com>", System.currentTimeMillis() + 2000, "main commit", false);
        mainCommitHash = cas.writeObject(mainRev);

        db.setRef("heads/main", mainCommitHash);
        db.setRef("heads/feature", featureCommitHash);
        db.setConfig("activeHead", "heads/feature");
        db.setConfig("activeRevisionHash", featureCommitHash);
        db.commit();
        db.close();
    }

    private void runCli(String... args) {
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "--repo";
        fullArgs[1] = tempDir.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);
        DraftFlow.runMain(fullArgs);
    }

    @Test
    public void testRebaseCmdAllBranches() throws Exception {
        // Linear Rebase feature onto main
        runCli("rebase", "main");

        // Pre-rebase hook failure
        Path hookDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hookDir);
        Path hookFile = hookDir.resolve("pre-rebase.bat");
        Files.writeString(hookFile, "@echo off\nexit 1\n");

        runCli("rebase", "main");
        Files.delete(hookFile);

        // Interactive Rebase
        System.setIn(new ByteArrayInputStream("p\n".getBytes(StandardCharsets.UTF_8)));
        runCli("rebase", "-i", "main");
    }

    @Test
    public void testCherryPickCmdAllBranches() throws Exception {
        runCli("cherry-pick", mainCommitHash);
    }
}
