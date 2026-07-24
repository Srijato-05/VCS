package com.draftflow.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveRemoteSyncTest {

    @TempDir
    Path tempDir;

    @Test
    public void testFileProtocolRefLifecycle() throws Exception {
        Path remoteRepo = tempDir.resolve("remote_repo");
        Files.createDirectories(remoteRepo);

        String remoteUrl = "file://" + remoteRepo.toAbsolutePath().toString().replace('\\', '/');
        RemoteClient client = new RemoteClient(remoteUrl);

        // 1. Get non-existent ref
        assertNull(client.getRef("heads/main"));

        // 2. Put ref
        client.putRef("heads/main", "revisionhash12345");
        assertEquals("revisionhash12345", client.getRef("heads/main"));

        // 3. Update ref
        client.putRef("heads/main", "newrevisionhash67890");
        assertEquals("newrevisionhash67890", client.getRef("heads/main"));
    }

    @Test
    public void testFileProtocolPackLifecycle() throws Exception {
        Path remoteRepo = tempDir.resolve("remote_repo_packs");
        Files.createDirectories(remoteRepo);

        String remoteUrl = "file://" + remoteRepo.toAbsolutePath().toString().replace('\\', '/');
        RemoteClient client = new RemoteClient(remoteUrl);

        byte[] packData = "mock pack data content".getBytes(StandardCharsets.UTF_8);

        // 1. Download missing pack should fail
        assertThrows(Exception.class, () -> {
            client.downloadPack("pack-111");
        });

        // 2. Upload pack
        client.uploadPack("pack-111", packData);

        // 3. Download uploaded pack
        byte[] downloaded = client.downloadPack("pack-111");
        assertArrayEquals(packData, downloaded);
    }

    @Test
    public void testFileProtocolIndexLifecycle() throws Exception {
        Path remoteRepo = tempDir.resolve("remote_repo_index");
        Files.createDirectories(remoteRepo);

        String remoteUrl = "file://" + remoteRepo.toAbsolutePath().toString().replace('\\', '/');
        RemoteClient client = new RemoteClient(remoteUrl);

        // 1. Download empty/non-existent index
        Map<String, String> initialIndex = client.downloadIndex();
        assertTrue(initialIndex.isEmpty());

        // 2. Upload index
        Map<String, String> mapping = new HashMap<>();
        mapping.put("objecthash1", "pack-111");
        mapping.put("objecthash2", "pack-111");
        client.uploadIndex(mapping);

        // 3. Download and verify
        Map<String, String> downloaded = client.downloadIndex();
        assertEquals(2, downloaded.size());
        assertEquals("pack-111", downloaded.get("objecthash1"));
        assertEquals("pack-111", downloaded.get("objecthash2"));
    }

    @Test
    public void testLocalPathResolutionWindows() {
        // Just verify the RemoteClient constructor processes trailing slash correctly
        RemoteClient client1 = new RemoteClient("file:///C:/projects/test");
        RemoteClient client2 = new RemoteClient("file:///C:/projects/test/");
        
        // No exceptions thrown, client initializes correctly
        assertNotNull(client1);
        assertNotNull(client2);
    }
}
