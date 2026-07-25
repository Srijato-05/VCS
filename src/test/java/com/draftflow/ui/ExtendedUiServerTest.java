package com.draftflow.ui;

import com.draftflow.core.CAS;
import com.draftflow.db.MetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ExtendedUiServerTest {

    @TempDir
    Path tempDir;

    @Test
    public void testExtendedEndpoints() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();

        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();

            UiServer server = new UiServer(cas, db, 0); // Bind dynamically to a free port
            server.start();
            int port = server.getPort();
            assertTrue(port > 0);

            HttpClient client = HttpClient.newHttpClient();

            // 1. Test GET /api/pull-requests (Should return initial PRs)
            HttpRequest getPrs = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/pull-requests"))
                    .GET()
                    .build();
            HttpResponse<String> prsResponse = client.send(getPrs, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, prsResponse.statusCode());
            assertTrue(prsResponse.body().contains("Refactor auth flow"));
            assertTrue(prsResponse.body().contains("Polish reviewer sidebar"));

            // 2. Test POST /api/auth/signup
            String signupJson = "{\"email\":\"test@vcs.dev\",\"name\":\"Test User\",\"password\":\"testpass\",\"username\":\"testuser\"}";
            HttpRequest signupReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/auth/signup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(signupJson))
                    .build();
            HttpResponse<String> signupResponse = client.send(signupReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signupResponse.statusCode());
            assertTrue(signupResponse.body().contains("test@vcs.dev"));

            // 3. Test POST /api/auth/login
            String loginJson = "{\"email\":\"test@vcs.dev\",\"password\":\"testpass\"}";
            HttpRequest loginReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginJson))
                    .build();
            HttpResponse<String> loginResponse = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loginResponse.statusCode());
            assertTrue(loginResponse.body().contains("test@vcs.dev"));

            // 4. Test GET /api/auth/profile
            HttpRequest getProfileReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/auth/profile"))
                    .header("X-User-Email", "test@vcs.dev")
                    .GET()
                    .build();
            HttpResponse<String> getProfileResponse = client.send(getProfileReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getProfileResponse.statusCode());
            assertTrue(getProfileResponse.body().contains("Test User"));

            // 5. Test POST /api/auth/profile (Update profile)
            String updateProfileJson = "{\"email\":\"test@vcs.dev\",\"country\":\"Canada\",\"domain\":\"DevOps\"}";
            HttpRequest updateProfileReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/auth/profile"))
                    .header("Content-Type", "application/json")
                    .header("X-User-Email", "test@vcs.dev")
                    .POST(HttpRequest.BodyPublishers.ofString(updateProfileJson))
                    .build();
            HttpResponse<String> updateProfileResponse = client.send(updateProfileReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, updateProfileResponse.statusCode());
            assertTrue(updateProfileResponse.body().contains("Canada"));
            assertTrue(updateProfileResponse.body().contains("DevOps"));

            // 6. Test POST /api/pull-requests (Create PR)
            String createPrJson = "{\"title\":\"Add test PR\",\"description\":\"Testing PR creation\",\"sourceBranch\":\"feature/test\",\"targetBranch\":\"main\"}";
            HttpRequest createPrReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/pull-requests"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createPrJson))
                    .build();
            HttpResponse<String> createPrResponse = client.send(createPrReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, createPrResponse.statusCode());
            assertTrue(createPrResponse.body().contains("Add test PR"));
            assertTrue(createPrResponse.body().contains("pr-43"));

            // 7. Test POST /api/pull-requests/close
            String closePrJson = "{\"id\":\"pr-43\"}";
            HttpRequest closePrReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/pull-requests/close"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(closePrJson))
                    .build();
            HttpResponse<String> closePrResponse = client.send(closePrReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, closePrResponse.statusCode());
            assertTrue(closePrResponse.body().contains("\"status\":\"closed\""));

            // 8. Test POST /api/pull-requests/comment
            String commentPrJson = "{\"id\":\"pr-1\",\"comment\":{\"body\":\"Looks good to me\",\"author\":\" Ari Patel\"}}";
            HttpRequest commentPrReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/pull-requests/comment"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(commentPrJson))
                    .build();
            HttpResponse<String> commentPrResponse = client.send(commentPrReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, commentPrResponse.statusCode());
            assertTrue(commentPrResponse.body().contains("Looks good to me"));

            // 9. Test GET /api/settings and POST /api/settings
            HttpRequest getSettingsReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/settings"))
                    .GET()
                    .build();
            HttpResponse<String> getSettingsResponse = client.send(getSettingsReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getSettingsResponse.statusCode());
            assertTrue(getSettingsResponse.body().contains("defaultBranch"));

            String saveSettingsJson = "{\"requiresCodeReview\":true,\"defaultBranch\":\"main\"}";
            HttpRequest saveSettingsReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/settings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(saveSettingsJson))
                    .build();
            HttpResponse<String> saveSettingsResponse = client.send(saveSettingsReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saveSettingsResponse.statusCode());

            // 10. Test GET /api/repositories
            HttpRequest getReposReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/repositories"))
                    .GET()
                    .build();
            HttpResponse<String> getReposResponse = client.send(getReposReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getReposResponse.statusCode());

            server.stop();
        }
    }

    @Test
    public void testRemoteHandlers() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            UiServer server = new UiServer(cas, db, 0);
            server.start();
            int port = server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            // GET /api/remote/refs
            HttpResponse<String> r1 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/refs")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r1.statusCode());

            // POST /api/remote/refs
            String refBody = "{\"name\":\"heads/feature\",\"hash\":\"1234567890abcdef1234567890abcdef12345678\"}";
            HttpResponse<String> r2 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/refs")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(refBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r2.statusCode());

            // GET /api/remote/refs?name=heads/feature
            HttpResponse<String> r3 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/refs?name=heads/feature")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r3.statusCode());
            assertTrue(r3.body().contains("1234567890abcdef"));

            // DELETE /api/remote/refs?name=heads/feature
            HttpResponse<String> r4 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/refs?name=heads/feature")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r4.statusCode());

            // GET /api/remote/index
            HttpResponse<String> r5 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/index")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r5.statusCode());

            // POST /api/remote/index
            HttpResponse<String> r6 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/index")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"obj1\":\"pack1\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r6.statusCode());

            // POST /api/remote/packs?id=pack-1
            HttpResponse<String> r7 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/packs?id=pack-1")).header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofString("pack stream")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r7.statusCode());

            // GET /api/remote/packs?id=pack-1
            HttpResponse<String> r8 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/remote/packs?id=pack-1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r8.statusCode());
            assertEquals("pack stream", r8.body());

            server.stop();
        }
    }

    @Test
    public void testFileContentAndConflictDetailsHandlers() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();
        Path dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            UiServer server = new UiServer(cas, db, 0);
            server.start();
            int port = server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            // GET /api/file-content without file param
            HttpResponse<String> r1 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/file-content")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r1.statusCode());

            // GET /api/conflict-details without file param
            HttpResponse<String> r2 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/conflict-details")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r2.statusCode());

            // POST /api/auth/sync
            HttpResponse<String> r3 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/auth/sync")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r3.statusCode());

            // POST /api/auth/logout
            HttpResponse<String> r4 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/auth/logout")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r4.statusCode());

            // POST /api/repositories/create
            String repoName = "test-repo-" + System.currentTimeMillis();
            HttpResponse<String> r5 = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/repositories/create")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + repoName + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r5.statusCode());

            server.stop();
        }
    }
}
