package com.draftflow.coverage;

import com.draftflow.core.*;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FullCoreAndDatabaseCoverageTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path dbFile;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        dbFile = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
    }

    @Test
    public void testMetadataStoreFullLifecycleAndUserOperations() throws Exception {
        try (MetadataStore store = new MetadataStore(dbFile)) {
            store.open();

            assertFalse(store.isClosed());

            // 1. File Metadata Operations
            FileMetadata meta = new FileMetadata("src/App.java", "hash123", 1024L, System.currentTimeMillis());
            store.putFile(meta);
            assertEquals("hash123", store.getFile("src/App.java").getHash());
            assertEquals(1, store.getAllFiles().size());

            store.removeFile("src/App.java");
            assertNull(store.getFile("src/App.java"));

            store.putFile(meta);
            store.clearIndex();
            assertEquals(0, store.getAllFiles().size());

            // 2. Ref / Branch Operations
            store.setRef("heads/feature", "rev456");
            assertEquals("rev456", store.getRef("heads/feature"));
            assertTrue(store.getRefNames().contains("heads/feature"));
            store.removeRef("heads/feature");
            assertNull(store.getRef("heads/feature"));

            // 3. Change ID Revision History
            store.setChangeRevision("change-1", "rev-001");
            store.setChangeRevision("change-1", "rev-002");
            assertEquals("rev-002", store.getChangeRevision("change-1"));
            List<String> history = store.getChangeHistory("change-1");
            assertEquals(2, history.size());
            assertEquals("rev-001", history.get(0));
            assertEquals("rev-002", history.get(1));

            // 4. Config Operations
            store.setConfig("core.editor", "vim");
            assertEquals("vim", store.getConfig("core.editor"));
            Map<String, String> allConfig = store.getAllConfig();
            assertEquals("vim", allConfig.get("core.editor"));
            store.removeConfig("core.editor");
            assertNull(store.getConfig("core.editor"));

            // 5. User Account Operations
            store.putUser("dev@example.com", "{\"email\":\"dev@example.com\",\"role\":\"admin\"}");
            assertEquals("{\"email\":\"dev@example.com\",\"role\":\"admin\"}", store.getUser("dev@example.com"));
            assertEquals(1, store.getAllUsers().size());

            // 6. Pull Request Operations
            store.putPullRequest("pr-1", "{\"title\":\"Add feature\"}");
            assertEquals("{\"title\":\"Add feature\"}", store.getPullRequest("pr-1"));
            assertEquals(1, store.getAllPullRequests().size());
            store.removePullRequest("pr-1");
            assertNull(store.getPullRequest("pr-1"));

            store.commit();
        }
    }

    @Test
    public void testMetadataStoreCorruptFileRecovery() throws Exception {
        // Create corrupt database file
        Files.createDirectories(dbFile.getParent());
        Files.writeString(dbFile, "CORRUPT INVALID MVSTORE DB CONTENT");

        try (MetadataStore store = new MetadataStore(dbFile)) {
            // Should catch corruption, backup file, and re-initialize cleanly
            assertDoesNotThrow(store::open);
            assertFalse(store.isClosed());
        }
    }

    @Test
    public void testDiagnosticEngineAndCustomErrorCodes() throws Exception {
        DiagnosticEngine engine = new DiagnosticEngine(tempDir);
        engine.log(DiagnosticEngine.LogLevel.INFO, "System started");
        engine.log(DiagnosticEngine.LogLevel.WARN, "High memory usage");
        engine.log(DiagnosticEngine.LogLevel.ERROR, "Database lock acquired");

        // Format diagnostic report with custom exception
        String report = engine.formatDiagnosticReport("CAS_CORRUPTED", new CASCorruptException("CAS hash mismatch"));
        assertTrue(report.contains("DRAFTFLOW VCS DIAGNOSTIC REPORT"));
        assertTrue(report.contains("CAS_CORRUPTED"));
    }

    @Test
    public void testSignatureHelperKeyPairAndVerification() throws Exception {
        SignatureHelper.KeyPairStrings kp = SignatureHelper.generateKeyPairStrings();
        assertNotNull(kp.getPublicKey());
        assertNotNull(kp.getPrivateKey());

        String message = "Revision hash commit data payload";
        String sig = SignatureHelper.sign(message, kp.getPrivateKey());
        assertNotNull(sig);

        boolean isValid = SignatureHelper.verify(message, sig, kp.getPublicKey());
        assertTrue(isValid);

        boolean isInvalid = SignatureHelper.verify("Modified message payload", sig, kp.getPublicKey());
        assertFalse(isInvalid);
    }

    @Test
    public void testReflogManagerEntries() throws Exception {
        ReflogManager manager = new ReflogManager(tempDir);
        manager.record("HEAD", "rev-001", "rev-002", "commit: Add test case");

        List<ReflogManager.ReflogEntry> entries = manager.getLogs("HEAD");
        assertFalse(entries.isEmpty());
        ReflogManager.ReflogEntry latest = entries.get(0);
        assertEquals("rev-001", latest.getOldHash());
        assertEquals("rev-002", latest.getNewHash());
        assertEquals("commit: Add test case", latest.getMessage());
    }

    @Test
    public void testHooksManagerLifecycle() throws Exception {
        HooksManager manager = new HooksManager(tempDir);
        manager.initDefaultHooks();

        assertTrue(manager.getHooksStatus().containsKey("pre-commit"));
        assertFalse(manager.isHookEnabled("pre-commit"));

        manager.setHookEnabled("pre-commit", true);
        assertTrue(manager.isHookEnabled("pre-commit"));

        // Trigger pre-commit hook (sample script returns 0 or mock)
        assertDoesNotThrow(() -> manager.runHook("pre-commit"));
    }

    @Test
    public void testCASWriteReadAndVerify() throws Exception {
        byte[] content = "DraftFlow Binary Content Payload".getBytes();
        Blob blob = new Blob(content);
        String hash = cas.writeObject(blob);

        DraftFlowObject readObj = cas.readObject(hash);
        assertNotNull(readObj);
        assertTrue(readObj instanceof Blob);
        assertArrayEquals(content, ((Blob) readObj).getContent());

        boolean exists = cas.hasObject(hash);
        assertTrue(exists);

        // Verify objects
        List<String> corruptHashes = cas.verifyObjects();
        assertTrue(corruptHashes.isEmpty());
    }

    @Test
    public void testFastCDCAndBinaryDelta() throws Exception {
        byte[] data = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\nLine 7\nLine 8\nLine 9\nLine 10\n".getBytes();
        List<com.draftflow.cdc.FastCDC.Chunk> chunks = com.draftflow.cdc.FastCDC.chunkify(data, 4, 8, 16);
        assertFalse(chunks.isEmpty());

        byte[] src = "The quick brown fox jumps over the lazy dog".getBytes();
        byte[] target = "The quick fast brown fox jumps over the very lazy dog".getBytes();

        byte[] delta = BinaryDelta.createDelta(src, target);
        byte[] restored = BinaryDelta.applyDelta(src, delta);

        assertArrayEquals(target, restored);
    }
}
