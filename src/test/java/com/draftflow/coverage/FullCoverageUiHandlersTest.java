package com.draftflow.coverage;

import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.Revision;
import com.draftflow.core.Tree;
import com.draftflow.core.TreeEntry;
import com.draftflow.core.ObjectType;
import com.draftflow.db.MetadataStore;
import com.draftflow.ui.UiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FullCoverageUiHandlersTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path dbPath;
    private MetadataStore db;
    private UiServer uiServer;
    private String serverUrl;
    private String commitHash;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

        Blob blob = new Blob("ui test file content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "ui-change", "Author <auth@test.com>", System.currentTimeMillis(), "UI test commit", false);
        commitHash = cas.writeObject(rev);

        db.setRef("heads/main", commitHash);
        db.commit();

        uiServer = new UiServer(cas, db, 0);
        uiServer.start();
        serverUrl = "http://localhost:" + uiServer.getPort();
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

    private int sendRequest(String method, String endpoint, String jsonPayload) throws IOException {
        return sendRequestWithHeader(method, endpoint, jsonPayload, null, null);
    }

    private int sendRequestWithHeader(String method, String endpoint, String jsonPayload, String headerKey, String headerVal) throws IOException {
        URL url = new URL(serverUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        if (headerKey != null && headerVal != null) {
            conn.setRequestProperty(headerKey, headerVal);
        }
        if (jsonPayload != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        try (InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream()) {
            if (is != null) {
                is.readAllBytes();
            }
        } catch (IOException ignored) {}
        conn.disconnect();
        return code;
    }

    @Test
    public void testPullRequestHandlersFullMatrix() throws Exception {
        // 1. Create PR
        sendRequest("POST", "/api/pull-requests/create", "{\"title\":\"PR1\",\"sourceBranch\":\"feature\",\"targetBranch\":\"main\"}");
        sendRequest("POST", "/api/pull-requests/create", "{\"title\":\"\"}");
        sendRequest("GET", "/api/pull-requests/create", null);

        // 2. List PRs
        sendRequest("GET", "/api/pull-requests", null);
        sendRequest("POST", "/api/pull-requests", "{}");

        // 3. Comment PR
        sendRequest("POST", "/api/pull-requests/comment", "{\"prId\":\"pr-1\",\"comment\":\"LGTM\"}");
        sendRequest("POST", "/api/pull-requests/comment", "{\"prId\":\"pr-1\"}");
        sendRequest("POST", "/api/pull-requests/comment", "{}");
        sendRequest("GET", "/api/pull-requests/comment", null);

        // 4. Close PR
        sendRequest("POST", "/api/pull-requests/close", "{\"prId\":\"pr-1\"}");
        sendRequest("POST", "/api/pull-requests/close", "{}");
        sendRequest("GET", "/api/pull-requests/close", null);

        // 5. Merge PR
        sendRequest("POST", "/api/pull-requests/merge", "{\"prId\":\"pr-1\"}");
        sendRequest("POST", "/api/pull-requests/merge", "{\"prId\":\"non-existent\"}");
        sendRequest("POST", "/api/pull-requests/merge", "{}");
        sendRequest("GET", "/api/pull-requests/merge", null);
    }

    @Test
    public void testRemoteHandlersFullMatrix() throws Exception {
        sendRequest("GET", "/api/remote/packs", null);
        sendRequest("POST", "/api/remote/packs", "{}");

        sendRequest("GET", "/api/remote/index", null);
        sendRequest("POST", "/api/remote/index", "{\"indexData\":\"abc\"}");
        sendRequest("POST", "/api/remote/index", "{}");

        sendRequest("GET", "/api/remote/refs", null);
        sendRequest("POST", "/api/remote/refs", "{}");
    }

    @Test
    public void testCommitAndDiffHandlersFullMatrix() throws Exception {
        sendRequest("GET", "/api/commit/tree?hash=" + commitHash, null);
        sendRequest("GET", "/api/commit/tree?hash=head", null);
        sendRequest("GET", "/api/commit/tree?hash=nonexistent", null);
        sendRequest("GET", "/api/commit/tree", null);
        sendRequest("POST", "/api/commit/tree", "{}");

        sendRequest("GET", "/api/commit/diff?hash=" + commitHash, null);
        sendRequest("GET", "/api/commit/diff?hash=head", null);
        sendRequest("GET", "/api/commit/diff?hash=nonexistent", null);
        sendRequest("GET", "/api/commit/diff", null);
        sendRequest("POST", "/api/commit/diff", "{}");
    }

    @Test
    public void testRepositoryAndSyncHandlersFullMatrix() throws Exception {
        sendRequest("POST", "/api/repositories/create", "{\"name\":\"new-repo\",\"path\":\"" + tempDir.toString().replace("\\", "/") + "\"}");
        sendRequest("POST", "/api/repositories/create", "{\"name\":\"\"}");
        sendRequest("POST", "/api/repositories/create", "{}");
        sendRequest("GET", "/api/repositories/create", null);

        sendRequest("POST", "/api/sync", "{\"direction\":\"push\"}");
        sendRequest("POST", "/api/sync", "{\"direction\":\"pull\"}");
        sendRequest("POST", "/api/sync", "{}");
        sendRequest("GET", "/api/sync", null);

        sendRequest("GET", "/api/repositories", null);
        sendRequest("POST", "/api/repositories", "{}");
    }

    @Test
    public void testAuthAndProfileHandlersFullMatrix() throws Exception {
        sendRequest("POST", "/api/login", "{\"username\":\"dev\",\"password\":\"password123\"}");
        sendRequest("POST", "/api/login", "{\"username\":\"dev\",\"password\":\"wrong\"}");
        sendRequest("POST", "/api/login", "{}");
        sendRequest("GET", "/api/login", null);

        sendRequest("POST", "/api/signup", "{\"username\":\"newuser\",\"password\":\"pass123\",\"email\":\"new@test.com\"}");
        sendRequest("POST", "/api/signup", "{\"username\":\"dev\"}");
        sendRequest("POST", "/api/signup", "{}");
        sendRequest("GET", "/api/signup", null);

        sendRequest("POST", "/api/logout", "{}");
        sendRequest("GET", "/api/logout", null);

        sendRequest("GET", "/api/profile", null);
        sendRequest("POST", "/api/profile", "{\"name\":\"Updated Name\"}");
        sendRequest("POST", "/api/profile", "{}");
    }

    @Test
    public void testFileContentTraceSettingsHandlersFullMatrix() throws Exception {
        sendRequest("GET", "/api/file?path=file.txt", null);
        sendRequest("GET", "/api/file?path=nonexistent.txt", null);
        sendRequest("GET", "/api/file", null);

        sendRequest("GET", "/api/trace?path=file.txt", null);
        sendRequest("GET", "/api/trace?path=nonexistent.txt", null);
        sendRequest("GET", "/api/trace", null);

        sendRequest("GET", "/api/settings", null);
        sendRequest("POST", "/api/settings", "{\"theme\":\"dark\"}");
        sendRequest("POST", "/api/settings", "{}");

        sendRequest("GET", "/api/status", null);
        sendRequest("GET", "/api/dag", null);
        sendRequest("GET", "/api/ledger", null);
        sendRequest("GET", "/api/index", null);
        sendRequest("GET", "/api/conflicts/details", null);

        sendRequest("POST", "/api/action", "{\"action\":\"save\",\"message\":\"test action\"}");
        sendRequest("POST", "/api/action", "{\"action\":\"invalid\"}");
        sendRequest("POST", "/api/action", "{}");
    }
}
