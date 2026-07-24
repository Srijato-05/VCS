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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MasterFullCoverageRebaseInteractiveTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private String commit1;
    private String commit2;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        Blob blob = new Blob("rebase text".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev1 = new Revision(treeHash, new ArrayList<>(), "ch1", "Author <auth@test.com>", System.currentTimeMillis(), "commit 1", false);
        commit1 = cas.writeObject(rev1);

        Revision rev2 = new Revision(treeHash, List.of(commit1), "ch2", "Author <auth@test.com>", System.currentTimeMillis() + 1000, "commit 2", false);
        commit2 = cas.writeObject(rev2);

        db.setRef("heads/main", commit1);
        db.setRef("heads/feature", commit2);
        db.setConfig("activeHead", "heads/feature");
        db.setConfig("activeRevisionHash", commit2);
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
    public void testRebaseInteractiveActions() throws Exception {
        // Test reword action
        System.setIn(new ByteArrayInputStream("r\nnew reworded message\n".getBytes(StandardCharsets.UTF_8)));
        runCli("rebase", "-i", "main");

        // Test squash action
        System.setIn(new ByteArrayInputStream("s\n".getBytes(StandardCharsets.UTF_8)));
        runCli("rebase", "-i", "main");

        // Test drop action
        System.setIn(new ByteArrayInputStream("d\n".getBytes(StandardCharsets.UTF_8)));
        runCli("rebase", "-i", "main");
    }
}
