package com.draftflow.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

public class CasEdgeCasesTest {

    @TempDir
    Path tempDir;

    @Test
    public void testCasIntegrityAndDecompressionErrors() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        // 1. Invalid compressed data to trigger decompression catch block
        String fakeHash1 = "1122334455667788990011223344556677889900112233445566778899001122";
        Path objPath1 = tempDir.resolve(".draftflow").resolve("objects").resolve("11").resolve("22334455667788990011223344556677889900112233445566778899001122");
        Files.createDirectories(objPath1.getParent());
        Files.write(objPath1, new byte[]{1, 2, 3, 4}); 
        
        IOException ex1 = assertThrows(IOException.class, () -> cas.readObject(fakeHash1));
        assertTrue(ex1.getMessage().contains("Decompression failed"));

        // 2. CAS corruption / hash mismatch
        byte[] validDecompressed = "BLOB 5\0hello".getBytes();
        byte[] compressed = Compressor.compress(validDecompressed);
        String fakeHash2 = "2222334455667788990011223344556677889900112233445566778899001122";
        Path objPath2 = tempDir.resolve(".draftflow").resolve("objects").resolve("22").resolve("22334455667788990011223344556677889900112233445566778899001122");
        Files.createDirectories(objPath2.getParent());
        Files.write(objPath2, compressed);

        IOException ex2 = assertThrows(IOException.class, () -> cas.readObject(fakeHash2));
        assertTrue(ex2.getMessage().contains("CAS data corruption detected"));

        // 3. Corrupt header (missing space or null)
        byte[] corruptHeaderBytes = "BLOB5hello".getBytes(); 
        byte[] compressedCorrupt = Compressor.compress(corruptHeaderBytes);
        String corruptHash = Hasher.hash(corruptHeaderBytes);
        Path objPathCorrupt = tempDir.resolve(".draftflow").resolve("objects").resolve(corruptHash.substring(0, 2)).resolve(corruptHash.substring(2));
        Files.createDirectories(objPathCorrupt.getParent());
        Files.write(objPathCorrupt, compressedCorrupt);

        IOException ex3 = assertThrows(IOException.class, () -> cas.readObject(corruptHash));
        assertTrue(ex3.getMessage().contains("Corrupt object header"));

        // 4. Invalid Object Type
        byte[] invalidTypeBytes = "INVALIDTYPE 5\0hello".getBytes();
        byte[] compressedInvalidType = Compressor.compress(invalidTypeBytes);
        String invalidTypeHash = Hasher.hash(invalidTypeBytes);
        Path objPathInvalidType = tempDir.resolve(".draftflow").resolve("objects").resolve(invalidTypeHash.substring(0, 2)).resolve(invalidTypeHash.substring(2));
        Files.createDirectories(objPathInvalidType.getParent());
        Files.write(objPathInvalidType, compressedInvalidType);

        assertThrows(Exception.class, () -> cas.readObject(invalidTypeHash));
    }

    @Test
    public void testCasResolveHashEdgeCases() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        // 1. resolveHash invalid inputs
        assertNull(cas.resolveHash(null));
        assertNull(cas.resolveHash("abc"));

        // 2. resolveHash non-existent prefix
        assertNull(cas.resolveHash("ffffffff"));

        // 3. resolveHash matches > 1 (ambiguity)
        byte[] b1 = "BLOB 2\0x1".getBytes();
        byte[] b2 = "BLOB 2\0x2".getBytes();
        String hash1 = "aabbcc1111111111111111111111111111111111111111111111111111111111";
        String hash2 = "aabbcc2222222222222222222222222222222222222222222222222222222222";

        Path p1 = tempDir.resolve(".draftflow").resolve("objects").resolve(hash1.substring(0, 2)).resolve(hash1.substring(2));
        Path p2 = tempDir.resolve(".draftflow").resolve("objects").resolve(hash2.substring(0, 2)).resolve(hash2.substring(2));
        Files.createDirectories(p1.getParent());
        Files.write(p1, Compressor.compress(b1));
        Files.write(p2, Compressor.compress(b2));

        assertNull(cas.resolveHash("aabbcc"));
    }

    @Test
    public void testCasConfigCorrupted() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path configPath = tempDir.resolve(".draftflow").resolve("config.json");
        Files.writeString(configPath, "{invalid json");

        DraftFlowConfig config = cas.getConfig();
        assertNotNull(config);
        assertEquals("1.0", config.getVersion());
    }

    @Test
    public void testCasLockTimeout() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path lockFile = tempDir.resolve(".draftflow").resolve("index.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
             FileLock lock = channel.tryLock()) {
            
            assertNotNull(lock);
            boolean acquired = cas.tryAcquireLock(50);
            assertFalse(acquired);

            assertThrows(IOException.class, () -> cas.acquireLock());
        }
    }

    @Test
    public void testCasWriteBlobDeltaFallback() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        byte[] baseContent = "This is a base blob content that is somewhat long.".getBytes();
        Blob baseBlob = new Blob(baseContent);
        String baseHash = cas.writeObject(baseBlob);

        byte[] newContent = "This is a base blob content that is somewhat long. Plus one small change.".getBytes();
        String newHash = cas.writeBlobWithDelta(newContent, baseHash);

        DraftFlowObject obj = cas.readObject(newHash);
        assertTrue(obj instanceof Blob);
    }
}
