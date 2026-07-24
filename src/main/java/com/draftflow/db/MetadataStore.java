/**
 * @file MetadataStore.java
 * @description The persistent transactional metadata store for DraftFlow VCS.
 * Wraps H2 MVStore (Multi-Version Store) map configurations to track indexed working files, branch pointers,
 * change histories, and repository telemetry values.
 * 
 * DESIGN RATIONALE:
 * - H2 MVStore provides lightweight, single-file embedded transactional storage, avoiding heavy SQL engines.
 * - Maintains a `ConcurrentHashMap` write-through cache of file metadata on top of MVStore maps to optimize scan reads.
 * - Integrates recovery logic: if the store is corrupt, it moves the damaged file to a backup path and regenerates
 *   a clean database, preventing permanent VCS lockouts.
 * - Registers a JVM shutdown hook to force-flush active transactions to disk on shutdown.
 */

package com.draftflow.db;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MetadataStore implements AutoCloseable {
    private final Path dbFilePath;
    private MVStore store;
    private MVMap<String, String> indexMap;
    private MVMap<String, String> refMap;
    private MVMap<String, String> changeMap;
    private MVMap<String, String> changeHistoryMap;
    private MVMap<String, String> configMap;
    private MVMap<String, String> usersMap;
    private MVMap<String, String> prMap;


    private final java.util.Map<String, FileMetadata> fileMetadataCache = new java.util.concurrent.ConcurrentHashMap<>();
    private Thread shutdownHook;

    public interface ShutdownHookRegistry {
        void addShutdownHook(Thread hook);
        void removeShutdownHook(Thread hook);
    }

    public ShutdownHookRegistry shutdownHookRegistry = new ShutdownHookRegistry() {
        @Override
        public void addShutdownHook(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }
        @Override
        public void removeShutdownHook(Thread hook) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    };

    public MetadataStore(Path dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    public void open() throws IOException {
        Files.createDirectories(dbFilePath.getParent());
        try {
            this.store = new MVStore.Builder()
                    .fileName(dbFilePath.toString())
                    .compress()
                    .open();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.toLowerCase().contains("lock") || msg.toLowerCase().contains("in use") || msg.toLowerCase().contains("locked"))) {
                throw new IOException("Database file is locked by another process (is the DraftFlow Dashboard/UiServer running?). Please stop the dashboard or other running DraftFlow processes and try again.", e);
            }
            // MVStore corruption recovery: backup corrupt db and recreate a clean one
            Path backupPath = dbFilePath.resolveSibling(dbFilePath.getFileName().toString() + ".corrupted_" + System.currentTimeMillis());
            try {
                if (Files.exists(dbFilePath)) {
                    Files.move(dbFilePath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ie) {
                try {
                    Files.deleteIfExists(dbFilePath);
                } catch (IOException ignored) {}
            }
            try {
                this.store = new MVStore.Builder()
                        .fileName(dbFilePath.toString())
                        .compress()
                        .open();
            } catch (Exception e2) {
                String msg2 = e2.getMessage();
                if (msg2 != null && (msg2.toLowerCase().contains("lock") || msg2.toLowerCase().contains("in use") || msg2.toLowerCase().contains("locked"))) {
                    throw new IOException("Database file is locked by another process (is the DraftFlow Dashboard/UiServer running?). Please stop the dashboard or other running DraftFlow processes and try again.", e2);
                }
                // Secondary fallback: open in an alternative database file
                Path fallbackPath = dbFilePath.resolveSibling(dbFilePath.getFileName().toString() + ".fallback");
                try {
                    Files.deleteIfExists(fallbackPath);
                } catch (IOException ignored) {}
                this.store = new MVStore.Builder()
                        .fileName(fallbackPath.toString())
                        .compress()
                        .open();
            }
        }
        
        this.indexMap = this.store.openMap("index");
        this.refMap = this.store.openMap("refs");
        this.changeMap = this.store.openMap("changes");
        this.changeHistoryMap = this.store.openMap("changeHistory");
        this.configMap = this.store.openMap("config");
        this.usersMap = this.store.openMap("users");
        this.prMap = this.store.openMap("pullRequests");

        if (this.usersMap.isEmpty()) {
            this.usersMap.put("dev@vcs.dev", "{\"email\":\"dev@vcs.dev\",\"name\":\"Developer\",\"username\":\"dev\",\"password\":\"password123\",\"avatar\":\"D\",\"country\":\"United States\",\"domain\":\"Full Stack\",\"experience\":\"Intermediate\",\"joinedDate\":\"2026-01-01T00:00:00Z\",\"repositoryCount\":12}");
            this.usersMap.put("mina@vcs.dev", "{\"email\":\"mina@vcs.dev\",\"name\":\"Mina Chen\",\"username\":\"mina\",\"password\":\"password123\",\"avatar\":\"M\",\"country\":\"United States\",\"domain\":\"Full Stack\",\"experience\":\"Intermediate\",\"joinedDate\":\"2026-01-01T00:00:00Z\",\"repositoryCount\":12}");
            this.usersMap.put("ari@vcs.dev", "{\"email\":\"ari@vcs.dev\",\"name\":\"Ari Patel\",\"username\":\"ari\",\"password\":\"password123\",\"avatar\":\"A\",\"country\":\"United States\",\"domain\":\"Full Stack\",\"experience\":\"Intermediate\",\"joinedDate\":\"2026-01-01T00:00:00Z\",\"repositoryCount\":12}");
        }
        if (this.prMap.isEmpty()) {
            this.prMap.put("pr-1", "{\"id\":\"pr-1\",\"number\":42,\"title\":\"Refactor auth flow to support SSO providers\",\"description\":\"This PR adds support for Google, GitHub, and Azure AD SSO integration.\",\"author\":{\"name\":\"Mina Chen\",\"email\":\"mina@vcs.dev\",\"avatar\":\"M\"},\"sourceBranch\":\"feature/auth\",\"targetBranch\":\"main\",\"status\":\"open\",\"createdAt\":\"2026-06-26 14:30\",\"updatedAt\":\"2026-06-27 09:15\",\"commits\":3,\"additions\":245,\"deletions\":89,\"reviewers\":[{\"name\":\"Ari Patel\",\"status\":\"approved\"},{\"name\":\"Maya Rodriguez\",\"status\":\"requested\"}],\"labels\":[\"enhancement\",\"auth\"]}");
            this.prMap.put("pr-2", "{\"id\":\"pr-2\",\"number\":41,\"title\":\"Polish reviewer sidebar styling\",\"description\":\"Improves the visual design and accessibility of the reviewer panel.\",\"author\":{\"name\":\"Ari Patel\",\"email\":\"ari@vcs.dev\",\"avatar\":\"A\"},\"sourceBranch\":\"feature/ui-polish\",\"targetBranch\":\"main\",\"status\":\"merged\",\"createdAt\":\"2026-06-25 10:00\",\"updatedAt\":\"2026-06-26 18:40\",\"commits\":5,\"additions\":120,\"deletions\":45,\"reviewers\":[{\"name\":\"Maya Rodriguez\",\"status\":\"approved\"}],\"labels\":[\"ui\",\"css\"]}");
        }



        // Warm up and populate in-memory cache
        this.fileMetadataCache.clear();
        for (Map.Entry<String, String> entry : indexMap.entrySet()) {
            FileMetadata fm = FileMetadata.fromJson(entry.getValue());
            if (fm != null) {
                this.fileMetadataCache.put(entry.getKey(), fm);
            }
        }

        // Graceful VM shutdown hook to commit and close MVStore
        this.shutdownHook = new Thread(() -> {
            try {
                if (store != null && !store.isClosed()) {
                    store.commit();
                    store.close();
                }
            } catch (Exception ex) {
                // Ignore during shutdown
            }
        });
        shutdownHookRegistry.addShutdownHook(shutdownHook);
    }

    public synchronized void commit() {
        if (store != null && !store.isClosed()) {
            store.commit();
        }
    }

    @Override
    public synchronized void close() {
        if (shutdownHook != null) {
            try {
                shutdownHookRegistry.removeShutdownHook(shutdownHook);
            } catch (Exception e) {
                // Ignore if shutdown is already in progress
            }
            shutdownHook = null;
        }
        if (store != null && !store.isClosed()) {
            store.commit();
            store.close();
        }
    }

    // --- Index Cache Operations ---

    public synchronized void putFile(FileMetadata meta) {
        indexMap.put(meta.getPath(), meta.toJson());
        fileMetadataCache.put(meta.getPath(), meta);
    }

    public synchronized FileMetadata getFile(String path) {
        return fileMetadataCache.get(path);
    }

    public synchronized void removeFile(String path) {
        indexMap.remove(path);
        fileMetadataCache.remove(path);
    }

    public synchronized List<FileMetadata> getAllFiles() {
        return new ArrayList<>(fileMetadataCache.values());
    }

    public synchronized void clearIndex() {
        indexMap.clear();
        fileMetadataCache.clear();
    }

    // --- Ref / Branch Operations ---

    public synchronized void setRef(String name, String revisionHash) {
        refMap.put(name, revisionHash);
    }

    public synchronized String getRef(String name) {
        return refMap.get(name);
    }

    public synchronized void removeRef(String name) {
        refMap.remove(name);
    }

    public synchronized List<String> getRefNames() {
        return new ArrayList<>(refMap.keySet());
    }

    // --- Change ID Operations ---

    public synchronized void setChangeRevision(String changeId, String revisionHash) {
        changeMap.put(changeId, revisionHash);
        addRevisionToChangeHistory(changeId, revisionHash);
    }

    public synchronized String getChangeRevision(String changeId) {
        return changeMap.get(changeId);
    }

    private synchronized void addRevisionToChangeHistory(String changeId, String revisionHash) {
        String history = changeHistoryMap.get(changeId);
        if (history == null || history.isEmpty()) {
            changeHistoryMap.put(changeId, revisionHash);
        } else {
            String[] parts = history.split(",");
            if (!parts[parts.length - 1].equals(revisionHash)) {
                changeHistoryMap.put(changeId, history + "," + revisionHash);
            }
        }
    }

    public synchronized List<String> getChangeHistory(String changeId) {
        String history = changeHistoryMap.get(changeId);
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(history.split(",")));
    }

    // --- Config Operations ---

    public synchronized void setConfig(String key, String value) {
        configMap.put(key, value);
    }

    public synchronized String getConfig(String key) {
        return configMap.get(key);
    }

    public synchronized void removeConfig(String key) {
        configMap.remove(key);
    }

    public synchronized Map<String, String> getAllConfig() {
        return new java.util.HashMap<>(configMap);
    }

    // --- User Operations ---

    public synchronized void putUser(String email, String json) {
        usersMap.put(email, json);
    }

    public synchronized String getUser(String email) {
        return usersMap.get(email);
    }

    public synchronized List<String> getAllUsers() {
        return new ArrayList<>(usersMap.values());
    }

    // --- Pull Request Operations ---

    public synchronized void putPullRequest(String id, String json) {
        prMap.put(id, json);
    }

    public synchronized String getPullRequest(String id) {
        return prMap.get(id);
    }

    public synchronized List<String> getAllPullRequests() {
        return new ArrayList<>(prMap.values());
    }

    public synchronized void removePullRequest(String id) {
        prMap.remove(id);
    }
}
