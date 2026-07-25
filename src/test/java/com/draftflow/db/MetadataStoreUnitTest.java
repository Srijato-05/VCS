package com.draftflow.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataStoreUnitTest {

    @TempDir
    Path tempDir;

    private MetadataStore db;
    private Path dbPath;

    @BeforeEach
    public void setUp() throws Exception {
        Path workDir = tempDir.resolve("db-repo");
        Files.createDirectories(workDir);
        dbPath = workDir.resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();
    }

    @AfterEach
    public void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    public void testFileMetadataPutAndGet() {
        FileMetadata meta = new FileMetadata("f1.txt", 100L, 1000L, "hash1", "BLOB", 100644);
        db.putFile(meta);

        FileMetadata retrieved = db.getFile("f1.txt");
        assertNotNull(retrieved);
        assertEquals("f1.txt", retrieved.getPath());
        assertEquals(100L, retrieved.getSize());
        assertEquals("hash1", retrieved.getHash());
        assertEquals("BLOB", retrieved.getType());
        assertEquals(100644, retrieved.getMode());
    }

    @Test
    public void testFileMetadataRemove() {
        FileMetadata meta = new FileMetadata("f2.txt", 50L, 500L, "hash2", "BLOB", 100644);
        db.putFile(meta);
        assertNotNull(db.getFile("f2.txt"));

        db.removeFile("f2.txt");
        assertNull(db.getFile("f2.txt"));
    }

    @Test
    public void testGetAllFiles() {
        db.putFile(new FileMetadata("a.txt", 10L, 100L, "h1", "BLOB", 100644));
        db.putFile(new FileMetadata("b.txt", 20L, 200L, "h2", "BLOB", 100644));

        List<FileMetadata> list = db.getAllFiles();
        assertEquals(2, list.size());
    }

    @Test
    public void testConfigGetAndSet() {
        db.setConfig("activeHead", "heads/main");
        assertEquals("heads/main", db.getConfig("activeHead"));

        db.removeConfig("activeHead");
        assertNull(db.getConfig("activeHead"));
    }

    @Test
    public void testRefGetAndSet() {
        db.setRef("heads/main", "hash-main-1");
        assertEquals("hash-main-1", db.getRef("heads/main"));

        List<String> refs = db.getRefNames();
        assertTrue(refs.contains("heads/main"));

        db.removeRef("heads/main");
        assertNull(db.getRef("heads/main"));
    }

    @Test
    public void testClearIndex() {
        db.putFile(new FileMetadata("x.txt", 1L, 1L, "hx", "BLOB", 100644));
        db.clearIndex();
        assertTrue(db.getAllFiles().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "file_1.txt", "file_2.txt", "file_3.txt", "file_4.txt", "file_5.txt"
    })
    public void testFileMetadataInsertAndFetch(String filename) {
        String hash = "hash-" + filename.hashCode();
        db.putFile(new FileMetadata(filename, 100L, 200L, hash, "BLOB", 100644));
        FileMetadata meta = db.getFile(filename);
        assertNotNull(meta);
        assertEquals(filename, meta.getPath());
        assertEquals(hash, meta.getHash());
    }
}
