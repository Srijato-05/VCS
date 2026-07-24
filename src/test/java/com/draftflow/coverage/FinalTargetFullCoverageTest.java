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

public class FinalTargetFullCoverageTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        Blob blob = new Blob("final test content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "initial", "Author <auth@test.com>", System.currentTimeMillis(), "initial commit", false);
        String commitHash = cas.writeObject(rev);

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
    public void testRebuildIndexCmdComplex() throws Exception {
        // Rebuild on active repository
        runCli("rebuild-index");

        // Create subdirectories and rebuild
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("sub.txt"), "sub content");
        runCli("save", "-m", "add sub");
        runCli("rebuild-index");
    }

    @Test
    public void testSaveCmdLfsAndSubdirs() throws Exception {
        // Create large file to hit LFS threshold
        byte[] largeBytes = new byte[1024 * 1024 * 10]; // 10MB
        Files.write(tempDir.resolve("large.bin"), largeBytes);
        runCli("save", "-m", "add large lfs file");
    }

    @Test
    public void testResolveCmdExternalTool() throws Exception {
        Files.writeString(tempDir.resolve("conflict.txt"), "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\n");
        System.setIn(new ByteArrayInputStream("4\n".getBytes(StandardCharsets.UTF_8)));
        runCli("resolve");
    }
}
