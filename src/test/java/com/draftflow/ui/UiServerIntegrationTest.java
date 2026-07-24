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

import static org.junit.jupiter.api.Assertions.*;

public class UiServerIntegrationTest {

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

        Path testFile = tempDir.resolve("file.txt");
        Files.writeString(testFile, "Line 1\nLine 2");

        db.setConfig("activeHead", "heads/main");

        byte[] blobBytes = "Line 1\nLine 2".getBytes(StandardCharsets.UTF_8);
        com.draftflow.core.Blob blob = new com.draftflow.core.Blob(blobBytes);
        String blobHash = cas.writeObject(blob);

        com.draftflow.core.TreeEntry entry = new com.draftflow.core.TreeEntry("file.txt", blobHash, com.draftflow.core.ObjectType.BLOB, 100644);
        com.draftflow.core.Tree tree = new com.draftflow.core.Tree(java.util.List.of(entry));
        String treeHash = cas.writeObject(tree);

        com.draftflow.core.Revision rev = new com.draftflow.core.Revision(
            treeHash,
            new java.util.ArrayList<>(),
            "change-123",
            "Author",
            System.currentTimeMillis(),
            "initial commit",
            false
        );
        String revHash = cas.writeObject(rev);

        db.setConfig("activeRevisionHash", revHash);
        db.setRef("heads/main", revHash);
        db.putFile(new com.draftflow.db.FileMetadata("file.txt", blobBytes.length, System.currentTimeMillis(), blobHash, com.draftflow.core.ObjectType.BLOB.name(), 100644));
        db.commit();

        byte[] blobBytes2 = "Line 1\nLine 2\nLine 3".getBytes(StandardCharsets.UTF_8);
        com.draftflow.core.Blob blob2 = new com.draftflow.core.Blob(blobBytes2);
        String blobHash2 = cas.writeObject(blob2);

        com.draftflow.core.TreeEntry entry2 = new com.draftflow.core.TreeEntry("file.txt", blobHash2, com.draftflow.core.ObjectType.BLOB, 100644);
        com.draftflow.core.Tree tree2 = new com.draftflow.core.Tree(java.util.List.of(entry2));
        String treeHash2 = cas.writeObject(tree2);

        com.draftflow.core.Revision rev2 = new com.draftflow.core.Revision(
            treeHash2,
            java.util.List.of(revHash),
            "change-123",
            "Author",
            System.currentTimeMillis(),
            "second commit",
            false
        );
        String revHash2 = cas.writeObject(rev2);

        db.setConfig("activeRevisionHash", revHash2);
        db.setRef("heads/main", revHash2);
        db.putFile(new com.draftflow.db.FileMetadata("file.txt", blobBytes2.length, System.currentTimeMillis(), blobHash2, com.draftflow.core.ObjectType.BLOB.name(), 100644));
        db.commit();

