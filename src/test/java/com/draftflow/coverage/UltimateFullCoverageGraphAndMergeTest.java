package com.draftflow.coverage;

import com.draftflow.DraftFlow;
import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.Revision;
import com.draftflow.core.Tree;
import com.draftflow.core.TreeEntry;
import com.draftflow.core.ObjectType;
import com.draftflow.core.SignatureHelper;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class UltimateFullCoverageGraphAndMergeTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private String commitHash1;
    private String commitHash2;
    private String mergeCommitHash;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        // 1. Initial Commit
        Blob blob1 = new Blob("content 1".getBytes(StandardCharsets.UTF_8));
        String blobHash1 = cas.writeObject(blob1);
        TreeEntry entry1 = new TreeEntry("file1.txt", blobHash1, ObjectType.BLOB, 100644);
        Tree tree1 = new Tree(List.of(entry1));
        String treeHash1 = cas.writeObject(tree1);

        Revision rev1 = new Revision(treeHash1, new ArrayList<>(), "change-1", "Author <auth@test.com>", System.currentTimeMillis(), "commit 1", false);
        commitHash1 = cas.writeObject(rev1);

        // 2. Second Commit
        Revision rev2 = new Revision(treeHash1, List.of(commitHash1), "change-2", "Author <auth@test.com>", System.currentTimeMillis() + 1000, "commit 2", false);
        commitHash2 = cas.writeObject(rev2);

        // 3. Signed Commit
        SignatureHelper.KeyPairStrings kp = SignatureHelper.generateKeyPair();
        byte[] signData = rev2.getSigningData();
        String sig = SignatureHelper.sign(signData, kp.privateKeyBase64);
        Revision signedRev = new Revision(treeHash1, List.of(commitHash2), "change-3", "Author <auth@test.com>", System.currentTimeMillis() + 2000, "Signed Commit", false, sig, kp.publicKeyBase64);
        String signedCommitHash = cas.writeObject(signedRev);

        // 4. Merge Commit (Multiple parents)
        Revision mergeRev = new Revision(treeHash1, List.of(signedCommitHash, commitHash1), "change-4", "Author <auth@test.com>", System.currentTimeMillis() + 3000, "Merge Commit", false);
        mergeCommitHash = cas.writeObject(mergeRev);

        db.setRef("heads/main", mergeCommitHash);
        db.setRef("heads/feature", commitHash2);
        db.setConfig("activeHead", "heads/main");
        db.setConfig("activeRevisionHash", mergeCommitHash);
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
    public void testHistoryGraphWithMergeAndSignatures() throws Exception {
        runCli("history");
        
        // Test detached HEAD graph
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();
        db.setConfig("activeHead", "");
        db.setConfig("activeRevisionHash", commitHash1);
        db.commit();
        db.close();

        runCli("history");
    }

    @Test
    public void testBranchCmdAllOptions() throws Exception {
        runCli("branch");
        runCli("branch", "-c", "new-test-branch");
        runCli("branch");
        runCli("branch", "-d", "new-test-branch");
        runCli("branch");
    }
}
