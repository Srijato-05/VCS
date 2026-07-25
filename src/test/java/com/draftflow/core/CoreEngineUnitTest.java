package com.draftflow.core;

import com.draftflow.cdc.FastCDC;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import com.draftflow.watcher.FSWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class CoreEngineUnitTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path workDir;

    @BeforeEach
    public void setUp() throws IOException {
        workDir = tempDir.resolve("core-repo");
        Files.createDirectories(workDir);
        cas = new CAS(workDir);
        cas.init();
    }

    // --- 1. CAS Tests ---
    @Test public void testCasInitTwice() throws IOException { cas.init(); assertTrue(Files.exists(cas.getDraftFlowDir())); }
    @Test public void testCasGetters() {
        assertEquals(workDir, cas.getRootDir());
        assertEquals(workDir.resolve(".draftflow"), cas.getDraftFlowDir());
    }

    @Test public void testCasWriteAndReadBlob() throws IOException {
        Blob blob = new Blob("Hello CAS".getBytes(StandardCharsets.UTF_8));
        String hash = cas.writeObject(blob);
        assertTrue(cas.exists(hash));
        DraftFlowObject read = cas.readObject(hash);
        assertEquals(ObjectType.BLOB, read.getType());
        assertArrayEquals("Hello CAS".getBytes(StandardCharsets.UTF_8), ((Blob) read).getContent());
    }

    @Test public void testCasWriteAndReadTree() throws IOException {
        TreeEntry e1 = new TreeEntry("file1.txt", "1234567890123456789012345678901234567890", ObjectType.BLOB, 100644);
        Tree tree = new Tree(Collections.singletonList(e1));
        String hash = cas.writeObject(tree);
        assertTrue(cas.exists(hash));

        Tree readTree = (Tree) cas.readObject(hash);
        assertEquals(1, readTree.getEntries().size());
        assertEquals("file1.txt", readTree.getEntries().get(0).getName());
    }

    @Test public void testCasResolveHashShortPrefix() throws IOException {
        Blob blob = new Blob("Unique Data".getBytes(StandardCharsets.UTF_8));
        String fullHash = cas.writeObject(blob);
        String resolved = cas.resolveHash(fullHash.substring(0, 7));
        assertEquals(fullHash, resolved);
    }

    @Test public void testCasResolveNonExistentHash() throws IOException {
        assertNull(cas.resolveHash("ffffffffffffffff"));
    }

    // --- 2. WorkspaceManager Tests ---
    @Test public void testWorkspaceManagerScanAndShadowCommit() throws Exception {
        Path dbPath = workDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        WorkspaceManager wm = new WorkspaceManager(cas, db);

        Path f1 = workDir.resolve("test.txt");
        Files.writeString(f1, "Sample file content");

        Set<Path> changes = new HashSet<>();
        changes.add(f1);

        String shadowHash = wm.scanAndCreateShadowCommit(changes);
        assertNotNull(shadowHash);
        assertTrue(cas.exists(shadowHash));

        db.close();
    }

    // --- 3. LFSManager Tests ---
    @Test public void testLfsIsLfsFileByExtension() throws IOException {
        DraftFlowConfig config = new DraftFlowConfig();
        Path p = workDir.resolve("image.png");
        Files.writeString(p, "dummy image content");
        assertTrue(LFSManager.isLfsFile(p, config));
    }

    @Test public void testLfsCreateAndParsePointer() throws IOException {
        Path f = workDir.resolve("large.bin");
        Files.writeString(f, "Large file payload");
        String ptrStr = LFSManager.createLfsPointer(workDir, f);
        assertNotNull(ptrStr);
        LFSManager.LfsPointer ptr = LFSManager.parsePointer(ptrStr);
        assertNotNull(ptr);
        assertNotNull(ptr.oid);
    }

    @Test public void testLfsParsePointerInvalid() {
        assertNull(LFSManager.parsePointer("Not an LFS pointer"));
    }

    // --- 4. SignatureHelper Tests ---
    @Test public void testSignatureHelperGenerateAndVerify() throws Exception {
        SignatureHelper.KeyPairStrings pair = SignatureHelper.generateKeyPair();
        assertNotNull(pair.privateKeyBase64);
        assertNotNull(pair.publicKeyBase64);

        byte[] payload = "Sign me".getBytes(StandardCharsets.UTF_8);
        String sig = SignatureHelper.sign(payload, pair.privateKeyBase64);
        assertTrue(SignatureHelper.verify(payload, sig, pair.publicKeyBase64));
        assertFalse(SignatureHelper.verify("Tampered".getBytes(StandardCharsets.UTF_8), sig, pair.publicKeyBase64));
    }

    // --- 5. FastCDC Tests ---
    @Test public void testFastCDCChunking() {
        byte[] data = new byte[32 * 1024];
        Arrays.fill(data, (byte) 'A');
        List<FastCDC.Chunk> chunks = FastCDC.chunk(data);
        assertFalse(chunks.isEmpty());
    }

    // --- 6. FSWatcher Tests ---
    @Test public void testFSWatcherLifecycle() throws Exception {
        final boolean[] triggered = {false};
        FSWatcher watcher = new FSWatcher(workDir, new DraftFlowConfig(), event -> triggered[0] = true);
        watcher.start();
        Thread.sleep(100);
        watcher.stop();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Payload 1", "Payload 2", "Payload 3", "Payload 4", "Payload 5"
    })
    public void testCasWriteBlobs(String payload) throws IOException {
        Blob blob = new Blob(payload.getBytes(StandardCharsets.UTF_8));
        String hash = cas.writeObject(blob);
        assertTrue(cas.exists(hash));
        DraftFlowObject read = cas.readObject(hash);
        assertEquals(ObjectType.BLOB, read.getType());
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), ((Blob) read).getContent());
    }
}
