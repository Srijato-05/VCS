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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FullCoverageCliDeepBranchTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private String commitHash;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        Blob blob = new Blob("deep cli content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "initial", "Author <auth@test.com>", System.currentTimeMillis(), "initial commit", false);
        commitHash = cas.writeObject(rev);

        db.setRef("heads/main", commitHash);
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
    public void testSaveCmdDeepBranches() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "modified content");
        runCli("save", "-m", "save modified", "-a");
        runCli("save", "--allow-empty", "-m", "empty save");
        runCli("save", "-p", "-m", "patch mode save");
        runCli("save");
    }

    @Test
    public void testRebuildIndexCmdDeepBranches() throws Exception {
        runCli("rebuild-index");
        Path dbFile = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        if (Files.exists(dbFile)) {
            Files.writeString(dbFile, "corrupted data");
        }
        runCli("rebuild-index");
    }

    @Test
    public void testResolveCmdDeepBranches() throws Exception {
        runCli("resolve");
        Files.writeString(tempDir.resolve("conflict.txt"), "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\n");
        
        // Choice 1: OURS
        System.setIn(new ByteArrayInputStream("1\n".getBytes(StandardCharsets.UTF_8)));
        runCli("resolve");

        // Choice 2: THEIRS
        Files.writeString(tempDir.resolve("conflict.txt"), "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\n");
        System.setIn(new ByteArrayInputStream("2\n".getBytes(StandardCharsets.UTF_8)));
        runCli("resolve");

        // Choice 3: Manual clean
        Files.writeString(tempDir.resolve("conflict.txt"), "resolved content\n");
        System.setIn(new ByteArrayInputStream("3\n".getBytes(StandardCharsets.UTF_8)));
        runCli("resolve");

        // Choice 4: External
        Files.writeString(tempDir.resolve("conflict.txt"), "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\n");
        System.setIn(new ByteArrayInputStream("4\n".getBytes(StandardCharsets.UTF_8)));
        runCli("resolve");
    }

    @Test
    public void testVerifyCmdDeepBranches() throws Exception {
        runCli("verify");
        runCli("verify", "--repair");
        runCli("verify", "--deep");
    }

    @Test
    public void testKeysCmdDeepBranches() throws Exception {
        runCli("keys", "--list");
        runCli("keys", "--add", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC3 user@test");
        runCli("keys", "--list");
        runCli("keys", "--remove", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC3 user@test");
    }
}
