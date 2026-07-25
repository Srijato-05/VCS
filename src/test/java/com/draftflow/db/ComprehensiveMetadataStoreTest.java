package com.draftflow.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveMetadataStoreTest {

    @TempDir
    Path tempDir;

    private MetadataStore.ShutdownHookRegistry mockRegistry = new MetadataStore.ShutdownHookRegistry() {
        @Override
        public void addShutdownHook(Thread hook) {}
        @Override
        public void removeShutdownHook(Thread hook) {}
    };

    @Test
    public void testFileMetadataCacheOperations() throws IOException {
        Path dbPath = tempDir.resolve("metadata.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            db.open();

            // Put files
            FileMetadata f1 = new FileMetadata("a.txt", 10L, 1000L, "hash-a", "BLOB", 0644);
            FileMetadata f2 = new FileMetadata("b.txt", 20L, 2000L, "hash-b", "BLOB", 0644);
            db.putFile(f1);
            db.putFile(f2);

            // Get files
            FileMetadata retrieved1 = db.getFile("a.txt");
            assertNotNull(retrieved1);
            assertEquals("hash-a", retrieved1.getHash());
            assertEquals(10L, retrieved1.getSize());

            // Get all files
            List<FileMetadata> all = db.getAllFiles();
            assertEquals(2, all.size());

            // Remove file
            db.removeFile("a.txt");
            assertNull(db.getFile("a.txt"));

            // Clear index
            db.clearIndex();
            assertTrue(db.getAllFiles().isEmpty());
        }
    }

    @Test
    public void testRefBranchOperations() throws IOException {
        Path dbPath = tempDir.resolve("metadata.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            db.open();

            db.setRef("heads/main", "rev1");
            db.setRef("heads/feat", "rev2");

            assertEquals("rev1", db.getRef("heads/main"));
            assertEquals("rev2", db.getRef("heads/feat"));

            List<String> refs = db.getRefNames();
            assertTrue(refs.contains("heads/main"));
            assertTrue(refs.contains("heads/feat"));

            db.removeRef("heads/feat");
            assertNull(db.getRef("heads/feat"));
        }
    }

    @Test
    public void testChangeHistoryTracking() throws IOException {
        Path dbPath = tempDir.resolve("metadata.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            db.open();

            db.setChangeRevision("c1", "revA");
            assertEquals("revA", db.getChangeRevision("c1"));

            // Append another revision to change c1
            db.setChangeRevision("c1", "revB");
            assertEquals("revB", db.getChangeRevision("c1"));

            List<String> history = db.getChangeHistory("c1");
            assertEquals(2, history.size());
            assertEquals("revA", history.get(0));
            assertEquals("revB", history.get(1));

            // Verify non-existent change history
            assertTrue(db.getChangeHistory("nonexistent").isEmpty());
        }
    }

    @Test
    public void testConfigKeyValueOperations() throws IOException {
        Path dbPath = tempDir.resolve("metadata.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            db.open();

            db.setConfig("activeHead", "heads/main");
            assertEquals("heads/main", db.getConfig("activeHead"));

            db.removeConfig("activeHead");
            assertNull(db.getConfig("activeHead"));
        }
    }

    @Test
    public void testCorruptionRecoveryScenario() throws IOException {
        Path dbPath = tempDir.resolve("corrupted.db");
        Files.createDirectories(dbPath.getParent());
        
        // Write invalid data to simulate MVStore corruption
        Files.writeString(dbPath, "COMPLETELY INVALID DB METADATA CONTENT");

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            
            // Should not crash, but recover by backing up and recreating a fresh database
            assertDoesNotThrow(() -> db.open());

            // Database should be fully operational now
            db.setConfig("test_key", "test_val");
            assertEquals("test_val", db.getConfig("test_key"));
        }
    }

    @Test
    public void testUserAndPullRequestOperations() throws IOException {
        Path dbPath = tempDir.resolve("users_prs.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.shutdownHookRegistry = mockRegistry;
            db.open();

            // Users
            db.putUser("u1@test.com", "{\"name\":\"User 1\"}");
            assertEquals("{\"name\":\"User 1\"}", db.getUser("u1@test.com"));
            assertTrue(db.getAllUsers().size() >= 1);

            // Pull requests
            db.putPullRequest("pr-1", "{\"title\":\"PR 1\"}");
            assertEquals("{\"title\":\"PR 1\"}", db.getPullRequest("pr-1"));
            assertTrue(db.getAllPullRequests().size() >= 1);
            db.removePullRequest("pr-1");
            assertNull(db.getPullRequest("pr-1"));
        }
    }

    @Test
    public void testLifecycleMethods() throws IOException {
        Path dbPath = tempDir.resolve("metadata.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.shutdownHookRegistry = mockRegistry;
        db.open();
        assertFalse(db.isClosed());

        db.close();
        assertTrue(db.isClosed());
    }
}
