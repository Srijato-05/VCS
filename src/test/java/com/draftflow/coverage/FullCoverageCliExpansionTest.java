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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FullCoverageCliExpansionTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path dbPath;
    private MetadataStore db;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();
        db.setConfig("activeHead", "heads/main");
        db.commit();
    }

    @Test
    public void testRebuildIndexCmdFull() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        try {
            Blob blob = new Blob("rebuild content".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entry = new TreeEntry("file1.txt", blobHash, ObjectType.BLOB, 100644);
            Tree tree = new Tree(List.of(entry));
            String treeHash = cas.writeObject(tree);

            Revision rev = new Revision(treeHash, new ArrayList<>(), "change-1", "author", System.currentTimeMillis(), "msg", false);
            String revHash = cas.writeObject(rev);

            db.setRef("heads/main", revHash);
            db.commit();
            db.close();

            assertEquals(0, DraftFlow.runMain(new String[]{"rebuild-index"}));

            System.setProperty("draftflow.dir", tempDir.resolve("non_existent").toString());
            assertEquals(1, DraftFlow.runMain(new String[]{"rebuild-index"}));
        } finally {
            System.clearProperty("draftflow.dir");
        }
    }

    @Test
    public void testConfigCmdFull() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        try {
            db.close();

            // 1. Set key-value
            assertEquals(0, DraftFlow.runMain(new String[]{"config", "--set", "user.name", "Tester"}));

            // 2. Get key
            assertEquals(0, DraftFlow.runMain(new String[]{"config", "--get", "user.name"}));

            // 3. List keys
            assertEquals(0, DraftFlow.runMain(new String[]{"config", "--list"}));

            // 4. Get non-existent key (returns exit code 1)
            assertEquals(1, DraftFlow.runMain(new String[]{"config", "--get", "non.existent"}));

            // 5. Invalid arguments
            assertEquals(1, DraftFlow.runMain(new String[]{"config"}));

            // 6. Non-existent repo path
            System.setProperty("draftflow.dir", tempDir.resolve("invalid").toString());
            assertEquals(1, DraftFlow.runMain(new String[]{"config", "--get", "user.name"}));
        } finally {
            System.clearProperty("draftflow.dir");
        }
    }

    @Test
    public void testKeysCmdFull() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        try {
            db.close();

            // 1. List keys
            assertEquals(0, DraftFlow.runMain(new String[]{"keys", "--list"}));

            // 2. Add public key
            assertEquals(0, DraftFlow.runMain(new String[]{"keys", "--add", "ssh-rsa AAAAB3NzaC1yc2E..."}));

            // 3. Remove public key
            assertEquals(0, DraftFlow.runMain(new String[]{"keys", "--remove", "ssh-rsa AAAAB3NzaC1yc2E..."}));

            // 4. Non-existent repo path
            System.setProperty("draftflow.dir", tempDir.resolve("invalid").toString());
            assertEquals(1, DraftFlow.runMain(new String[]{"keys", "--list"}));
        } finally {
            System.clearProperty("draftflow.dir");
        }
    }

    @Test
    public void testVerifyCmdFull() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        try {
            Blob blob = new Blob("verify data".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entry = new TreeEntry("f.txt", blobHash, ObjectType.BLOB, 100644);
            Tree tree = new Tree(List.of(entry));
            String treeHash = cas.writeObject(tree);

            Revision rev = new Revision(treeHash, new ArrayList<>(), "change-v", "author", System.currentTimeMillis(), "verify msg", false);
            String revHash = cas.writeObject(rev);

            db.setRef("heads/main", revHash);
            db.commit();
            db.close();

            // 1. Verify active head
            assertEquals(0, DraftFlow.runMain(new String[]{"verify"}));

            // 2. Verify repair
            assertEquals(0, DraftFlow.runMain(new String[]{"verify", "--repair"}));

            // 3. Non-existent repo
            System.setProperty("draftflow.dir", tempDir.resolve("invalid").toString());
            assertEquals(1, DraftFlow.runMain(new String[]{"verify"}));
        } finally {
            System.clearProperty("draftflow.dir");
        }
    }

    @Test
    public void testSaveCmdEdgeCases() throws Exception {
        System.setProperty("draftflow.dir", tempDir.toString());
        try {
            db.close();

            // 1. Save with message
            assertEquals(0, DraftFlow.runMain(new String[]{"save", "-m", "Test save"}));

            // 2. Save with missing message option
            assertEquals(2, DraftFlow.runMain(new String[]{"save"}));

            // 3. Save with non-existent repo path
            System.setProperty("draftflow.dir", tempDir.resolve("invalid").toString());
            assertEquals(1, DraftFlow.runMain(new String[]{"save", "-m", "Save msg"}));
        } finally {
            System.clearProperty("draftflow.dir");
        }
    }
}
