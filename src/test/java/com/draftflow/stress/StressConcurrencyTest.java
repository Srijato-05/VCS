package com.draftflow.stress;

import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import com.draftflow.ui.UiServer;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class StressConcurrencyTest {

    @TempDir
    Path tempDir;

    private CAS cas;
    private Path dbPath;

    @BeforeEach
    public void setUp() throws IOException {
        cas = new CAS(tempDir);
        cas.init();
        dbPath = tempDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
    }

    @Test
    public void testConcurrentCasReadWriteStress() throws InterruptedException {
        int threadCount = 16;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger writeCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        String content = "Thread-" + threadId + " Op-" + j + " Data-" + System.nanoTime();
                        Blob blob = new Blob(content.getBytes(StandardCharsets.UTF_8));
                        String hash = cas.writeObject(blob);
                        assertNotNull(hash);

                        Blob readBlob = (Blob) cas.readObject(hash);
                        assertEquals(content, new String(readBlob.getContent(), StandardCharsets.UTF_8));
                        writeCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "CAS Stress test timed out");
        assertTrue(errors.isEmpty(), "Encountered errors during CAS concurrent stress test: " + errors);
        assertEquals(threadCount * operationsPerThread, writeCount.get());
    }

    @Test
    public void testConcurrentMetadataStoreAccess() throws Exception {
        // Prepare database initial state
        try (MetadataStore setupDb = new MetadataStore(dbPath)) {
            setupDb.open();
            setupDb.setConfig("activeHead", "heads/main");
            setupDb.commit();
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    synchronized (StressConcurrencyTest.class) {
                        try (MetadataStore db = new MetadataStore(dbPath)) {
                            db.open();
                            String file = "file_" + threadId + ".txt";
                            db.putFile(new FileMetadata(file, 100L, System.currentTimeMillis(), "hash_" + threadId, "BLOB", 100644));
                            db.setConfig("thread_" + threadId + "_key", "val_" + threadId);
                            db.commit();
                            assertNotNull(db.getFile(file));
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "MetadataStore stress test timed out");
        assertTrue(errors.isEmpty(), "Errors during MetadataStore stress test: " + errors);
    }

    @Test
    public void testConcurrentUiServerHttpRequests() throws Exception {
        // Init DB state
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            db.setConfig("activeHead", "heads/main");
            db.commit();
        }

        UiServer server = new UiServer(cas, null, 0);
        server.start();
        String baseUrl = "http://localhost:" + server.getPort();
        HttpClient client = HttpClient.newHttpClient();

        int threadCount = 12;
        int requestsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/status"))
                                .GET()
                                .build();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            successCount.incrementAndGet();
                        } else {
                            errors.add(new RuntimeException("Non-200 response: " + resp.statusCode()));
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "UiServer HTTP stress test timed out");
        server.stop();

        assertTrue(errors.isEmpty(), "Encountered errors during UiServer concurrent requests: " + errors);
        assertEquals(threadCount * requestsPerThread, successCount.get());
    }
}
