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

public class FullCoverageUiHandlersExpansionTest {

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

        Blob blob = new Blob("expansion test file content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "ui-expansion", "Author <auth@test.com>", System.currentTimeMillis(), "UI expansion commit", false);
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
        URL url = new URL(serverUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
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
    public void testRemotePacksHandlerDeep() throws Exception {
        sendRequest("GET", "/api/remote/packs", null);
        sendRequest("GET", "/api/remote/packs?pack=pack-123", null);
        sendRequest("POST", "/api/remote/packs", "{\"packData\":\"abc\"}");
        sendRequest("POST", "/api/remote/packs", "invalid json");
        sendRequest("PUT", "/api/remote/packs", null);
    }

    @Test
    public void testRemoteIndexHandlerDeep() throws Exception {
        sendRequest("GET", "/api/remote/index", null);
        sendRequest("POST", "/api/remote/index", "{\"indexData\":\"xyz\"}");
        sendRequest("POST", "/api/remote/index", "malformed json");
        sendRequest("DELETE", "/api/remote/index", null);
    }

    @Test
    public void testRemoteRefsHandlerDeep() throws Exception {
        sendRequest("GET", "/api/remote/refs", null);
        sendRequest("GET", "/api/remote/refs?branch=main", null);
        sendRequest("POST", "/api/remote/refs", "{\"ref\":\"refs/heads/feature\",\"hash\":\"" + commitHash + "\"}");
        sendRequest("POST", "/api/remote/refs", "bad json");
        sendRequest("PUT", "/api/remote/refs", null);
    }

    @Test
    public void testSyncHandlerDeep() throws Exception {
        sendRequest("GET", "/api/sync", null);
        sendRequest("POST", "/api/sync", "{\"direction\":\"push\",\"remoteUrl\":\"http://remote.com\"}");
        sendRequest("POST", "/api/sync", "{\"direction\":\"pull\",\"remoteUrl\":\"http://remote.com\"}");
        sendRequest("POST", "/api/sync", "{\"direction\":\"unknown\"}");
        sendRequest("POST", "/api/sync", "corrupt body");
        sendRequest("DELETE", "/api/sync", null);
    }

    @Test
    public void testCommitTreeHandlerDeep() throws Exception {
        sendRequest("GET", "/api/commit/tree?hash=" + commitHash, null);
        sendRequest("GET", "/api/commit/tree?hash=invalidhash", null);
        sendRequest("GET", "/api/commit/tree", null);
        sendRequest("POST", "/api/commit/tree", "{}");
        sendRequest("DELETE", "/api/commit/tree", null);
    }

    @Test
    public void testCommitDiffHandlerDeep() throws Exception {
        sendRequest("GET", "/api/commit/diff?hash=" + commitHash, null);
        sendRequest("GET", "/api/commit/diff?hash=invalidhash", null);
        sendRequest("GET", "/api/commit/diff", null);
        sendRequest("POST", "/api/commit/diff", "{}");
        sendRequest("PUT", "/api/commit/diff", null);
    }
}
