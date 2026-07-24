package com.draftflow.coverage;

import com.draftflow.core.CAS;
import com.draftflow.core.ReflogManager;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FullCoverageCoreAndRemoteTest {

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
    }

    @Test
    public void testCasExceptionsAndCorruptData() throws Exception {
        // 1. Read non-existent object
        assertThrows(IOException.class, () -> cas.readObject("0000000000000000000000000000000000000000"));

        // 2. Corrupt object file read
        Path objectPath = tempDir.resolve(".draftflow").resolve("objects").resolve("11").resolve("22334455667788990011223344556677889900");
        Files.createDirectories(objectPath.getParent());
        Files.write(objectPath, "corrupt non-zlib payload".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> cas.readObject("1122334455667788990011223344556677889900"));
    }

    @Test
    public void testMetadataStoreOperationsAndClose() throws Exception {
        db.setConfig("key1", "val1");
        db.commit();
        db.close();

        // Verify clean reopening after close
        MetadataStore reopened = new MetadataStore(dbPath);
        assertDoesNotThrow(reopened::open);
        reopened.close();
    }

    @Test
    public void testReflogManagerEdgeCases() throws Exception {
        // Test missing/empty reflog file read
        List<ReflogManager.ReflogEntry> entries = ReflogManager.getReflog(tempDir);
        assertNotNull(entries);

        // Append entry
        ReflogManager.logTransition(tempDir, "0000000000000000000000000000000000000000", "1111111111111111111111111111111111111111", "Tester", "Initial reflog");
        List<ReflogManager.ReflogEntry> updatedEntries = ReflogManager.getReflog(tempDir);
        assertNotNull(updatedEntries);
    }
}
