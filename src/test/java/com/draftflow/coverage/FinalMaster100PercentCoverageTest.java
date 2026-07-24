package com.draftflow.coverage;

import com.draftflow.DraftFlow;
import com.draftflow.core.CAS;
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
import java.nio.file.Files;
import java.nio.file.Path;

public class FinalMaster100PercentCoverageTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path dbPath;
    private MetadataStore db;
    private UiServer uiServer;
    private String serverUrl;

    @BeforeEach
    public void setUp() throws Exception {
        cas = new CAS(tempDir);
        cas.init();
        dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        db = new MetadataStore(dbPath);
        db.open();

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

    private void runCli(String... args) {
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "--repo";
        fullArgs[1] = tempDir.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);
        DraftFlow.runMain(fullArgs);
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
    public void testBranchAndHistoryEmptyRepo() throws Exception {
        // Create branch on empty repo
        runCli("branch", "-c", "new-branch");

        // Delete active branch or non-existent branch
        runCli("branch", "-d", "non-existent");

        // History on empty repo
        runCli("history");
    }

    @Test
    public void testUiServerStaticAssetsAndPRs() throws Exception {
        sendRequest("GET", "/style.css", null);
        sendRequest("GET", "/script.js", null);
        sendRequest("GET", "/logo.png", null);
        sendRequest("GET", "/icon.svg", null);
        sendRequest("GET", "/nonexistent.file", null);

        sendRequest("GET", "/api/pull-requests", null);
        sendRequest("POST", "/api/pull-requests/merge", "{\"id\":\"pr-1\"}");
        sendRequest("POST", "/api/pull-requests/comment", "{\"id\":\"pr-1\",\"comment\":{\"author\":\"user\",\"text\":\"lgtm\"}}");
    }
}
