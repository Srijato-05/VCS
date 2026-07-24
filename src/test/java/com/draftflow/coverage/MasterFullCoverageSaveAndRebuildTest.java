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

public class MasterFullCoverageSaveAndRebuildTest {

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

        Blob blob = new Blob("master save rebuild test".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("master.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "master-initial", "Author <auth@test.com>", System.currentTimeMillis(), "initial master commit", false);
        commitHash = cas.writeObject(rev);

        db.setRef("heads/main", commitHash);
        db.commit();
        db.close();
    }

    private void runCli(String... args) {
        System.clearProperty("draftflow.dir");
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "--repo";
        fullArgs[1] = tempDir.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);
        DraftFlow.runMain(fullArgs);
    }

    @Test
    public void testSaveCmdAllBranches() throws Exception {
        // 1. Save with modified file
        Files.writeString(tempDir.resolve("master.txt"), "modified line 1\nmodified line 2\n");
        runCli("save", "-m", "update master file");

        // 2. Save with deleted file
        Files.delete(tempDir.resolve("master.txt"));
        runCli("save", "-m", "delete master file");

        // 3. Save with new file
        Files.writeString(tempDir.resolve("new_file.txt"), "brand new file content");
        runCli("save", "-m", "add new file");

        // 4. Save with pre-commit hook failure
        Path hookDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hookDir);
        Path hookFile = hookDir.resolve("pre-commit.bat");
        Files.writeString(hookFile, "@echo off\nexit 1\n");
        Files.writeString(tempDir.resolve("new_file.txt"), "content trigger hook fail");
        runCli("save", "-m", "hook fail save");

        // Remove failing hook
        Files.delete(hookFile);

        // 5. Interactive patch mode with simulated user inputs: 'y', 'n', 'q'
        Files.writeString(tempDir.resolve("patch_file.txt"), "patch line 1\npatch line 2\n");
        System.setIn(new ByteArrayInputStream("y\nn\nq\n".getBytes(StandardCharsets.UTF_8)));
        runCli("save", "-p", "-m", "patch save");
    }

    @Test
    public void testRebuildIndexCmdAllBranches() throws Exception {
        // 1. Clean rebuild
        runCli("rebuild-index");

        // 2. Rebuild with corrupt MVStore database file
        Path dbFile = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        if (Files.exists(dbFile)) {
            Files.writeString(dbFile, "CORRUPTED_MVSTORE_BYTES_DATA");
        }
        runCli("rebuild-index");

        // 3. Rebuild with non-existent database file
        if (Files.exists(dbFile)) {
            Files.delete(dbFile);
        }
        runCli("rebuild-index");
    }

    @Test
    public void testStatusAndHistoryAllBranches() throws Exception {
        runCli("status");
        Files.writeString(tempDir.resolve("untracked.txt"), "untracked");
        runCli("status");

        runCli("history");
        runCli("history", "-n", "10");
        runCli("history", "--oneline");
        runCli("history", "--graph");
    }
}
