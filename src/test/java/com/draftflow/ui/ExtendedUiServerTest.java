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
}
