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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;

public class MasterFullCoverageKeysAndDashboardTest {

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

    private int sendRequest(String method, String endpoint) throws IOException {
        URL url = new URL(serverUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
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
    public void testKeysCmdAllBranches() throws Exception {
        // List empty keys
        runCli("keys", "--list");

        // Add empty key error
        runCli("keys", "--add", "   ");

        // Add valid key
        runCli("keys", "--add", "sample-public-key-1");

        // Add duplicate key
        runCli("keys", "--add", "sample-public-key-1");

        // List keys
        runCli("keys", "--list");

        // Remove key
        runCli("keys", "--remove", "sample-public-key-1");

        // Remove non-existent key
        runCli("keys", "--remove", "sample-public-key-1");

        // Generate ECDSA keypair
        runCli("keys");

        // Attempt generating existing keypair
        runCli("keys");
    }

    @Test
    public void testRemainingUiServerHandlers() throws Exception {
        sendRequest("POST", "/api/logout");
        sendRequest("GET", "/api/remote/packs");
        sendRequest("GET", "/api/remote/refs");
        sendRequest("GET", "/api/remote/index");
        sendRequest("POST", "/api/sync");
    }

    @Test
    public void testDashboardCmdExecution() throws Exception {
        Thread thread = new Thread(() -> runCli("dashboard", "--port", "0"));
        thread.start();
        Thread.sleep(500);
        thread.interrupt();
        thread.join(2000);
    }
}
