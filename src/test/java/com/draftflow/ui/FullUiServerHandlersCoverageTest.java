package com.draftflow.ui;

import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.ObjectType;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FullUiServerHandlersCoverageTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private UiServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        db.setConfig("activeHead", "heads/main");
        db.setConfig("activeRevisionHash", "rev-root-hash");

        // Write a test blob to CAS
        byte[] blobBytes = "Content of test blob".getBytes(StandardCharsets.UTF_8);
        Blob blob = new Blob(blobBytes);
        String blobHash = cas.writeObject(blob);

        // Put a tracked file in DB
        FileMetadata meta = new FileMetadata("src/main.txt", blobBytes.length, System.currentTimeMillis(), blobHash, ObjectType.BLOB.name(), 0644);
        db.putFile(meta);

        // Put a conflict file in DB
        FileMetadata conflictMeta = new FileMetadata("src/conflict.txt", 100L, System.currentTimeMillis(), "conflict-hash", ObjectType.CONFLICT.name(), 0644);
        db.putFile(conflictMeta);

        db.commit();

        server = new UiServer(cas, db, 0);
        server.start();
        port = server.getPort();

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> req(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
        if ("GET".equalsIgnoreCase(method)) {
            b.GET();
        } else if ("POST".equalsIgnoreCase(method)) {
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
        } else if ("DELETE".equalsIgnoreCase(method)) {
            b.DELETE();
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            b.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testFileContentHandlerEdgeCases() throws Exception {
        // Tracked file content
        HttpResponse<String> r1 = req("GET", "/api/file-content?file=src/main.txt", null);
        assertEquals(200, r1.statusCode());
        assertTrue(r1.body().contains("Content of test blob"));

        // File on disk directly
        Files.createDirectories(tempDir.resolve("disk_file.txt").getParent());
        Files.writeString(tempDir.resolve("disk_file.txt"), "On disk text");
        HttpResponse<String> r2 = req("GET", "/api/file-content?file=disk_file.txt", null);
        assertEquals(200, r2.statusCode());
        assertTrue(r2.body().contains("On disk text"));

        // Non existent file
        HttpResponse<String> r3 = req("GET", "/api/file-content?file=missing.txt", null);
        assertEquals(404, r3.statusCode());
    }

    @Test
    public void testConflictDetailsHandlerEdgeCases() throws Exception {
        // Create actual conflict file with markers on disk
        Path cFile = tempDir.resolve("src/conflict.txt");
        Files.createDirectories(cFile.getParent());
        String conflictText = "<<<<<<< HEAD\nOurs Version\n=======\nTheirs Version\n>>>>>>> branch\n";
        Files.writeString(cFile, conflictText);

        HttpResponse<String> r1 = req("GET", "/api/conflict-details?file=src/conflict.txt", null);
        assertEquals(200, r1.statusCode());
        assertTrue(r1.body().contains("Ours Version"));
        assertTrue(r1.body().contains("Theirs Version"));

        // Non conflict file
        HttpResponse<String> r2 = req("GET", "/api/conflict-details?file=src/main.txt", null);
        assertEquals(404, r2.statusCode());
    }

    @Test
    public void testSyncAndLogoutHandlers() throws Exception {
        HttpResponse<String> r1 = req("POST", "/api/auth/sync", "{\"token\":\"abc\"}");
        assertEquals(200, r1.statusCode());

        HttpResponse<String> r2 = req("POST", "/api/auth/logout", "{}");
        assertEquals(200, r2.statusCode());
    }

    @Test
    public void testDagAndStatusHandlers() throws Exception {
        HttpResponse<String> r1 = req("GET", "/api/dag", null);
        assertEquals(200, r1.statusCode());

        HttpResponse<String> r2 = req("GET", "/api/status", null);
        assertEquals(200, r2.statusCode());
    }

    @Test
    public void testLedgerAndTraceHandlers() throws Exception {
        HttpResponse<String> r1 = req("GET", "/api/ledger", null);
        assertEquals(200, r1.statusCode());

        HttpResponse<String> r2 = req("GET", "/api/trace?file=src/main.txt", null);
        assertEquals(200, r2.statusCode());
    }

    @Test
    public void testActionHandlerActions() throws Exception {
        String oldDir = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());
        try {
            // cmd=status
            HttpResponse<String> r1 = req("POST", "/api/action?cmd=status", null);
            assertEquals(200, r1.statusCode());

            // cmd=history
            HttpResponse<String> r2 = req("POST", "/api/action?cmd=history", null);
            assertEquals(200, r2.statusCode());

            // cmd=branch
            HttpResponse<String> r3 = req("POST", "/api/action?cmd=branch&name=feat-api", null);
            assertEquals(200, r3.statusCode());

            // cmd=switch
            HttpResponse<String> r4 = req("POST", "/api/action?cmd=switch&target=feat-api", null);
            assertEquals(200, r4.statusCode());

            // cmd=clean
            HttpResponse<String> r5 = req("POST", "/api/action?cmd=clean", null);
            assertEquals(200, r5.statusCode());

            // cmd=stash
            HttpResponse<String> r6 = req("POST", "/api/action?cmd=stash&sub=push", null);
            assertEquals(200, r6.statusCode());

            // cmd=verify
            HttpResponse<String> r7 = req("POST", "/api/action?cmd=verify", null);
            assertEquals(200, r7.statusCode());

            // cmd=prune
            HttpResponse<String> r8 = req("POST", "/api/action?cmd=prune", null);
            assertEquals(200, r8.statusCode());

            // Invalid command -> 400
            HttpResponse<String> rErr = req("POST", "/api/action?cmd=invalid_cmd_xyz", null);
            assertEquals(400, rErr.statusCode());
        } finally {
            if (oldDir != null) {
                System.setProperty("draftflow.dir", oldDir);
            } else {
                System.clearProperty("draftflow.dir");
            }
        }
    }
}
