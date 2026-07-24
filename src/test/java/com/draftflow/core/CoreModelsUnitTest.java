package com.draftflow.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class CoreModelsUnitTest {

    @TempDir
    Path tempDir;

    @Test
    public void testBlobModel() {
        byte[] data = "Hello World Data".getBytes(StandardCharsets.UTF_8);
        Blob blob = new Blob(data);
        assertEquals(ObjectType.BLOB, blob.getType());
        assertArrayEquals(data, blob.getContent());
        assertNotNull(blob.serialize());
    }

    @Test
    public void testTreeEntryAndTreeModel() {
        TreeEntry entry1 = new TreeEntry("file.txt", "hash-1", ObjectType.BLOB, 0644);
        assertEquals("file.txt", entry1.getName());
        assertEquals("hash-1", entry1.getHash());
        assertEquals(ObjectType.BLOB, entry1.getType());
        assertEquals(0644, entry1.getMode());

        TreeEntry entry2 = new TreeEntry("src", "hash-2", ObjectType.TREE, 0755);

        Tree tree = new Tree(Arrays.asList(entry1, entry2));
        assertEquals(ObjectType.TREE, tree.getType());
        assertEquals(2, tree.getEntries().size());

        byte[] serialized = tree.serialize();
        Tree deserialized = Tree.deserialize(serialized);
        assertEquals(2, deserialized.getEntries().size());
        assertEquals("file.txt", deserialized.getEntries().get(0).getName());
    }

    @Test
    public void testRevisionModel() {
        Revision rev = new Revision(
                "tree-hash",
                Arrays.asList("parent-1", "parent-2"),
                "change-id-123",
                "Author <author@dev.org>",
                123456789L,
                "Commit message",
                false,
                "sig-base64",
                "pub-base64"
        );

        assertEquals(ObjectType.REVISION, rev.getType());
        assertEquals("tree-hash", rev.getTreeHash());
        assertEquals(2, rev.getParentHashes().size());
        assertEquals("change-id-123", rev.getChangeId());
        assertEquals("Author <author@dev.org>", rev.getAuthor());
        assertEquals(123456789L, rev.getTimestamp());
        assertEquals("Commit message", rev.getMessage());
        assertFalse(rev.isDraft());
        assertEquals("sig-base64", rev.getSignature());
        assertEquals("pub-base64", rev.getPublicKey());

        byte[] serialized = rev.serialize();
        Revision deserialized = Revision.deserialize(serialized);
        assertEquals("tree-hash", deserialized.getTreeHash());
        assertEquals("change-id-123", deserialized.getChangeId());
        assertNotNull(deserialized.getSigningData());
    }

    @Test
    public void testDraftFlowConfigModel() {
        DraftFlowConfig config = new DraftFlowConfig();
        assertEquals("1.0", config.getVersion());
        assertEquals("SHA-256", config.getHashAlgorithm());
        assertNotNull(config.getExclude());
        assertNotNull(config.getLfsExtensions());
        assertNotNull(config.getLfsSizeThreshold());
    }

    @Test
    public void testCASOperations() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();
        assertNotNull(cas.getDraftFlowDir());
        assertNotNull(cas.getRootDir());

        Blob blob = new Blob("Test CAS Data".getBytes(StandardCharsets.UTF_8));
        String hash = cas.writeObject(blob);
        assertNotNull(hash);

        assertTrue(cas.exists(hash));
        DraftFlowObject readObj = cas.readObject(hash);
        assertEquals(ObjectType.BLOB, readObj.getType());

        assertNotNull(cas.resolveHash(hash.substring(0, 8)));
    }

    @Test
    public void testExceptionsAndCompressor() throws IOException {
        CASCorruptException ex1 = new CASCorruptException("Corrupt object hash", Collections.singletonList("Fix suggestion"));
        assertEquals("Corrupt object hash", ex1.getMessage());
        assertEquals(1, ex1.getSuggestions().size());

        LockContentionException ex2 = new LockContentionException("lock.file", new IOException("Already locked"));
        assertEquals("lock.file", ex2.getMessage());
        assertNotNull(ex2.getCause());

        byte[] raw = "Sample raw payload for compressor test".getBytes(StandardCharsets.UTF_8);
        byte[] comp = Compressor.compress(raw);
        byte[] decomp = Compressor.decompress(comp);
        assertArrayEquals(raw, decomp);
    }
}
