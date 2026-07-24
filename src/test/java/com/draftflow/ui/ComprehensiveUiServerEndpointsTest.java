package com.draftflow.ui;

import com.draftflow.core.CAS;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveUiServerEndpointsTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private MetadataStore db;
    private UiServer uiServer;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        Path testFile = tempDir.resolve("sample.txt");
        Files.writeString(testFile, "Hello World Content");

        db.setConfig("activeHead", "heads/main");
        db.commit();

        int port = 49000 + (int) (Math.random() * 8000);
        baseUrl = "http://localhost:" + port;

        uiServer = new UiServer(cas, db, port);
        uiServer.start();

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() {
        if (uiServer != null) {
            uiServer.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-User-Email", "dev@vcs.dev")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendMethod(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");

        if ("DELETE".equalsIgnoreCase(method)) {
            builder.DELETE();
        } else if ("PUT".equalsIgnoreCase(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body));
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testRemoteRefsHandler() throws Exception {
        // 1. GET all refs (empty initially)
        HttpResponse<String> resGet = get("/api/remote/refs");
        assertEquals(200, resGet.statusCode());

        // 2. POST ref
        String refJson = "{\"name\":\"heads/main\",\"hash\":\"abc123def456\"}";
        HttpResponse<String> resPost = post("/api/remote/refs", refJson);
        assertEquals(200, resPost.statusCode());

        // 3. GET specific ref
        HttpResponse<String> resRef = get("/api/remote/refs?name=heads/main");
        assertEquals(200, resRef.statusCode());
        assertTrue(resRef.body().contains("abc123def456"));

        // 4. DELETE ref
        HttpResponse<String> resDel = sendMethod("DELETE", "/api/remote/refs?name=heads/main", "");
        assertEquals(200, resDel.statusCode());

        // 5. Unsupported method
        HttpResponse<String> resPut = sendMethod("PUT", "/api/remote/refs", "{}");
        assertEquals(405, resPut.statusCode());
    }

    @Test
    public void testRemoteIndexHandler() throws Exception {
        // 1. GET remote index
        HttpResponse<String> resGet = get("/api/remote/index");
        assertEquals(200, resGet.statusCode());

        // 2. POST remote index mapping
        String indexBody = "{\"object1\":\"pack1\",\"object2\":\"pack1\"}";
        HttpResponse<String> resPost = post("/api/remote/index", indexBody);
        assertEquals(200, resPost.statusCode());

        // 3. OPTIONS check
        HttpResponse<String> resOpt = sendMethod("OPTIONS", "/api/remote/index", "");
        assertEquals(204, resOpt.statusCode());
    }

    @Test
    public void testRemotePacksHandler() throws Exception {
        // 1. POST upload packfile
        byte[] packData = "dummy binary pack stream data".getBytes(StandardCharsets.UTF_8);
        HttpRequest uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/remote/packs?id=pack-100"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(packData))
                .build();
        HttpResponse<String> resUpload = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resUpload.statusCode());

        // 2. GET download packfile
        HttpResponse<String> resDownload = get("/api/remote/packs?id=pack-100");
        assertEquals(200, resDownload.statusCode());
        assertEquals("dummy binary pack stream data", resDownload.body());

        // 3. GET missing packfile
        HttpResponse<String> resMissing = get("/api/remote/packs?id=pack-nonexistent");
        assertEquals(404, resMissing.statusCode());
    }

    @Test
    public void testAuthSyncAndLogoutHandlers() throws Exception {
        // POST /api/auth/sync
        HttpResponse<String> resSync = post("/api/auth/sync", "{\"repo\":\"main\"}");
        assertEquals(200, resSync.statusCode());

        // POST /api/auth/logout
        HttpResponse<String> resLogout = post("/api/auth/logout", "{}");
        assertEquals(200, resLogout.statusCode());

        // GET /api/auth/logout (Method not allowed)
        HttpResponse<String> resLogoutGet = get("/api/auth/logout");
        assertEquals(405, resLogoutGet.statusCode());
    }

    @Test
    public void testRepositoriesAndCreateHandler() throws Exception {
        // GET /api/repositories
        HttpResponse<String> resRepos = get("/api/repositories");
        assertEquals(200, resRepos.statusCode());

        // POST /api/repositories/create
        String createJson = "{\"name\":\"new-project\",\"description\":\"Sample repo\"}";
        HttpResponse<String> resCreate = post("/api/repositories/create", createJson);
        assertEquals(200, resCreate.statusCode());
    }

    @Test
    public void testFileContentAndConflictDetailsHandlers() throws Exception {
        // GET /api/file-content without params -> 400
        HttpResponse<String> resNoParam = get("/api/file-content");
        assertEquals(400, resNoParam.statusCode());

        // GET /api/file-content with non-existent file -> 404
        HttpResponse<String> res404 = get("/api/file-content?file=missing.txt");
        assertEquals(404, res404.statusCode());

        // GET /api/conflict-details without file -> 400
        HttpResponse<String> resNoFile = get("/api/conflict-details");
        assertEquals(400, resNoFile.statusCode());

        // GET /api/conflict-details with non-existent conflict file -> 404
        HttpResponse<String> resMissingConflict = get("/api/conflict-details?file=missing.txt");
        assertEquals(404, resMissingConflict.statusCode());
    }

    @Test
    public void testPullRequestActionEdgeCases() throws Exception {
        // Close invalid PR
        HttpResponse<String> resCloseErr = post("/api/pull-requests/close", "{\"id\":\"nonexistent-pr\"}");
        assertEquals(404, resCloseErr.statusCode());

        // Merge invalid PR
        HttpResponse<String> resMergeErr = post("/api/pull-requests/merge", "{\"id\":\"nonexistent-pr\"}");
        assertEquals(404, resMergeErr.statusCode());

        // Comment on invalid PR
        HttpResponse<String> resCommentErr = post("/api/pull-requests/comment", "{\"id\":\"nonexistent-pr\",\"comment\":\"test\"}");
        assertEquals(404, resCommentErr.statusCode());
    }

    @Test
    public void testActionHandlerSubcommands() throws Exception {
        String oldDF = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());
        try {
            // Unknown action command -> 400
            HttpResponse<String> resUnknown = post("/api/action?cmd=unknown_cmd_name", "");
            assertEquals(400, resUnknown.statusCode());

            // Status action
            HttpResponse<String> resStatus = post("/api/action?cmd=status", "");
            assertEquals(200, resStatus.statusCode());

            // History action
            HttpResponse<String> resHistory = post("/api/action?cmd=history", "");
            assertEquals(200, resHistory.statusCode());

            // Branch action
            HttpResponse<String> resBranch = post("/api/action?cmd=branch&name=test-br", "");
            assertEquals(200, resBranch.statusCode());

            // Clean action
            HttpResponse<String> resClean = post("/api/action?cmd=clean", "");
            assertEquals(200, resClean.statusCode());

            // Stash action
            HttpResponse<String> resStash = post("/api/action?cmd=stash&sub=push", "");
            assertEquals(200, resStash.statusCode());

            // Keys action
            HttpResponse<String> resKeys = post("/api/action?cmd=keys", "");
            assertEquals(200, resKeys.statusCode());

            // Verify action
            HttpResponse<String> resVerify = post("/api/action?cmd=verify", "");
            assertEquals(200, resVerify.statusCode());

            // Prune action
            HttpResponse<String> resPrune = post("/api/action?cmd=prune", "");
            assertEquals(200, resPrune.statusCode());
        } finally {
            if (oldDF != null) {
                System.setProperty("draftflow.dir", oldDF);
            } else {
                System.clearProperty("draftflow.dir");
            }
        }
    }
}
