package com.draftflow.core;

import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceManagerEdgeCasesTest {

    @TempDir
    Path tempDir;

    @Test
    public void testWorkspaceManagerConflictErrors() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        try {
            ConflictNode conflict = new ConflictNode(null, "nonexistent_left", "nonexistent_right", "conflict.txt");
            String conflictHash = cas.writeObject(conflict);

            TreeEntry entry = new TreeEntry("conflict.txt", conflictHash, ObjectType.CONFLICT, 100644);
            Tree tree = new Tree(List.of(entry));
            String treeHash = cas.writeObject(tree);

            Revision rev = new Revision(treeHash, new ArrayList<>(), "change-1", "author", System.currentTimeMillis(), "msg", false);
            String revHash = cas.writeObject(rev);

            WorkspaceManager wm = new WorkspaceManager(cas, db);

            wm.restoreWorkingCopy(revHash);

            Path conflictFile = tempDir.resolve("conflict.txt");
            assertTrue(Files.exists(conflictFile));
            String content = Files.readString(conflictFile);
            assertTrue(content.contains("[Error reading Left Object]"));
            assertTrue(content.contains("[Error reading Right Object]"));
        } finally {
            db.close();
        }
    }

    @Test
    public void testWorkspaceManagerCheckoutRollback() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        try {
            Blob blob = new Blob("content".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
            Tree tree = new Tree(List.of(entry));
            String treeHash = cas.writeObject(tree);

            Revision rev = new Revision(treeHash, new ArrayList<>(), "change-2", "author", System.currentTimeMillis(), "msg", false);
            String revHash = cas.writeObject(rev);

            Path destDir = tempDir.resolve("file.txt");
            Files.createDirectories(destDir);

            WorkspaceManager wm = new WorkspaceManager(cas, db);

            IOException ex = assertThrows(IOException.class, () -> wm.restoreWorkingCopy(revHash));
            assertTrue(ex.getMessage().contains("Workspace checkout failed partially"));
        } finally {
            db.close();
        }
    }

    @Test
    public void testWorkspaceManagerChunkTreeDiffAndGetFileBytes() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        try {
            Blob c1 = new Blob("Chunk 1 content\n".getBytes(StandardCharsets.UTF_8));
            Blob c2 = new Blob("Chunk 2 content\n".getBytes(StandardCharsets.UTF_8));
            String c1Hash = cas.writeObject(c1);
            String c2Hash = cas.writeObject(c2);

            ChunkTree ct = new ChunkTree(List.of(c1Hash, c2Hash), List.of(16, 16), 32);
            String ctHash = cas.writeObject(ct);

            WorkspaceManager wm = new WorkspaceManager(cas, db);
            java.lang.reflect.Method m = WorkspaceManager.class.getDeclaredMethod("getFileBytes", String.class, ObjectType.class);
            m.setAccessible(true);
            byte[] bytes = (byte[]) m.invoke(wm, ctHash, ObjectType.CHUNK_TREE);
            assertEquals("Chunk 1 content\nChunk 2 content\n", new String(bytes, StandardCharsets.UTF_8));

            try {
                m.invoke(wm, "somehash", ObjectType.TREE);
                fail("Expected exception not thrown");
            } catch (java.lang.reflect.InvocationTargetException ite) {
                assertTrue(ite.getCause() instanceof IOException);
            }
        } catch (Exception e) {
            fail(e);
        } finally {
            db.close();
        }
    }
}
