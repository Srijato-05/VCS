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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveUiServerHandlersTest {

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
        db.setConfig("activeHead", "heads/main");
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

    @Test
    public void testIndexHandlerResourceMimeTypesAnd404() throws Exception {
        // 1. Request non-existent static resource (expect 404)
        HttpRequest request404 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/web/nonexistent-file.xyz"))
                .GET()
                .build();
        HttpResponse<String> res404 = client.send(request404, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res404.statusCode());

        // 2. Request default index.html fallback
        HttpRequest requestIndex = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();
        HttpResponse<String> resIndex = client.send(requestIndex, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resIndex.statusCode());
        assertTrue(resIndex.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }

    @Test
    public void testActionHandlerHttpMethods() throws Exception {
        // GET on ActionHandler should return 405 Method Not Allowed
        HttpRequest requestGet = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/action?cmd=status"))
                .GET()
                .build();
        HttpResponse<String> resGet = client.send(requestGet, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resGet.statusCode());

        // POST without cmd should return 500 error json
        HttpRequest requestPostNoCmd = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/action"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resPostNoCmd = client.send(requestPostNoCmd, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resPostNoCmd.statusCode());
        assertTrue(resPostNoCmd.body().contains("error"));
    }

    @Test
    public void testFileContentHandlerMissingFile() throws Exception {
        // GET on FileContentHandler without file parameter should return 500 error json
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/file-content"))
                .GET()
                .build();
        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("error"));
    }
}
