package com.draftflow.coverage;

import com.draftflow.core.DraftFlowConfig;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import com.draftflow.remote.OCC;
import com.draftflow.remote.RemoteClient;
import com.draftflow.watcher.FSWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FullCoverageCoreEngineTest {

    @TempDir
    Path tempDir;

    @Test
    public void testFSWatcherLifecycle() throws Exception {
        DraftFlowConfig config = new DraftFlowConfig();
        FSWatcher watcher = new FSWatcher(tempDir, config, new FSWatcher.WatcherListener() {
            @Override
            public void onFilesChanged(Set<Path> changedPaths) {}
        });

        watcher.start();
        Thread.sleep(200);
        watcher.stop();
    }

    @Test
    public void testMetadataStoreFileMetadataAndHookRegistry() throws Exception {
        FileMetadata meta = new FileMetadata("test.txt", 100, System.currentTimeMillis(), "hash123", "BLOB", 100644);
        assertEquals("test.txt", meta.getPath());
        assertEquals("hash123", meta.getHash());
        assertEquals("BLOB", meta.getType());
        assertEquals(100, meta.getSize());
        assertEquals(100644, meta.getMode());

        String json = meta.toJson();
        assertNotNull(json);
        FileMetadata parsed = FileMetadata.fromJson(json);
        assertNotNull(parsed);
        assertEquals("test.txt", parsed.getPath());

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        Thread hookThread = new Thread(() -> {});
        db.shutdownHookRegistry.addShutdownHook(hookThread);
        db.shutdownHookRegistry.removeShutdownHook(hookThread);

        db.close();
    }

    @Test
    public void testRemotePackerAndOcc() throws Exception {
        OCC.ConcurrencyException concEx = new OCC.ConcurrencyException("conflict");
        assertEquals("conflict", concEx.getMessage());

        RemoteClient client = new RemoteClient("http://localhost:9999");
        assertNotNull(client);
    }
}