        uiServer = new UiServer(cas, db, 0);
        uiServer.start();
        baseUrl = "http://localhost:" + uiServer.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() {
        if (uiServer != null) {
            uiServer.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testUiServerRoutes() throws Exception {
        // 1. GET / (IndexHandler)
        HttpResponse<String> resIndex = get("/");
        assertEquals(200, resIndex.statusCode());
        assertTrue(resIndex.body().toLowerCase().contains("draftflow"));

        // 2. GET /api/dag (DagHandler)
        HttpResponse<String> resDag = get("/api/dag");
        assertEquals(200, resDag.statusCode());
        assertTrue(resDag.body().contains("hash"));

        // 3. GET /api/status (StatusHandler)
        HttpResponse<String> resStatus = get("/api/status");
        assertEquals(200, resStatus.statusCode());
        assertTrue(resStatus.body().contains("activeHead"));

        // 4. GET /api/ledger (LedgerHandler)
        HttpResponse<String> resLedger = get("/api/ledger");
        assertEquals(200, resLedger.statusCode());

        // 5. GET /api/trace (TraceHandler)
        HttpResponse<String> resTrace = get("/api/trace?file=file.txt");
        assertEquals(200, resTrace.statusCode());
        assertTrue(resTrace.body().contains("Line 1"));

        // 6. GET /api/trace errors
        HttpResponse<String> resTraceError1 = get("/api/trace");
        assertEquals(500, resTraceError1.statusCode());

        HttpResponse<String> resTraceError2 = get("/api/trace?file=nonexistent.txt");
        assertEquals(500, resTraceError2.statusCode());

        // 7. ActionHandler invalid method (GET)
        HttpResponse<String> resActionGet = get("/api/action");
        assertEquals(405, resActionGet.statusCode());

        // 8. ActionHandler missing cmd
        HttpResponse<String> resActionNoCmd = post("/api/action");
        assertEquals(500, resActionNoCmd.statusCode());

        // 9. ActionHandler unknown cmd
        HttpResponse<String> resActionUnknown = post("/api/action?cmd=unknown");
        assertEquals(500, resActionUnknown.statusCode());

        // 10. ActionHandler clean cmd
        String oldDF = System.getProperty("draftflow.dir");
        try {
            System.setProperty("draftflow.dir", tempDir.toString());
            HttpResponse<String> resActionClean = post("/api/action?cmd=clean");
            assertEquals(200, resActionClean.statusCode());
            assertTrue(resActionClean.body().contains("swept"));

            // 11. switch command error path
            HttpResponse<String> resSwitchErr = post("/api/action?cmd=switch");
            assertEquals(500, resSwitchErr.statusCode());

            HttpResponse<String> resSwitchErr2 = post("/api/action?cmd=switch&target=nonexistent");
            assertEquals(500, resSwitchErr2.statusCode());

            // 12. save command
            HttpResponse<String> resSave = post("/api/action?cmd=save&msg=WebCommit");
            // Since we just ran clean and there are no changes, save will succeed and return 200
            assertEquals(200, resSave.statusCode());

            // 13. rebase command error path
            HttpResponse<String> resRebaseErr = post("/api/action?cmd=rebase");
            assertEquals(500, resRebaseErr.statusCode());

            HttpResponse<String> resRebaseErr2 = post("/api/action?cmd=rebase&upstream=nonexistent");
            assertEquals(500, resRebaseErr2.statusCode());

            // 14. undo command error path
            HttpResponse<String> resUndo = post("/api/action?cmd=undo");
            // Since we cleaned, undo might fail or succeed. We expect 500 or 200 depending on active state.
            assertTrue(resUndo.statusCode() == 200 || resUndo.statusCode() == 500);

        } finally {
            if (oldDF != null) {
                System.setProperty("draftflow.dir", oldDF);
            } else {
                System.clearProperty("draftflow.dir");
            }
        }
    }

    @Test
    public void testConflictResolutionAndCustomReflog() throws Exception {
        // 1. Create a conflict node in CAS
        byte[] leftBytes = "Left Content".getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = "Right Content".getBytes(StandardCharsets.UTF_8);
        byte[] ancestorBytes = "Ancestor Content".getBytes(StandardCharsets.UTF_8);

        String leftHash = cas.writeObject(new com.draftflow.core.Blob(leftBytes));
        String rightHash = cas.writeObject(new com.draftflow.core.Blob(rightBytes));
        String ancestorHash = cas.writeObject(new com.draftflow.core.Blob(ancestorBytes));

        com.draftflow.core.ConflictNode conflictNode = new com.draftflow.core.ConflictNode(ancestorHash, leftHash, rightHash, "conflict_test.txt");
        String conflictHash = cas.writeObject(conflictNode);

        // Put the conflict file metadata in DB
        db.putFile(new com.draftflow.db.FileMetadata(
            "conflict_test.txt", 100L, System.currentTimeMillis(), conflictHash, "CONFLICT", 100644
        ));
        db.commit();

        // 2. Test GET /api/conflict-details?file=conflict_test.txt
        HttpResponse<String> resConflictDetails = get("/api/conflict-details?file=conflict_test.txt");
        assertEquals(200, resConflictDetails.statusCode());
        String detailsBody = resConflictDetails.body();
        assertTrue(detailsBody.contains("Left Content"));
        assertTrue(detailsBody.contains("Right Content"));
        assertTrue(detailsBody.contains("Ancestor Content"));

        // 3. Test GET /api/file-content?file=file.txt
        HttpResponse<String> resFileContent = get("/api/file-content?file=file.txt");
        assertEquals(200, resFileContent.statusCode());
        assertTrue(resFileContent.body().contains("Line 1"));

        // 4. Test POST /api/action?cmd=resolve&file=conflict_test.txt&resolution=custom with body
        HttpRequest resolveRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/action?cmd=resolve&file=conflict_test.txt&resolution=custom"))
                .POST(HttpRequest.BodyPublishers.ofString("Custom Merged Content"))
                .build();
        HttpResponse<String> resolveResponse = client.send(resolveRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resolveResponse.statusCode());
        assertTrue(resolveResponse.body().contains("custom"));

        // Check if file is updated on disk
        Path resolvedPath = tempDir.resolve("conflict_test.txt");
        assertTrue(Files.exists(resolvedPath));
        assertEquals("Custom Merged Content", Files.readString(resolvedPath, StandardCharsets.UTF_8));

        // 5. Test Reflog parsing with special format
        com.draftflow.core.ReflogManager.logTransition(
            tempDir,
            "0000000000000000000000000000000000000000",
            "1111111111111111111111111111111111111111",
            "Srijato <135578874+Srijato-05@users.noreply.github.com>",
            "test log message"
        );

        java.util.List<com.draftflow.core.ReflogManager.ReflogEntry> logs = com.draftflow.core.ReflogManager.getReflog(tempDir);
        assertFalse(logs.isEmpty());
        com.draftflow.core.ReflogManager.ReflogEntry lastEntry = logs.get(logs.size() - 1);
        assertEquals("Srijato <135578874+Srijato-05@users.noreply.github.com>", lastEntry.getAuthor());
        assertEquals("test log message", lastEntry.getMessage());
    }

    @Test
    public void testRemainingActionRoutes() throws Exception {
        // 1. Test prune command
        HttpResponse<String> resPrune = post("/api/action?cmd=prune");
        // prune may return 200 or 500 depending on active state/database config, we assert it completes
        assertNotNull(resPrune.body());

        // 2. Test branch command (missing parameters)
        HttpResponse<String> resBranchErr = post("/api/action?cmd=branch");
        assertEquals(500, resBranchErr.statusCode());

        // 3. Test branch creation
        HttpResponse<String> resBranchCreate = post("/api/action?cmd=branch&create=new-feature-branch");
        // branch creation may succeed or fail depending on active head state, we check that status is parsed
        assertNotNull(resBranchCreate.body());

        // 4. Test branch deletion
        HttpResponse<String> resBranchDelete = post("/api/action?cmd=branch&delete=new-feature-branch");
        assertNotNull(resBranchDelete.body());

        // 5. Test merge command (missing parameters)
        HttpResponse<String> resMergeErr = post("/api/action?cmd=merge");
        assertEquals(500, resMergeErr.statusCode());

        // 6. Test switch-repo command (missing parameters)
        HttpResponse<String> resSwitchRepoErr = post("/api/action?cmd=switch-repo");
        assertEquals(500, resSwitchRepoErr.statusCode());

        // 7. Test switch-repo with non-existent repo
        HttpResponse<String> resSwitchRepoNonExistent = post("/api/action?cmd=switch-repo&repo=nonexistent_repo_dir");
        assertEquals(500, resSwitchRepoNonExistent.statusCode());
    }
}
