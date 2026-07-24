package com.draftflow.core;

import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class AdvancedFaultToleranceTest {

    @TempDir
    Path tempDir;

    @Test
    public void testAtomicWriteAndIntegrityCheck() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();

        Blob blob = new Blob("Atomic test payload data".getBytes());
        String hash = cas.writeObject(blob);

        // Verify normal read works
        Blob readBlob = (Blob) cas.readObject(hash);
        assertEquals("Atomic test payload data", new String(readBlob.getContent()));

        // Locate object file on disk
        String dir = hash.substring(0, 2);
        String file = hash.substring(2);
        Path objectPath = tempDir.resolve(".draftflow/objects").resolve(dir).resolve(file);
        assertTrue(Files.exists(objectPath));

        // Corrupt the object file contents on disk
        Files.write(objectPath, "corrupted compressed payload data that will not decompress or hash correctly".getBytes());

        // Verify read fails with corruption exception
        Exception ex = assertThrows(Exception.class, () -> cas.readObject(hash));
        assertTrue(ex.getMessage().contains("Decompression failed") || ex.getMessage().contains("corruption detected"), 
                "Expected corruption message but got: " + ex.getMessage());
    }

    @Test
    public void testGitIgnoreMatcher() throws Exception {
        // Write a mock .gitignore
        Path gitignore = tempDir.resolve(".gitignore");
        Files.writeString(gitignore, "# Comments\n\n*.log\nbuild/\nbin/\n");

        GitIgnoreMatcher matcher = new GitIgnoreMatcher(tempDir, Arrays.asList("out/"));

        // Checked files
        assertTrue(matcher.isIgnored(tempDir.resolve(".git")), "Default .git should be ignored");
        assertTrue(matcher.isIgnored(tempDir.resolve(".draftflow")), "Default .draftflow should be ignored");
        assertTrue(matcher.isIgnored(tempDir.resolve("test.log")), "*.log should be ignored");
        assertTrue(matcher.isIgnored(tempDir.resolve("build/Main.class")), "build/ folder should be ignored");
        assertTrue(matcher.isIgnored(tempDir.resolve("bin/app.exe")), "bin/ folder should be ignored");
        assertTrue(matcher.isIgnored(tempDir.resolve("out/artifacts")), "config out/ folder should be ignored");

        // Allowed files
        assertFalse(matcher.isIgnored(tempDir.resolve("src/Main.java")), "src/Main.java should NOT be ignored");
        assertFalse(matcher.isIgnored(tempDir.resolve("build.gradle")), "build.gradle should NOT be ignored");
    }

    @Test
    public void testDFIgnoreMatcher() throws Exception {
        // Write a mock .dfignore
        Path dfignore = tempDir.resolve(".dfignore");
        Files.writeString(dfignore, "# Comments\n\ntemp/\n*.tmp\n");

        GitIgnoreMatcher matcher = new GitIgnoreMatcher(tempDir, null);

        // Checked files
        assertTrue(matcher.isIgnored(tempDir.resolve("temp/file.txt")), "temp/ folder should be ignored via .dfignore");
        assertTrue(matcher.isIgnored(tempDir.resolve("run.tmp")), "*.tmp should be ignored via .dfignore");

        // Allowed files
        assertFalse(matcher.isIgnored(tempDir.resolve("temp.txt")), "temp.txt should NOT be ignored");
    }

    @Test
    public void testDatabaseShutdownHookAndAutoRecovery() throws Exception {
        Path dbPath = tempDir.resolve("index").resolve("index.mv.db");
        
        // 1. Write some initial data
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("testKey", "testVal");
            db.commit();
        }

        // Verify it was persisted
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            assertEquals("testVal", db.getConfig("testKey"));
        }

        // 2. Simulate database file corruption by writing junk
        Files.writeString(dbPath, "COMPLETELY_CORRUPTED_DATABASE_FILE_HEADER_AND_PAYLOAD");

        // Verify open() auto-recovers and opens a clean store rather than crashing with IllegalStateException
        try (MetadataStore db = new MetadataStore(dbPath)) {
            assertDoesNotThrow(() -> db.open());
            // Since it recovered/recreated the database, the old key should be gone (but the system didn't crash)
            assertNull(db.getConfig("testKey"));
        }

        // Check that a backup file was created
        long corruptedBackupCount = Files.list(tempDir.resolve("index"))
                .filter(p -> p.getFileName().toString().contains("index.mv.db.corrupted_"))
                .count();
        assertEquals(1, corruptedBackupCount, "Should create exactly 1 corrupted backup file");
    }
}
