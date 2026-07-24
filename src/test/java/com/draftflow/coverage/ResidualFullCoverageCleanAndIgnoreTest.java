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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ResidualFullCoverageCleanAndIgnoreTest {

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

        Blob blob = new Blob("tracked content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("tracked.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "ch1", "Author <auth@test.com>", System.currentTimeMillis(), "initial", false);
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
    public void testCleanCmdAllModes() throws Exception {
        // Create untracked file & directory
        Files.writeString(tempDir.resolve("untracked.tmp"), "untracked file");
        Path untrackedDir = tempDir.resolve("untracked_folder");
        Files.createDirectories(untrackedDir);
        Files.writeString(untrackedDir.resolve("inside.txt"), "inside untracked folder");

        // Dry-run clean
        runCli("clean");

        // Force clean files & directories
        runCli("clean", "-f", "-d");

        // Clean ignored files
        Files.writeString(tempDir.resolve(".dfignore"), "*.log\n");
        Files.writeString(tempDir.resolve("app.log"), "log content");
        runCli("clean", "-f", "-x");
    }

    @Test
    public void testIgnoreCmdAllModes() throws Exception {
        Files.writeString(tempDir.resolve(".dfignore"), "*.tmp\n# comment line\n");
        Files.writeString(tempDir.resolve(".gitignore"), "*.log\n");

        runCli("ignore");
        runCli("ignore", "new_pattern*.txt");
        runCli("ignore", "--check", "file.tmp");
        runCli("ignore", "--check", "file.log");
        runCli("ignore", "--check", "tracked.txt");
    }
}
