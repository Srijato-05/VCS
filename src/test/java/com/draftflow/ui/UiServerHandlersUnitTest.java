package com.draftflow.ui;

import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.ConflictNode;
import com.draftflow.core.ObjectType;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class UiServerHandlersUnitTest {

    @TempDir
    Path tempDir;

    private UiServer server;
    private int port;
    private HttpClient client;
    private CAS cas;
    private String originalDraftFlowDir;

    @BeforeEach
    public void setUp() throws IOException {
        originalDraftFlowDir = System.getProperty("draftflow.dir");
        Path workDir = tempDir.resolve("ui-repo");
        Files.createDirectories(workDir);
        System.setProperty("draftflow.dir", workDir.toAbsolutePath().toString());

        cas = new CAS(workDir);
        cas.init();

        Path dbPath = workDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();
        db.setConfig("activeHead", "heads/main");
        db.putPullRequest("pr-123", "{\"id\":\"pr-123\",\"title\":\"PR 123\",\"sourceBranch\":\"feat\",\"targetBranch\":\"main\",\"status\":\"open\"}");
        db.close();

        server = new UiServer(cas, null, 0);
        server.start();
        port = server.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (originalDraftFlowDir != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDir);
        } else {
            System.clearProperty("draftflow.dir");
        }
    }

    private HttpResponse<String> sendGet(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> sendPost(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> sendOptions(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    // --- 1. IndexHandler Tests ---
    @Test public void testIndexGet() throws Exception { assertEquals(200, sendGet("/").statusCode()); }
    @Test public void testIndexOptions() throws Exception { assertEquals(204, sendOptions("/").statusCode()); }
    @Test public void testIndexPostNotAllowed() throws Exception { assertEquals(405, sendPost("/", "").statusCode()); }

    // --- 2. StatusHandler Tests ---
    @Test public void testStatusGet() throws Exception { assertEquals(200, sendGet("/api/status").statusCode()); }
    @Test public void testStatusOptions() throws Exception { assertEquals(204, sendOptions("/api/status").statusCode()); }

    // --- 3. DagHandler Tests ---
    @Test public void testDagGet() throws Exception { assertEquals(200, sendGet("/api/dag").statusCode()); }
    @Test public void testDagOptions() throws Exception { assertEquals(204, sendOptions("/api/dag").statusCode()); }

    // --- 4. LedgerHandler Tests ---
    @Test public void testLedgerGet() throws Exception { assertEquals(200, sendGet("/api/ledger").statusCode()); }
    @Test public void testLedgerOptions() throws Exception { assertEquals(204, sendOptions("/api/ledger").statusCode()); }

    // --- 5. TraceHandler Tests ---
    @Test public void testTraceMissingFile() throws Exception { assertEquals(400, sendGet("/api/trace").statusCode()); }
    @Test public void testTraceNonExistentFile() throws Exception { assertEquals(404, sendGet("/api/trace?file=nonexistent.txt").statusCode()); }
    @Test public void testTraceOptions() throws Exception { assertEquals(204, sendOptions("/api/trace").statusCode()); }

    // --- 6. ConflictDetailsHandler Tests ---
    @Test public void testConflictDetailsMissingFile() throws Exception { assertEquals(400, sendGet("/api/conflict-details").statusCode()); }
    @Test public void testConflictDetailsNonConflict() throws Exception { assertEquals(404, sendGet("/api/conflict-details?file=normal.txt").statusCode()); }
    @Test public void testConflictDetailsOptions() throws Exception { assertEquals(204, sendOptions("/api/conflict-details").statusCode()); }

    // --- 7. FileContentHandler Tests ---
    @Test public void testFileContentMissingParams() throws Exception { assertEquals(400, sendGet("/api/file-content").statusCode()); }
    @Test public void testFileContentNonExistentHash() throws Exception { assertEquals(404, sendGet("/api/file-content?hash=1234567890123456789012345678901234567890").statusCode()); }
    @Test public void testFileContentOptions() throws Exception { assertEquals(204, sendOptions("/api/file-content").statusCode()); }

    // --- 8. ActionHandler Tests ---
    @Test public void testActionGetNotAllowed() throws Exception { assertEquals(405, sendGet("/api/action?cmd=save").statusCode()); }
    @Test public void testActionMissingCmd() throws Exception { assertEquals(400, sendPost("/api/action", "").statusCode()); }
    @Test public void testActionUnknownCmd() throws Exception { assertEquals(400, sendPost("/api/action?cmd=unknown", "").statusCode()); }
    @Test public void testActionSave() throws Exception { assertEquals(200, sendPost("/api/action?cmd=save&msg=Test", "").statusCode()); }
    @Test public void testActionClean() throws Exception { assertEquals(200, sendPost("/api/action?cmd=clean", "").statusCode()); }
    @Test public void testActionStash() throws Exception { assertEquals(200, sendPost("/api/action?cmd=stash", "").statusCode()); }
    @Test public void testActionSwitchMissingTarget() throws Exception { assertEquals(400, sendPost("/api/action?cmd=switch", "").statusCode()); }
    @Test public void testActionRebaseMissingUpstream() throws Exception { assertEquals(400, sendPost("/api/action?cmd=rebase", "").statusCode()); }
    @Test public void testActionOptions() throws Exception { assertEquals(204, sendOptions("/api/action").statusCode()); }

    // --- 9. Auth Endpoints Tests ---
    @Test public void testSignupGetNotAllowed() throws Exception { assertEquals(405, sendGet("/api/auth/signup").statusCode()); }
    @Test public void testSignupOptions() throws Exception { assertEquals(204, sendOptions("/api/auth/signup").statusCode()); }
    @Test public void testSignupPost() throws Exception {
        String json = "{\"username\":\"user1\",\"email\":\"u1@dev.org\",\"password\":\"pass123\"}";
        assertEquals(200, sendPost("/api/auth/signup", json).statusCode());
    }

    @Test public void testLoginGetNotAllowed() throws Exception { assertEquals(405, sendGet("/api/auth/login").statusCode()); }
    @Test public void testLoginOptions() throws Exception { assertEquals(204, sendOptions("/api/auth/login").statusCode()); }
    @Test public void testLoginPost() throws Exception {
        String json = "{\"email\":\"u1@dev.org\",\"password\":\"pass123\"}";
        assertEquals(200, sendPost("/api/auth/login", json).statusCode());
    }

    @Test public void testProfileGet() throws Exception { assertEquals(200, sendGet("/api/auth/profile").statusCode()); }
    @Test public void testProfileOptions() throws Exception { assertEquals(204, sendOptions("/api/auth/profile").statusCode()); }

    @Test public void testSyncGet() throws Exception { assertEquals(200, sendGet("/api/auth/sync").statusCode()); }
    @Test public void testSyncPost() throws Exception { assertEquals(200, sendPost("/api/auth/sync", "{}").statusCode()); }
    @Test public void testSyncOptions() throws Exception { assertEquals(204, sendOptions("/api/auth/sync").statusCode()); }

    @Test public void testLogoutPost() throws Exception { assertEquals(200, sendPost("/api/auth/logout", "").statusCode()); }
    @Test public void testLogoutOptions() throws Exception { assertEquals(204, sendOptions("/api/auth/logout").statusCode()); }

    // --- 10. Pull Requests Endpoints Tests ---
    @Test public void testPullRequestsGet() throws Exception { assertEquals(200, sendGet("/api/pull-requests").statusCode()); }
    @Test public void testPullRequestsPost() throws Exception {
        String json = "{\"title\":\"PR 1\",\"description\":\"Desc\",\"sourceBranch\":\"feat\",\"targetBranch\":\"main\"}";
        assertEquals(200, sendPost("/api/pull-requests", json).statusCode());
    }
    @Test public void testPullRequestsOptions() throws Exception { assertEquals(204, sendOptions("/api/pull-requests").statusCode()); }

    @Test public void testPRMergePost() throws Exception {
        String json = "{\"id\":\"pr-123\"}";
        assertEquals(200, sendPost("/api/pull-requests/merge", json).statusCode());
    }
    @Test public void testPRMergeOptions() throws Exception { assertEquals(204, sendOptions("/api/pull-requests/merge").statusCode()); }

    @Test public void testPRClosePost() throws Exception {
        String json = "{\"id\":\"pr-123\"}";
        assertEquals(200, sendPost("/api/pull-requests/close", json).statusCode());
    }
    @Test public void testPRCloseOptions() throws Exception { assertEquals(204, sendOptions("/api/pull-requests/close").statusCode()); }

    @Test public void testPRCommentPost() throws Exception {
        String json = "{\"id\":\"pr-123\",\"comment\":\"Looks good!\"}";
        assertEquals(200, sendPost("/api/pull-requests/comment", json).statusCode());
    }
    @Test public void testPRCommentOptions() throws Exception { assertEquals(204, sendOptions("/api/pull-requests/comment").statusCode()); }

    // --- 11. Settings & Repositories Endpoints Tests ---
    @Test public void testSettingsGet() throws Exception { assertEquals(200, sendGet("/api/settings").statusCode()); }
    @Test public void testSettingsPost() throws Exception { assertEquals(200, sendPost("/api/settings", "{\"key\":\"val\"}").statusCode()); }
    @Test public void testSettingsOptions() throws Exception { assertEquals(204, sendOptions("/api/settings").statusCode()); }

    @Test public void testRepositoriesGet() throws Exception { assertEquals(200, sendGet("/api/repositories").statusCode()); }
    @Test public void testRepositoriesOptions() throws Exception { assertEquals(204, sendOptions("/api/repositories").statusCode()); }

    @Test public void testCreateRepoPost() throws Exception {
        String json = "{\"name\":\"new-repo\",\"description\":\"Demo repo\"}";
        assertEquals(200, sendPost("/api/repositories/create", json).statusCode());
    }
    @Test public void testCreateRepoOptions() throws Exception { assertEquals(204, sendOptions("/api/repositories/create").statusCode()); }

    // --- 12. Commit Tree & Commit Diff Endpoints Tests ---
    @Test public void testCommitTreeGetMissingHash() throws Exception { assertEquals(200, sendGet("/api/commit-tree").statusCode()); }
    @Test public void testCommitTreeOptions() throws Exception { assertEquals(204, sendOptions("/api/commit-tree").statusCode()); }

    @Test public void testCommitDiffGetMissingHash() throws Exception { assertEquals(500, sendGet("/api/commit-diff").statusCode()); }
    @Test public void testCommitDiffOptions() throws Exception { assertEquals(204, sendOptions("/api/commit-diff").statusCode()); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/api/nonexistent1", "/api/invalid-route", "/api/unknown/path", "/api/v2/foo", "/api/bad-request"
    })
    public void testNonExistentApiRoutes(String route) throws Exception {
        assertEquals(404, sendGet(route).statusCode());
    }
}
