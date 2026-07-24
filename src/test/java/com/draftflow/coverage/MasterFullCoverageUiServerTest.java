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

public class MasterFullCoverageUiServerTest {

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

        Blob blob = new Blob("master ui test content".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);
        TreeEntry entry = new TreeEntry("master_ui.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(List.of(entry));
        String treeHash = cas.writeObject(tree);

        Revision rev = new Revision(treeHash, new ArrayList<>(), "master-ui", "Author <auth@test.com>", System.currentTimeMillis(), "master ui commit", false);
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
    public void testActionHandlerAllBranches() throws Exception {
        // 1. Action save
        sendRequest("POST", "/api/action", "{\"action\":\"save\",\"message\":\"ui save message\"}");

        // 2. Action rebase
        sendRequest("POST", "/api/action", "{\"action\":\"rebase\",\"upstream\":\"main\"}");

        // 3. Action cherry-pick
        sendRequest("POST", "/api/action", "{\"action\":\"cherry-pick\",\"hash\":\"" + commitHash + "\"}");

        // 4. Action stash
        sendRequest("POST", "/api/action", "{\"action\":\"stash\"}");

        // 5. Action prune
        sendRequest("POST", "/api/action", "{\"action\":\"prune\"}");

        // 6. Action clean
        sendRequest("POST", "/api/action", "{\"action\":\"clean\"}");

        // 7. Action undo
        sendRequest("POST", "/api/action", "{\"action\":\"undo\"}");

        // 8. Unknown action
        sendRequest("POST", "/api/action", "{\"action\":\"unknown_action\"}");

        // 9. Malformed action body
        sendRequest("POST", "/api/action", "corrupt json payload");
    }

    @Test
    public void testStaticAssetsAndRootEndpoints() throws Exception {
        sendRequest("GET", "/", null);
        sendRequest("GET", "/index.html", null);
        sendRequest("GET", "/bundle.js", null);
        sendRequest("GET", "/non_existent_page.html", null);
    }
}
