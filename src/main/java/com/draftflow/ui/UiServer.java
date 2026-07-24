/**
 * @file UiServer.java
 * @description Embedded HTTP Server for DraftFlow VCS visualization dashboard.
 * Serves the React frontend SPA assets from resource paths, and provides a series 
 * of REST API endpoints to access status, histories, line blame traces, and 
 * execute command actions.
 * 
 * DESIGN RATIONALE:
 * - Built using lightweight Java standard `com.sun.net.httpserver` to avoid heavy 
 *   framework dependencies like Spring Boot or Jetty, keeping build time sub-second.
 * - Thread-safe operations via volatile CAS and MetadataStore references to allow live re-sync.
 * - Handles SPA router fallback (redirecting unregistered static routes to index.html).
 */

package com.draftflow.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.draftflow.core.*;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;


import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class UiServer {

    private volatile CAS cas;
    private volatile MetadataStore db;
    private HttpServer server;
    private int port;
    private static final Gson GSON = new Gson();


    public UiServer(CAS cas, MetadataStore db, int port) {
        this.cas = cas;
        this.db = db;
        this.port = port;
    }

    private void registerContext(String path, HttpHandler handler) {
        com.sun.net.httpserver.HttpContext context = server.createContext(path, handler);
        context.getFilters().add(new DatabaseLifecycleFilter());
    }

    private class DatabaseLifecycleFilter extends com.sun.net.httpserver.Filter {
        @Override
        public String description() {
            return "Manages database connection lifecycle per HTTP request to allow concurrent CLI operations";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            synchronized (UiServer.this) {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                db = new MetadataStore(dbPath);
                db.open();
                try {
                    chain.doFilter(exchange);
                } finally {
                    try {
                        db.close();
                    } catch (Exception ignored) {}
                    db = null;
                }
            }
        }
    }

    public void start() throws IOException {
        if (db != null) {
            db.close();
            db = null;
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        registerContext("/", new IndexHandler());
        registerContext("/api/dag", new DagHandler());
        registerContext("/api/status", new StatusHandler());
        registerContext("/api/ledger", new LedgerHandler());
        registerContext("/api/trace", new TraceHandler());
        registerContext("/api/conflict-details", new ConflictDetailsHandler());
        registerContext("/api/action", new ActionHandler());
        registerContext("/api/file-content", new FileContentHandler());
        registerContext("/api/auth/signup", new SignupHandler());
        registerContext("/api/auth/login", new LoginHandler());
        registerContext("/api/auth/profile", new ProfileHandler());
        registerContext("/api/auth/sync", new SyncHandler());
        registerContext("/api/auth/logout", new LogoutHandler());
        registerContext("/api/pull-requests", new PullRequestsHandler());
        registerContext("/api/pull-requests/merge", new PullRequestMergeHandler());
        registerContext("/api/pull-requests/close", new PullRequestCloseHandler());
        registerContext("/api/pull-requests/comment", new PullRequestCommentHandler());
        registerContext("/api/settings", new SettingsHandler());
        registerContext("/api/repositories", new RepositoriesHandler());
        registerContext("/api/repositories/create", new CreateRepositoryHandler());
        registerContext("/api/commit-tree", new CommitTreeHandler());
        registerContext("/api/commit-diff", new CommitDiffHandler());
        server.setExecutor(null); // default executor
        server.start();
        this.port = server.getAddress().getPort();
        System.out.println("DraftFlow UI Server running at: http://localhost:" + this.port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return port;
    }

    private int executeCommandWithDbClosed(java.util.concurrent.Callable<Integer> cmd) throws Exception {
        synchronized (this) {
            if (db != null) {
                db.close();
            }
            try {
                return cmd.call();
            } finally {
                Path dbPath = cas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                db = new MetadataStore(dbPath);
                db.open();
            }
        }
    }

    // --- HANDLERS ---

    private class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            String resourcePath = "/web" + path;
            InputStream is = getResourceAsStream(resourcePath);
            if (is == null && !path.startsWith("/api/") && !path.contains(".")) {
                // Fallback to SPA index.html for virtual routes
                path = "/index.html";
                resourcePath = "/web/index.html";
                is = getResourceAsStream(resourcePath);
            }

            
            byte[] bytes = null;
            if (is == null) {
                if (path.equals("/index.html")) {
                    bytes = getDashboardHtml();
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
            } else {
                try (InputStream tempIs = is) {
                    bytes = tempIs.readAllBytes();
                }
            }
            
            String contentType = "text/html; charset=utf-8";
            if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=utf-8";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";
            
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class DagHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String json = buildDagJson();
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String json = buildStatusJson();
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }
    }

    // --- JSON BUILDERS ---

    private String buildDagJson() throws IOException {
        // Find all reachable revisions
        Map<String, List<String>> refMap = new HashMap<>();
        for (String refName : db.getRefNames()) {
            String target = db.getRef(refName);
            if (target != null) {
                refMap.computeIfAbsent(target, k -> new ArrayList<>()).add(refName);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (List<String> refs : refMap.values()) {
            for (String r : refs) {
                String hash = db.getRef(r);
                if (hash != null) {
                    queue.add(hash);
                }
            }
        }
        
        String activeRev = db.getConfig("activeRevisionHash");
        if (activeRev != null) {
            queue.add(activeRev);
        }

        Set<String> visited = new HashSet<>();
        List<String> revisionsJsonList = new ArrayList<>();

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr == null || visited.contains(curr)) {
                continue;
            }
            visited.add(curr);

            try {
                Revision rev = (Revision) cas.readObject(curr);
                StringBuilder parentsJson = new StringBuilder("[");
                for (int i = 0; i < rev.getParentHashes().size(); i++) {
                    parentsJson.append("\"").append(rev.getParentHashes().get(i)).append("\"");
                    if (i < rev.getParentHashes().size() - 1) {
                        parentsJson.append(",");
                    }
                    queue.add(rev.getParentHashes().get(i));
                }
                parentsJson.append("]");

                StringBuilder refsJson = new StringBuilder("[");
                List<String> refs = refMap.get(curr);
                if (refs != null) {
                    for (int i = 0; i < refs.size(); i++) {
                        refsJson.append("\"").append(refs.get(i)).append("\"");
                        if (i < refs.size() - 1) {
                            refsJson.append(",");
                        }
                    }
                }
                refsJson.append("]");

                String signatureStatus = "unsigned";
                if (rev.getSignature() != null && rev.getPublicKey() != null) {
                    try {
                        boolean ok = SignatureHelper.verify(rev.getSigningData(), rev.getSignature(), rev.getPublicKey());
                        signatureStatus = ok ? "valid" : "invalid";
                    } catch (Exception e) {
                        signatureStatus = "invalid";
                    }
                }

                String parentTreeHash = null;
                if (!rev.getParentHashes().isEmpty()) {
                    String parentHash = rev.getParentHashes().get(0);
                    try {
                        Revision parentRev = (Revision) cas.readObject(parentHash);
                        parentTreeHash = parentRev.getTreeHash();
                    } catch (Exception ignored) {}
                }
                List<com.draftflow.diff.FileDiff> diffs = com.draftflow.diff.TreeDiffer.diff(parentTreeHash, rev.getTreeHash(), cas);
                StringBuilder filesJson = new StringBuilder("[");
                for (int i = 0; i < diffs.size(); i++) {
                    com.draftflow.diff.FileDiff fd = diffs.get(i);
                    filesJson.append(String.format("{\"path\":\"%s\",\"type\":\"%s\"}",
                            fd.getPath().replace("\\", "\\\\").replace("\"", "\\\""),
                            fd.getType().name().toLowerCase()
                    ));
                    if (i < diffs.size() - 1) {
                        filesJson.append(",");
                    }
                }
                filesJson.append("]");

                String revJson = String.format(
                        "{\"hash\":\"%s\",\"treeHash\":\"%s\",\"parents\":%s,\"changeId\":\"%s\"," +
                                "\"author\":\"%s\",\"timestamp\":%d,\"message\":\"%s\",\"isDraft\":%b,\"refs\":%s,\"signatureStatus\":\"%s\",\"changedFiles\":%s}",
                        curr,
                        rev.getTreeHash(),
                        parentsJson.toString(),
                        rev.getChangeId(),
                        rev.getAuthor(),
                        rev.getTimestamp(),
                        rev.getMessage().replace("\"", "\\\"").replace("\n", " ").replace("\r", " "),
                        rev.isDraft(),
                        refsJson.toString(),
                        signatureStatus,
                        filesJson.toString()
                );
                revisionsJsonList.add(revJson);
            } catch (Exception ignored) {
                // Skip invalid or unreadable revision objects
            }
        }

        return "[" + String.join(",", revisionsJsonList) + "]";
    }

    private String buildStatusJson() throws IOException {
        String activeHead = db.getConfig("activeHead");
        String activeRev = db.getConfig("activeRevisionHash");
        String activeChange = db.getConfig("activeChangeId");

        List<String> branches = new ArrayList<>();
        for (String name : db.getRefNames()) {
            if (name.startsWith("heads/")) {
                branches.add(name.replace("heads/", ""));
            }
        }
        if (branches.contains("main")) {
            branches.remove("main");
        }
        branches.add(0, "main");

        List<FileMetadata> tracked = db.getAllFiles();
        List<String> trackedPaths = new ArrayList<>();
        for (FileMetadata file : tracked) {
            trackedPaths.add(file.getPath());
        }
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        for (FileMetadata file : tracked) {
            Path p = cas.getRootDir().resolve(file.getPath());
            if (!Files.exists(p)) {
                deleted.add(file.getPath());
            } else {
                if (file.getType().equals(ObjectType.CONFLICT.name())) {
                    conflicts.add(file.getPath());
                } else {
                    long size = Files.size(p);
                    long lastMod = Files.getLastModifiedTime(p).toMillis();
                    if (size != file.getSize() || lastMod != file.getLastModified()) {
                        modified.add(file.getPath());
                    }
                }
            }
        }

        List<String> untracked = new ArrayList<>();
        try {
            DraftFlowConfig config = cas.getConfig();
            com.draftflow.core.GitIgnoreMatcher ignoreMatcher = new com.draftflow.core.GitIgnoreMatcher(cas.getRootDir(), config.getExclude());
            Set<String> trackedRelativePaths = new HashSet<>();
            for (FileMetadata f : tracked) {
                trackedRelativePaths.add(f.getPath());
            }
            if (Files.exists(cas.getRootDir())) {
                Files.walkFileTree(cas.getRootDir(), new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        if (dir.equals(cas.getDraftFlowDir())) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        if (ignoreMatcher.isIgnored(dir)) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        String rel = cas.getRootDir().relativize(file).toString().replace('\\', '/');
                        if (!trackedRelativePaths.contains(rel) && !ignoreMatcher.isIgnored(file)) {
                            untracked.add(rel);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (Exception ignored) {}

        String workspaceName = cas.getRootDir().getFileName().toString();
        List<String> repositories = new ArrayList<>();
        repositories.add(workspaceName);
        Path parent = cas.getRootDir().getParent();
        if (parent != null && Files.exists(parent)) {
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && !entry.equals(cas.getRootDir())) {
                        if (Files.exists(entry.resolve(".draftflow"))) {
                            repositories.add(entry.getFileName().toString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        Collections.sort(repositories);

        String username = System.getProperty("user.name", "Developer");
        String osName = System.getProperty("os.name", "Unknown OS");
        String javaVersion = System.getProperty("java.version", "Unknown Java");

        long casSizeVal = 0L;
        try {
            if (Files.exists(cas.getDraftFlowDir())) {
                try (java.util.stream.Stream<Path> walk = Files.walk(cas.getDraftFlowDir())) {
                    casSizeVal = walk.filter(Files::isRegularFile)
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (Exception e) {
                                    return 0L;
                                }
                            })
                            .sum();
                }
            }
        } catch (Exception ignored) {}

        long objectCountVal = 0L;
        try {
            if (Files.exists(cas.getObjectsDir())) {
                try (java.util.stream.Stream<Path> walk = Files.walk(cas.getObjectsDir())) {
                    objectCountVal = walk.filter(Files::isRegularFile).count();
                }
            }
        } catch (Exception ignored) {}

        long totalVirtualSizeVal = 0L;
        for (FileMetadata f : tracked) {
            totalVirtualSizeVal += f.getSize();
        }

        String hashAlgorithm = "SHA-256";
        long lfsSizeThreshold = 10L * 1024 * 1024;
        try {
            DraftFlowConfig config = cas.getConfig();
            if (config != null) {
                if (config.getHashAlgorithm() != null) {
                    hashAlgorithm = config.getHashAlgorithm();
                }
                if (config.getLfsSizeThreshold() != null) {
                    lfsSizeThreshold = config.getLfsSizeThreshold();
                }
            }
        } catch (Exception ignored) {}

        long lfsFileCountVal = 0L;
        for (FileMetadata f : tracked) {
            if (f.getSize() >= lfsSizeThreshold) {
                lfsFileCountVal++;
            }
        }

        return String.format(
                "{\"activeHead\":\"%s\",\"activeRevision\":\"%s\",\"activeChangeId\":\"%s\"," +
                        "\"branches\":%s," +
                        "\"modified\":%s,\"deleted\":%s,\"conflicts\":%s,\"untracked\":%s," +
                        "\"trackedFiles\":%s," +
                        "\"workspaceName\":\"%s\",\"repositories\":%s,\"casSize\":%d," +
                        "\"casObjectCount\":%d,\"trackedCount\":%d,\"totalVirtualSize\":%d," +
                        "\"hashAlgorithm\":\"%s\",\"lfsThresholdBytes\":%d,\"lfsFileCount\":%d," +
                        "\"userProfile\":{\"username\":\"%s\",\"osName\":\"%s\",\"javaVersion\":\"%s\"}}",
                activeHead != null ? activeHead.replace("heads/", "") : "main",
                activeRev != null ? activeRev : "",
                activeChange != null ? activeChange : "",
                toJsonArray(branches),
                toJsonArray(modified),
                toJsonArray(deleted),
                toJsonArray(conflicts),
                toJsonArray(untracked),
                toJsonArray(trackedPaths),
                workspaceName.replace("\"", "\\\""),
                toJsonArray(repositories),
                casSizeVal,
                objectCountVal,
                tracked.size(),
                totalVirtualSizeVal,
                hashAlgorithm.replace("\"", "\\\""),
                lfsSizeThreshold,
                lfsFileCountVal,
                username.replace("\"", "\\\""),
                osName.replace("\"", "\\\""),
                javaVersion.replace("\"", "\\\"")
        );
    }

    private String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    // --- EMBEDDED DASHBOARD HTML ---

    // --- EMBEDDED DASHBOARD HTML ---

    private byte[] getDashboardHtml() {
        try {
            InputStream is = getResourceAsStream("/web/index.html");
            if (is != null) {
                try (is) {
                    return is.readAllBytes();
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback Premium Midnight Gold dashboard HTML
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>DraftFlow Premium Web GUI</title>
                    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;600;800&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
                    <style>
                        :root {
                            --bg-dark: #07080b;
                            --bg-card: rgba(18, 20, 29, 0.65);
                            --gold-gradient: linear-gradient(135deg, #f3d078 0%, #c39c38 100%);
                            --gold: #d4af37;
                            --gold-glow: rgba(212, 175, 55, 0.25);
                            --text-main: #f3f4f6;
                            --text-muted: #9ca3af;
                            --border-glow: rgba(212, 175, 55, 0.15);
                            --border-dim: rgba(255, 255, 255, 0.05);
                        }
                        * {
                            box-sizing: border-box;
                            margin: 0;
                            padding: 0;
                        }
                        body {
                            background-color: var(--bg-dark);
                            color: var(--text-main);
                            font-family: 'Inter', sans-serif;
                            min-height: 100vh;
                            display: flex;
                            flex-direction: column;
                            overflow-x: hidden;
                            position: relative;
                        }
                        @keyframes pulse {
                            0% { opacity: 0.5; transform: scale(1); }
                            100% { opacity: 0.8; transform: scale(1.05); }
                        }
                        header {
                            background: rgba(7, 8, 11, 0.8);
                            backdrop-filter: blur(16px);
                            -webkit-backdrop-filter: blur(16px);
                            border-bottom: 1px solid var(--border-dim);
                            padding: 18px 40px;
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            position: sticky;
                            top: 0;
                            z-index: 100;
                        }
                        .logo-container {
                            display: flex;
                            align-items: center;
                            gap: 12px;
                        }
                        .logo-icon {
                            width: 14px;
                            height: 14px;
                            background: var(--gold-gradient);
                            border-radius: 50%;
                            box-shadow: 0 0 12px var(--gold);
                        }
                        h1 {
                            font-family: 'Outfit', sans-serif;
                            font-weight: 800;
                            font-size: 24px;
                            letter-spacing: -0.5px;
                            background: var(--gold-gradient);
                            -webkit-background-clip: text;
                            -webkit-text-fill-color: transparent;
                        }
                        .tabs {
                            display: flex;
                            gap: 10px;
                        }
                        .tab-btn {
                            background: transparent;
                            border: 1px solid transparent;
                            color: var(--text-muted);
                            padding: 8px 16px;
                            border-radius: 8px;
                            font-size: 13px;
                            font-weight: 500;
                            cursor: pointer;
                            transition: all 0.2s ease;
                        }
                        .tab-btn:hover {
                            color: #fff;
                            background: rgba(255,255,255,0.03);
                        }
                        .tab-btn.active {
                            color: var(--gold);
                            background: rgba(212, 175, 55, 0.1);
                            border-color: var(--border-glow);
                        }
                        .container {
                            max-width: 1500px;
                            width: 100%;
                            margin: 30px auto;
                            padding: 0 30px;
                            flex-grow: 1;
                        }
                        .tab-content {
                            display: none;
                            animation: fadeIn 0.3s ease;
                        }
                        .tab-content.active {
                            display: grid;
                            grid-template-columns: 1fr 420px;
                            gap: 30px;
                        }
                        @keyframes fadeIn {
                            from { opacity: 0; transform: translateY(5px); }
                            to { opacity: 1; transform: translateY(0); }
                        }
                        .panel {
                            background: var(--bg-card);
                            border: 1px solid var(--border-dim);
                            border-radius: 20px;
                            padding: 30px;
                            backdrop-filter: blur(20px);
                            -webkit-backdrop-filter: blur(20px);
                            box-shadow: 0 10px 40px rgba(0, 0, 0, 0.6);
                            position: relative;
                            transition: border-color 0.3s ease;
                        }
                        .panel:hover {
                            border-color: rgba(212, 175, 55, 0.15);
                        }
                        h2 {
                            font-family: 'Outfit', sans-serif;
                            font-size: 18px;
                            font-weight: 600;
                            letter-spacing: -0.2px;
                            margin-bottom: 20px;
                            color: var(--gold);
                            display: flex;
                            align-items: center;
                            gap: 8px;
                            border-bottom: 1px solid var(--border-dim);
                            padding-bottom: 12px;
                        }
                        #graph-container {
                            height: 620px;
                            overflow: auto;
                            border: 1px solid var(--border-dim);
                            border-radius: 14px;
                            background: rgba(3, 4, 6, 0.4);
                            position: relative;
                        }
                        /* Scrollbar */
                        ::-webkit-scrollbar {
                            width: 8px;
                            height: 8px;
                        }
                        ::-webkit-scrollbar-track {
                            background: transparent;
                        }
                        ::-webkit-scrollbar-thumb {
                            background: rgba(255, 255, 255, 0.1);
                            border-radius: 4px;
                        }
                        ::-webkit-scrollbar-thumb:hover {
                            background: rgba(212, 175, 55, 0.3);
                        }
                        .status-card {
                            background: rgba(255, 255, 255, 0.02);
                            border: 1px solid var(--border-dim);
                            border-radius: 12px;
                            padding: 20px;
                            margin-bottom: 20px;
                        }
                        .status-grid {
                            display: grid;
                            grid-template-columns: 1fr;
                            gap: 15px;
                        }
                        .status-item {
                            display: flex;
                            flex-direction: column;
                            gap: 4px;
                        }
                        .status-label {
                            font-size: 11px;
                            font-weight: 500;
                            color: var(--text-muted);
                            text-transform: uppercase;
                            letter-spacing: 0.8px;
                        }
                        .status-value {
                            font-size: 14px;
                            font-weight: 600;
                            color: #ffffff;
                            word-break: break-all;
                        }
                        .file-list {
                            display: flex;
                            flex-direction: column;
                            gap: 8px;
                            max-height: 220px;
                            overflow-y: auto;
                            padding-right: 4px;
                        }
                        .file-item {
                            padding: 10px 14px;
                            border-radius: 8px;
                            font-family: 'JetBrains Mono', monospace;
                            font-size: 12px;
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            border: 1px solid transparent;
                            transition: all 0.2s ease;
                        }
                        .file-item:hover {
                            transform: translateX(4px);
                        }
                        .modified { 
                            background: rgba(212, 175, 55, 0.06); 
                            color: #f3d078;
                            border-color: rgba(212, 175, 55, 0.12);
                        }
                        .deleted { 
                            background: rgba(239, 68, 68, 0.06); 
                            color: #f87171;
                            border-color: rgba(239, 68, 68, 0.12);
                        }
                        .conflict { 
                            background: rgba(249, 115, 22, 0.08); 
                            color: #fb923c;
                            border-color: rgba(249, 115, 22, 0.2);
                            animation: pulse-orange 2s infinite;
                        }
                        @keyframes pulse-orange {
                            0% { box-shadow: 0 0 0 0 rgba(249, 115, 22, 0.4); }
                            70% { box-shadow: 0 0 0 6px rgba(249, 115, 22, 0); }
                            100% { box-shadow: 0 0 0 0 rgba(249, 115, 22, 0); }
                        }
                        .btn {
                            background: var(--gold-gradient);
                            border: none;
                            color: #000;
                            padding: 10px 18px;
                            border-radius: 8px;
                            font-size: 13px;
                            font-weight: 700;
                            cursor: pointer;
                            transition: all 0.2s ease;
                            display: inline-flex;
                            align-items: center;
                            justify-content: center;
                            gap: 6px;
                        }
                        .btn:hover {
                            box-shadow: 0 0 15px rgba(212, 175, 55, 0.4);
                            transform: translateY(-1px);
                        }
                        .btn-sec {
                            background: rgba(255,255,255,0.05);
                            border: 1px solid var(--border-dim);
                            color: #fff;
                        }
                        .btn-sec:hover {
                            background: rgba(255,255,255,0.1);
                            box-shadow: none;
                        }
                        .btn-danger {
                            background: linear-gradient(135deg, #ef4444 0%, #b91c1c 100%);
                            color: #fff;
                        }
                        .btn-danger:hover {
                            box-shadow: 0 0 15px rgba(239, 68, 68, 0.4);
                        }
                        input[type="text"] {
                            background: rgba(255,255,255,0.03);
                            border: 1px solid var(--border-dim);
                            color: #fff;
                            padding: 10px 14px;
                            border-radius: 8px;
                            font-size: 13px;
                            width: 100%;
                            outline: none;
                            transition: border-color 0.2s ease;
                        }
                        input[type="text"]:focus {
                            border-color: var(--gold);
                        }
                        .input-group {
                            display: flex;
                            flex-direction: column;
                            gap: 8px;
                            margin-bottom: 15px;
                        }
                        .input-group label {
                            font-size: 11px;
                            font-weight: 600;
                            color: var(--text-muted);
                            text-transform: uppercase;
                            letter-spacing: 0.5px;
                        }
                        /* SVG Graph Styles */
                        .node-circle {
                            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                            cursor: pointer;
                        }
                        .node-circle:hover {
                            r: 16;
                            fill: #ffffff;
                            stroke-width: 5;
                            filter: drop-shadow(0px 0px 8px var(--gold));
                        }
                        .edge-path {
                            stroke-dasharray: 1000;
                            stroke-dashoffset: 1000;
                            animation: draw 1.5s ease forwards;
                        }
                        @keyframes draw {
                            to { stroke-dashoffset: 0; }
                        }
                        /* Modal Info Panel */
                        .detail-panel {
                            background: rgba(13, 15, 24, 0.95);
                            border: 1px solid var(--border-glow);
                            box-shadow: 0 20px 50px rgba(0,0,0,0.8);
                            border-radius: 16px;
                            padding: 24px;
                            display: none;
                            position: absolute;
                            bottom: 20px;
                            left: 20px;
                            right: 20px;
                            z-index: 10;
                            backdrop-filter: blur(25px);
                            animation: slideUp 0.3s cubic-bezier(0.16, 1, 0.3, 1);
                        }
                        @keyframes slideUp {
                            from { transform: translateY(20px); opacity: 0; }
                            to { transform: translateY(0); opacity: 1; }
                        }
                        .detail-close {
                            position: absolute;
                            top: 15px;
                            right: 15px;
                            cursor: pointer;
                            color: var(--text-muted);
                            border: none;
                            background: transparent;
                            font-size: 18px;
                        }
                        .detail-close:hover {
                            color: #fff;
                        }
                        .ref-tag {
                            background: var(--gold-gradient);
                            color: #000;
                            padding: 2px 8px;
                            border-radius: 4px;
                            font-size: 11px;
                            font-weight: 700;
                            margin-right: 5px;
                            display: inline-block;
                        }
                        /* Toast System */
                        #toast {
                            position: fixed;
                            bottom: 30px;
                            right: 30px;
                            background: rgba(18, 20, 29, 0.95);
                            border: 1px solid var(--border-glow);
                            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
                            border-radius: 10px;
                            padding: 16px 24px;
                            color: #fff;
                            font-size: 13px;
                            display: none;
                            z-index: 999;
                            align-items: center;
                            gap: 10px;
                            backdrop-filter: blur(10px);
                            animation: slideIn 0.3s ease;
                        }
                        @keyframes slideIn {
                            from { transform: translateX(50px); opacity: 0; }
                            to { transform: translateX(0); opacity: 1; }
                        }
                        /* Trace Table */
                        .trace-table {
                            width: 100%;
                            border-collapse: collapse;
                            font-size: 12px;
                            margin-top: 15px;
                        }
                        .trace-table th, .trace-table td {
                            padding: 10px;
                            border-bottom: 1px solid var(--border-dim);
                            text-align: left;
                        }
                        .trace-table th {
                            color: var(--gold);
                            font-weight: 600;
                        }
                        .trace-table td.code-cell {
                            font-family: 'JetBrains Mono', monospace;
                            color: #fff;
                        }
                        /* Ledger list */
                        .ledger-list {
                            display: flex;
                            flex-direction: column;
                            gap: 10px;
                        }
                        .ledger-item {
                            padding: 12px 16px;
                            background: rgba(255,255,255,0.02);
                            border: 1px solid var(--border-dim);
                            border-radius: 8px;
                            font-family: 'JetBrains Mono', monospace;
                            font-size: 12px;
                            display: flex;
                            justify-content: space-between;
                        }
                    </style>
                </head>
                <body>
                    <!-- Glowing Background Orbs -->
                    <div style="position: fixed; inset: 0; overflow: hidden; pointer-events: none; z-index: -1;">
                        <div style="position: absolute; top: -40%; left: -20%; width: 80%; height: 80%; border-radius: 50%; background: radial-gradient(circle, rgba(195,156,56,0.12) 0%, rgba(195,156,56,0.02) 50%, transparent 100%); filter: blur(120px); opacity: 0.7; animation: pulse 10s infinite alternate;"></div>
                        <div style="position: absolute; bottom: -30%; right: -10%; width: 60%; height: 60%; border-radius: 50%; background: radial-gradient(circle, rgba(229,193,88,0.08) 0%, rgba(229,193,88,0.01) 50%, transparent 100%); filter: blur(100px); opacity: 0.6; animation: pulse 15s infinite alternate;"></div>
                    </div>
                    <header>
                        <div class="logo-container">
                            <div class="logo-icon"></div>
                            <h1>DRAFTFLOW</h1>
                        </div>
                        <div class="tabs">
                            <button class="tab-btn active" onclick="switchTab('dag')">DAG History</button>
                            <button class="tab-btn" onclick="switchTab('workspace')">Workspace Tools</button>
                            <button class="tab-btn" onclick="switchTab('trace')">Trace Explorer</button>
                            <button class="tab-btn" onclick="switchTab('ledger')">Ledger Log</button>
                        </div>
                        <div class="status-badge">Independent VCS</div>
                    </header>
                    <div class="container">
                        <!-- TAB: DAG -->
                        <div id="tab-dag" class="tab-content active">
                            <div class="panel">
                                <h2>Revision History DAG</h2>
                                <div id="graph-container">
                                    <svg id="dag-svg" width="100%" height="100%" style="min-height:550px;"></svg>
                                    
                                    <div id="detail-panel" class="detail-panel">
                                        <button class="detail-close" onclick="closeDetails()">&times;</button>
                                        <h3 id="det-msg" style="color:var(--gold); font-family:'Outfit'; margin-bottom:10px;">Select a Node</h3>
                                        <div style="display:grid; grid-template-columns: 1fr 1fr; gap:15px; font-size:13px; font-family:'Inter'; margin-bottom: 20px;">
                                            <div>
                                                <span style="color:var(--text-muted);">Revision Hash:</span>
                                                <p id="det-hash" style="font-family:monospace; color:#fff;"></p>
                                            </div>
                                            <div>
                                                <span style="color:var(--text-muted);">Change ID:</span>
                                                <p id="det-change" style="font-family:monospace; color:#fff;"></p>
                                            </div>
                                            <div>
                                                <span style="color:var(--text-muted);">Author:</span>
                                                <p id="det-author" style="color:#fff;"></p>
                                            </div>
                                            <div>
                                                <span style="color:var(--text-muted);">Timestamp:</span>
                                                <p id="det-time" style="color:#fff;"></p>
                                            </div>
                                        </div>
                                        <div style="display:flex; gap:10px;">
                                            <button class="btn btn-sec" onclick="actionSwitchSelected()">Checkout Commit</button>
                                            <button class="btn" onclick="actionRebaseSelected()">Rebase onto this</button>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <div class="panel" style="display:flex; flex-direction:column; gap:25px;">
                                <div>
                                    <h2>Working Copy Status</h2>
                                    <div class="status-card">
                                        <div class="status-grid">
                                            <div class="status-item">
                                                <div class="status-label">Active Branch</div>
                                                <div class="status-value" id="active-branch" style="color:var(--gold);">-</div>
                                            </div>
                                            <div class="status-item">
                                                <div class="status-label">Active Revision</div>
                                                <div class="status-value" id="active-rev" style="font-family:monospace;">-</div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                                <div>
                                    <h2>Modified Files</h2>
                                    <div id="modified-list" class="file-list"></div>
                                </div>
                                <div>
                                    <h2>Conflicts</h2>
                                    <div id="conflict-list" class="file-list"></div>
                                </div>
                            </div>
                        </div>

                        <!-- TAB: WORKSPACE TOOLS -->
                        <div id="tab-workspace" class="tab-content">
                            <div class="panel" style="display:flex; flex-direction:column; gap:25px;">
                                <h2>Interactive VCS Actions</h2>
                                
                                <div class="input-group">
                                    <label>Save Changes (Commit)</label>
                                    <input type="text" id="commit-msg-input" placeholder="Enter commit message...">
                                    <div style="margin-top:8px;">
                                        <button class="btn" onclick="actionSave()">Save Commit</button>
                                    </div>
                                </div>

                                <div class="input-group">
                                    <label>Rebase Current Branch</label>
                                    <input type="text" id="rebase-upstream-input" placeholder="Enter upstream branch or commit hash...">
                                    <div style="margin-top:8px;">
                                        <button class="btn" onclick="actionRebaseInput()">Rebase Onto Target</button>
                                    </div>
                                </div>

                                <div style="display:flex; gap:12px; margin-top:15px; border-top:1px solid var(--border-dim); padding-top:20px;">
                                    <button class="btn btn-danger" onclick="actionClean()">Workspace Sweeper (Clean)</button>
                                    <button class="btn btn-sec" onclick="actionUndo()">Undo Last Commit</button>
                                </div>
                            </div>
                            <div class="panel">
                                <h2>Active Branch Meta</h2>
                                <div class="status-card" style="margin-bottom:0;">
                                    <div class="status-grid" style="gap:20px;">
                                        <div class="status-item">
                                            <div class="status-label">Quick Switch Branch</div>
                                            <input type="text" id="switch-branch-input" placeholder="e.g. main, feature-1" style="margin-bottom:8px;">
                                            <button class="btn btn-sec" onclick="actionSwitchInput()">Switch Branch</button>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- TAB: TRACE EXPLORER -->
                        <div id="tab-trace" class="tab-content">
                            <div class="panel" style="grid-column: span 2;">
                                <h2>Trace Explorer</h2>
                                <p style="color:var(--text-muted); font-size:13px; margin-bottom:20px;">
                                    "Trace" analyzes a file line-by-line, determining the exact commit, author, and timestamp that last modified each line.
                                </p>
                                <div style="display:flex; gap:12px; margin-bottom:20px;">
                                    <input type="text" id="trace-file-input" placeholder="e.g. tracked.txt" style="max-width:350px;">
                                    <button class="btn" onclick="loadTrace()">Trace File</button>
                                </div>
                                <div style="overflow-x:auto;">
                                    <table class="trace-table" id="trace-table" style="display:none;">
                                        <thead>
                                            <tr>
                                                <th style="width:70px;">Line</th>
                                                <th style="width:100px;">Commit</th>
                                                <th style="width:150px;">Author</th>
                                                <th style="width:180px;">Date</th>
                                                <th>Content</th>
                                            </tr>
                                        </thead>
                                        <tbody id="trace-tbody"></tbody>
                                    </table>
                                    <div id="trace-placeholder" style="color:var(--text-muted); font-size:13px; padding:20px 0; text-align:center;">
                                        Enter a file path and click Trace File to begin analysis.
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- TAB: LEDGER -->
                        <div id="tab-ledger" class="tab-content">
                            <div class="panel" style="grid-column: span 2;">
                                <h2>Reference Transaction Log (Ledger)</h2>
                                <p style="color:var(--text-muted); font-size:13px; margin-bottom:20px;">
                                    Chronological log of reference state changes (checkouts, commits, rebases, and undo operations).
                                </p>
                                <div class="ledger-list" id="ledger-container">
                                    <div style="color:var(--text-muted); font-size:13px; text-align:center; padding:20px 0;">No ledger entries logged.</div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div id="toast">
                        <span id="toast-icon">✨</span>
                        <span id="toast-text">Action completed successfully!</span>
                    </div>

                    <script>
                        let nodeCache = {};
                        let selectedHash = null;

                        function showToast(text, isError = false) {
                            const t = document.getElementById('toast');
                            const txt = document.getElementById('toast-text');
                            const icon = document.getElementById('toast-icon');
                            txt.innerText = text;
                            icon.innerText = isError ? '❌' : '✨';
                            t.style.display = 'flex';
                            setTimeout(() => {
                                t.style.display = 'none';
                            }, 4000);
                        }

                        function switchTab(tabId) {
                            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                            
                            const btn = Array.from(document.querySelectorAll('.tab-btn')).find(b => b.innerText.toLowerCase().includes(tabId));
                            if(btn) btn.classList.add('active');
                            
                            const content = document.getElementById('tab-' + tabId);
                            if(content) {
                                content.classList.add('active');
                                if(content.style.display === 'none') content.style.display = 'grid';
                            }
                            if (tabId === 'ledger') {
                                loadLedger();
                            }
                        }

                        async function postAction(url) {
                            try {
                                const res = await fetch(url, { method: 'POST' });
                                const data = await res.json();
                                if(data.error) {
                                    showToast(data.error, true);
                                } else {
                                    showToast(data.message || 'Action executed successfully!');
                                    loadStatus();
                                    loadDag();
                                }
                            } catch(e) {
                                showToast(e.message || 'Error occurred', true);
                            }
                        }

                        function actionSave() {
                            const msg = document.getElementById('commit-msg-input').value;
                            postAction('/api/action?cmd=save&msg=' + encodeURIComponent(msg));
                            document.getElementById('commit-msg-input').value = '';
                        }

                        function actionRebaseInput() {
                            const target = document.getElementById('rebase-upstream-input').value;
                            postAction('/api/action?cmd=rebase&upstream=' + encodeURIComponent(target));
                            document.getElementById('rebase-upstream-input').value = '';
                        }

                        function actionSwitchInput() {
                            const branch = document.getElementById('switch-branch-input').value;
                            postAction('/api/action?cmd=switch&target=' + encodeURIComponent(branch));
                            document.getElementById('switch-branch-input').value = '';
                        }

                        function actionSwitchSelected() {
                            if(!selectedHash) return;
                            postAction('/api/action?cmd=switch&target=' + selectedHash);
                            closeDetails();
                        }

                        function actionRebaseSelected() {
                            if(!selectedHash) return;
                            postAction('/api/action?cmd=rebase&upstream=' + selectedHash);
                            closeDetails();
                        }

                        function actionClean() {
                            postAction('/api/action?cmd=clean');
                        }

                        function actionUndo() {
                            postAction('/api/action?cmd=undo');
                        }

                        async function loadTrace() {
                            const file = document.getElementById('trace-file-input').value;
                            if(!file) {
                                showToast('Please enter a file path', true);
                                return;
                            }
                            try {
                                const res = await fetch('/api/trace?file=' + encodeURIComponent(file));
                                const data = await res.json();
                                if (data.error) {
                                    showToast(data.error, true);
                                    return;
                                }
                                const tbl = document.getElementById('trace-table');
                                const placeholder = document.getElementById('trace-placeholder');
                                const tbody = document.getElementById('trace-tbody');
                                tbody.innerHTML = '';
                                
                                data.forEach((row, idx) => {
                                    tbody.innerHTML += `
                                        <tr>
                                            <td style="color:var(--text-muted);">${idx + 1}</td>
                                            <td style="font-family:monospace; color:var(--gold);">${row.hash}</td>
                                            <td>${row.author}</td>
                                            <td style="color:var(--text-muted);">${row.date}</td>
                                            <td class="code-cell">${escapeHtml(row.line)}</td>
                                        </tr>
                                    `;
                                });
                                placeholder.style.display = 'none';
                                tbl.style.display = 'table';
                            } catch(e) {
                                showToast(e.message || 'Error running trace', true);
                            }
                        }

                        function escapeHtml(str) {
                            return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
                        }

                        async function loadLedger() {
                            try {
                                const res = await fetch('/api/ledger');
                                const data = await res.json();
                                const container = document.getElementById('ledger-container');
                                container.innerHTML = '';
                                if(data.length === 0) {
                                    container.innerHTML = '<div style="color:var(--text-muted); font-size:13px; text-align:center; padding:20px 0;">No ledger entries logged.</div>';
                                    return;
                                }
                                data.reverse().forEach((e, idx) => {
                                    container.innerHTML += `
                                        <div class="ledger-item">
                                            <span style="color:var(--gold); font-weight:bold;">${e.newHash.substring(0,7)}</span>
                                            <span style="color:#fff;">${e.message}</span>
                                        </div>
                                    `;
                                });
                            } catch(e) {
                                showToast('Failed to load ledger', true);
                            }
                        }

                        async function loadStatus() {
                            const res = await fetch('/api/status');
                            const data = await res.json();
                            document.getElementById('active-branch').innerText = data.activeHead || 'detached';
                            document.getElementById('active-rev').innerText = data.activeRevision ? data.activeRevision.substring(0, 12) : 'None';

                            const modContainer = document.getElementById('modified-list');
                            modContainer.innerHTML = '';
                            data.modified.forEach(f => {
                                modContainer.innerHTML += `<div class="file-item modified"><span>M</span><span>${f}</span></div>`;
                            });
                            data.deleted.forEach(f => {
                                modContainer.innerHTML += `<div class="file-item deleted"><span>D</span><span>${f}</span></div>`;
                            });

                            const conflictContainer = document.getElementById('conflict-list');
                            conflictContainer.innerHTML = '';
                            data.conflicts.forEach(f => {
                                conflictContainer.innerHTML += `<div class="file-item conflict"><span>C</span><span>${f}</span></div>`;
                            });
                            if(data.conflicts.length === 0 && data.modified.length === 0 && data.deleted.length === 0) {
                                modContainer.innerHTML = '<span style="color:var(--text-muted); font-size:13px;">Working copy clean.</span>';
                            }
                        }

                        function showDetails(hash) {
                            selectedHash = hash;
                            const n = nodeCache[hash];
                            if(!n) return;
                            document.getElementById('det-msg').innerText = n.message;
                            document.getElementById('det-hash').innerText = n.hash;
                            document.getElementById('det-change').innerText = n.changeId || 'None';
                            document.getElementById('det-author').innerText = n.author;
                            document.getElementById('det-time').innerText = new Date(n.timestamp).toLocaleString();
                            document.getElementById('detail-panel').style.display = 'block';
                        }

                        function closeDetails() {
                            document.getElementById('detail-panel').style.display = 'none';
                            selectedHash = null;
                        }

                        async function loadDag() {
                            const res = await fetch('/api/dag');
                            const nodes = await res.json();
                            const svg = document.getElementById('dag-svg');
                            svg.innerHTML = '';
                            nodeCache = {};

                            if(nodes.length === 0) return;

                            nodes.sort((a,b) => b.timestamp - a.timestamp);

                            const spacingY = 80;
                            const startX = 200;
                            
                            const nodeMap = {};
                            nodes.forEach((n, idx) => {
                                nodeCache[n.hash] = n;
                                nodeMap[n.hash] = {
                                    x: startX + (n.isDraft ? 80 : 0),
                                    y: 60 + idx * spacingY,
                                    data: n
                                };
                            });

                            nodes.forEach(n => {
                                const from = nodeMap[n.hash];
                                n.parents.forEach(pHash => {
                                    const to = nodeMap[pHash];
                                    if(to) {
                                        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
                                        const midY = (from.y + to.y) / 2;
                                        const d = `M ${from.x} ${from.y} C ${from.x} ${midY}, ${to.x} ${midY}, ${to.x} ${to.y}`;
                                        path.setAttribute("d", d);
                                        path.setAttribute("class", "edge-path");
                                        path.setAttribute("stroke", "rgba(212,175,55,0.45)");
                                        path.setAttribute("stroke-width", "2.5");
                                        path.setAttribute("fill", "none");
                                        if(from.data.isDraft) {
                                            path.setAttribute("stroke-dasharray", "5,5");
                                        }
                                        svg.appendChild(path);
                                    }
                                });
                            });

                            nodes.forEach(n => {
                                const coord = nodeMap[n.hash];
                                const group = document.createElementNS("http://www.w3.org/2000/svg", "g");

                                const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
                                circle.setAttribute("cx", coord.x);
                                circle.setAttribute("cy", coord.y);
                                circle.setAttribute("r", n.isDraft ? "10" : "13");
                                circle.setAttribute("fill", n.isDraft ? "transparent" : "#d4af37");
                                circle.setAttribute("stroke", "#d4af37");
                                circle.setAttribute("stroke-width", "3");
                                circle.setAttribute("class", "node-circle");
                                if(n.isDraft) {
                                    circle.setAttribute("stroke-dasharray", "3");
                                }
                                circle.onclick = () => showDetails(n.hash);
                                group.appendChild(circle);

                                const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
                                text.setAttribute("x", coord.x + 22);
                                text.setAttribute("y", coord.y + 4);
                                text.setAttribute("fill", "#ffffff");
                                text.setAttribute("font-size", "12px");
                                text.setAttribute("font-family", "monospace");
                                text.style.cursor = 'pointer';
                                text.onclick = () => showDetails(n.hash);
                                
                                let refLabel = "";
                                if(n.refs.length > 0) {
                                    refLabel = ` [${n.refs.map(r => r.replace('heads/', '')).join(', ')}]`;
                                }

                                text.textContent = `${n.hash.substring(0,8)} - ${n.message}${refLabel}`;
                                group.appendChild(text);

                                svg.appendChild(group);
                            });

                            svg.setAttribute("height", (nodes.length * spacingY + 120) + "px");
                        }

                        loadStatus();
                        loadDag();
                        setInterval(loadStatus, 2000);
                        setInterval(loadDag, 5000);
                    </script>
                </body>
                </html>
                """;
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private class LedgerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                List<com.draftflow.core.ReflogManager.ReflogEntry> entries = com.draftflow.core.ReflogManager.getReflog(cas.getRootDir());
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < entries.size(); i++) {
                    com.draftflow.core.ReflogManager.ReflogEntry e = entries.get(i);
                    sb.append(String.format("{\"oldHash\":\"%s\",\"newHash\":\"%s\",\"author\":\"%s\",\"timestamp\":%d,\"message\":\"%s\"}",
                            e.getOldHash(),
                            e.getNewHash(),
                            e.getAuthor().replace("\"", "\\\""),
                            e.getTimestamp(),
                            e.getMessage().replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
                    ));
                    if (i < entries.size() - 1) sb.append(",");
                }
                sb.append("]");
                byte[] response = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }
    }

    private class TraceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String fileName = null;
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("file") && parts.length > 1) {
                            fileName = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                if (fileName == null) {
                    throw new IllegalArgumentException("Missing file parameter");
                }
                Path fullPath = cas.getRootDir().resolve(fileName).toAbsolutePath().normalize();
                if (!Files.exists(fullPath)) {
                    throw new FileNotFoundException("File not found: " + fileName);
                }
                String relPath = cas.getRootDir().relativize(fullPath).toString().replace('\\', '/');

                String activeRev = db.getConfig("activeRevisionHash");
                if (activeRev == null) {
                    throw new IllegalStateException("No commits in this repository.");
                }

                List<String> currentLines = Files.readAllLines(fullPath, StandardCharsets.UTF_8);
                String[] finalTrace = new String[currentLines.size()];
                for (int i = 0; i < currentLines.size(); i++) {
                    finalTrace[i] = activeRev;
                }

                String currHash = activeRev;
                while (currHash != null) {
                    Revision rev = (Revision) cas.readObject(currHash);
                    if (rev.getParentHashes().isEmpty()) {
                        break;
                    }
                    String parentHash = rev.getParentHashes().get(0);
                    Revision parentRev = (Revision) cas.readObject(parentHash);

                    byte[] parentBytes = getFileContentAtCommit(parentRev.getTreeHash(), relPath);
                    if (parentBytes == null) {
                        break;
                    }
                    List<String> parentLines = Arrays.asList(new String(parentBytes, StandardCharsets.UTF_8).split("\\r?\\n"));

                    for (int i = 0; i < currentLines.size(); i++) {
                        if (finalTrace[i].equals(currHash)) {
                            if (parentLines.contains(currentLines.get(i))) {
                                finalTrace[i] = parentHash;
                            }
                        }
                    }

                    currHash = parentHash;
                }

                List<Map<String, String>> traceList = new ArrayList<>();
                for (int i = 0; i < currentLines.size(); i++) {
                    String bh = finalTrace[i];
                    Revision r = (Revision) cas.readObject(bh);
                    String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(r.getTimestamp()));
                    Map<String, String> item = new HashMap<>();
                    item.put("hash", bh);
                    item.put("author", r.getAuthor());
                    item.put("date", dateStr);
                    item.put("line", currentLines.get(i));
                    traceList.add(item);
                }

                byte[] response = GSON.toJson(traceList).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }
    }

    private class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                String cmd = params.get("cmd");
                if (cmd == null) {
                    throw new IllegalArgumentException("Missing cmd parameter");
                }

                String message = "Success";
                if (cmd.equals("clean")) {
                    com.draftflow.DraftFlow.CleanCmd clean = new com.draftflow.DraftFlow.CleanCmd();
                    java.lang.reflect.Field fForce = clean.getClass().getDeclaredField("force");
                    fForce.setAccessible(true);
                    fForce.set(clean, true);

                    java.lang.reflect.Field fDirs = clean.getClass().getDeclaredField("removeDirs");
                    fDirs.setAccessible(true);
                    fDirs.set(clean, true);

                    java.lang.reflect.Field fIgnored = clean.getClass().getDeclaredField("cleanIgnored");
                    fIgnored.setAccessible(true);
                    fIgnored.set(clean, true);

                    int res = executeCommandWithDbClosed(clean);
                    if (res != 0) throw new RuntimeException("Clean returned code: " + res);
                    message = "Workspace successfully swept clean of all untracked files and directories!";
                } else if (cmd.equals("undo")) {
                    com.draftflow.DraftFlow.UndoCmd undo = new com.draftflow.DraftFlow.UndoCmd();
                    int res = executeCommandWithDbClosed(undo);
                    if (res != 0) throw new RuntimeException("Undo returned code: " + res);
                    message = "Successfully undid last commit!";
                } else if (cmd.equals("select-repo")) {
                    String id = params.get("id");
                    if (id == null) throw new IllegalArgumentException("Missing id parameter");
                    Path currentRepoDir = cas.getRootDir();
                    Path parentDir = currentRepoDir.getParent();
                    if (parentDir != null) {
                        Path targetRepoDir = parentDir.resolve(id);
                        if (Files.exists(targetRepoDir) && Files.isDirectory(targetRepoDir) && Files.exists(targetRepoDir.resolve(".draftflow"))) {
                            if (db != null) {
                                db.close();
                            }
                            CAS newCas = new CAS(targetRepoDir);
                            Path dbPath = newCas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                            MetadataStore newDb = new MetadataStore(dbPath);
                            newDb.open();
                            cas = newCas;
                            db = newDb;
                            message = "Successfully switched workspace repository to: " + id;
                        } else {
                            throw new IllegalArgumentException("Repository not found or invalid: " + id);
                        }
                    } else {
                        throw new IllegalStateException("Parent directory unavailable for workspace resolution.");
                    }
                } else if (cmd.equals("switch") || cmd.equals("checkout")) {
                    String target = params.get("target");
                    if (target == null) {
                        target = params.get("branch");
                    }
                    if (target == null) throw new IllegalArgumentException("Missing target or branch parameter");
                    com.draftflow.DraftFlow.SwitchCmd sw = new com.draftflow.DraftFlow.SwitchCmd();
                    java.lang.reflect.Field fRev = sw.getClass().getDeclaredField("revisionHash");
                    fRev.setAccessible(true);
                    fRev.set(sw, target);
                    int res = executeCommandWithDbClosed(sw);
                    if (res != 0) throw new RuntimeException("Switch/Checkout returned code: " + res);
                    message = "Successfully checked out " + target;
                } else if (cmd.equals("save")) {
                    String msg = params.get("msg");
                    if (msg == null || msg.trim().isEmpty()) {
                        msg = "Commit from Web GUI";
                    }
                    com.draftflow.DraftFlow.SaveCmd save = new com.draftflow.DraftFlow.SaveCmd();
                    java.lang.reflect.Field fMsg = save.getClass().getDeclaredField("message");
                    fMsg.setAccessible(true);
                    fMsg.set(save, msg);
                    int res = executeCommandWithDbClosed(save);
                    if (res != 0) throw new RuntimeException("Save returned code: " + res);
                    message = "Changes successfully saved with message: " + msg;
                } else if (cmd.equals("rebase")) {
                    String upstream = params.get("upstream");
                    if (upstream == null) throw new IllegalArgumentException("Missing upstream parameter");
                    com.draftflow.DraftFlow.RebaseCmd rebase = new com.draftflow.DraftFlow.RebaseCmd();
                    java.lang.reflect.Field fUp = rebase.getClass().getDeclaredField("upstream");
                    fUp.setAccessible(true);
                    fUp.set(rebase, upstream);
                    int res = executeCommandWithDbClosed(rebase);
                    if (res != 0) throw new RuntimeException("Rebase returned code: " + res);
                    message = "Successfully rebased current branch onto " + upstream;
                } else if (cmd.equals("prune")) {
                    com.draftflow.DraftFlow.PruneCmd prune = new com.draftflow.DraftFlow.PruneCmd();
                    int res = executeCommandWithDbClosed(prune);
                    if (res != 0) throw new RuntimeException("Prune returned code: " + res);
                    message = "Successfully pruned unreachable objects from the CAS store!";
                } else if (cmd.equals("resolve")) {
                    String fileName = params.get("file");
                    String resolution = params.get("resolution"); // "ours", "theirs", "both"
                    if (fileName == null || resolution == null) {
                        throw new IllegalArgumentException("Missing file or resolution parameter");
                    }
                    FileMetadata f = db.getFile(fileName);
                    if (f == null || !f.getType().equals(ObjectType.CONFLICT.name())) {
                        throw new IllegalArgumentException("File is not in conflicted state: " + fileName);
                    }
                    ConflictNode node = (ConflictNode) cas.readObject(f.getHash());
                    Path path = cas.getRootDir().resolve(f.getPath());

                    if (resolution.equals("ours")) {
                        if (node.getLeftHash() == null) {
                            db.removeFile(f.getPath());
                            Files.deleteIfExists(path);
                            message = "Resolved " + f.getPath() + " by deleting it (OURS).";
                        } else {
                            byte[] content = readBlobOrChunkTree(node.getLeftHash());
                            Files.write(path, content);
                            long size = Files.size(path);
                            long lastMod = Files.getLastModifiedTime(path).toMillis();
                            FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, node.getLeftHash(), ObjectType.BLOB.name(), f.getMode());
                            db.putFile(resolved);
                            message = "Resolved " + f.getPath() + " using OURS version.";
                        }
                    } else if (resolution.equals("theirs")) {
                        if (node.getRightHash() == null) {
                            db.removeFile(f.getPath());
                            Files.deleteIfExists(path);
                            message = "Resolved " + f.getPath() + " by deleting it (THEIRS).";
                        } else {
                            byte[] content = readBlobOrChunkTree(node.getRightHash());
                            Files.write(path, content);
                            long size = Files.size(path);
                            long lastMod = Files.getLastModifiedTime(path).toMillis();
                            FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, node.getRightHash(), ObjectType.BLOB.name(), f.getMode());
                            db.putFile(resolved);
                            message = "Resolved " + f.getPath() + " using THEIRS version.";
                        }
                    } else if (resolution.equals("both")) {
                        byte[] leftBytes = readBlobOrChunkTree(node.getLeftHash());
                        byte[] rightBytes = readBlobOrChunkTree(node.getRightHash());
                        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
                        outBytes.write(leftBytes);
                        outBytes.write("\n".getBytes(StandardCharsets.UTF_8));
                        outBytes.write(rightBytes);
                        byte[] mergedBytes = outBytes.toByteArray();

                        Files.write(path, mergedBytes);

                        String newBlobHash;
                        String typeStr;
                        if (mergedBytes.length > 1024 * 1024) {
                            List<com.draftflow.cdc.FastCDC.Chunk> chunks = com.draftflow.cdc.FastCDC.chunk(mergedBytes);
                            List<String> chunkHashes = new ArrayList<>();
                            List<Integer> chunkSizes = new ArrayList<>();
                            for (com.draftflow.cdc.FastCDC.Chunk chunk : chunks) {
                                byte[] cb = chunk.getBytes();
                                Blob cblob = new Blob(cb);
                                chunkHashes.add(cas.writeObject(cblob));
                                chunkSizes.add(cb.length);
                            }
                            ChunkTree ct = new ChunkTree(chunkHashes, chunkSizes, mergedBytes.length);
                            newBlobHash = cas.writeObject(ct);
                            typeStr = ObjectType.CHUNK_TREE.name();
                        } else {
                            Blob blob = new Blob(mergedBytes);
                            newBlobHash = cas.writeObject(blob);
                            typeStr = ObjectType.BLOB.name();
                        }

                        long size = Files.size(path);
                        long lastMod = Files.getLastModifiedTime(path).toMillis();
                        FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, newBlobHash, typeStr, f.getMode());
                        db.putFile(resolved);
                        message = "Resolved " + f.getPath() + " by keeping both versions.";
                    } else if (resolution.equals("custom")) {
                        byte[] mergedBytes;
                        try (InputStream reqBody = exchange.getRequestBody()) {
                            mergedBytes = reqBody.readAllBytes();
                        }
                        Files.write(path, mergedBytes);

                        String newBlobHash;
                        String typeStr;
                        if (mergedBytes.length > 1024 * 1024) {
                            List<com.draftflow.cdc.FastCDC.Chunk> chunks = com.draftflow.cdc.FastCDC.chunk(mergedBytes);
                            List<String> chunkHashes = new ArrayList<>();
                            List<Integer> chunkSizes = new ArrayList<>();
                            for (com.draftflow.cdc.FastCDC.Chunk chunk : chunks) {
                                byte[] cb = chunk.getBytes();
                                Blob cblob = new Blob(cb);
                                chunkHashes.add(cas.writeObject(cblob));
                                chunkSizes.add(cb.length);
                            }
                            ChunkTree ct = new ChunkTree(chunkHashes, chunkSizes, mergedBytes.length);
                            newBlobHash = cas.writeObject(ct);
                            typeStr = ObjectType.CHUNK_TREE.name();
                        } else {
                            Blob blob = new Blob(mergedBytes);
                            newBlobHash = cas.writeObject(blob);
                            typeStr = ObjectType.BLOB.name();
                        }

                        long size = Files.size(path);
                        long lastMod = Files.getLastModifiedTime(path).toMillis();
                        FileMetadata resolved = new FileMetadata(f.getPath(), size, lastMod, newBlobHash, typeStr, f.getMode());
                        db.putFile(resolved);
                        message = "Resolved " + f.getPath() + " using custom merge resolution.";
                    } else {
                        throw new IllegalArgumentException("Unknown resolution: " + resolution);
                    }

                    // Scan and commit/save changes to shadows
                    Set<Path> scanned = new HashSet<>();
                    for (FileMetadata fileMeta : db.getAllFiles()) {
                        scanned.add(cas.getRootDir().resolve(fileMeta.getPath()));
                    }
                    WorkspaceManager wm = new WorkspaceManager(cas, db);
                    wm.scanAndCreateShadowCommit(scanned);
                    db.commit();
                } else if (cmd.equals("switch-repo")) {
                    String repoName = params.get("repo");
                    if (repoName == null) throw new IllegalArgumentException("Missing repo parameter");
                    Path parent = cas.getRootDir().getParent();
                    if (parent == null) throw new IllegalStateException("Parent directory not found");
                    Path targetRepo = parent.resolve(repoName);
                    if (!Files.exists(targetRepo) || !Files.exists(targetRepo.resolve(".draftflow"))) {
                        throw new IllegalArgumentException("Target repository not found or not a DraftFlow repository: " + repoName);
                    }
                    synchronized (UiServer.this) {
                        db.close();
                        CAS newCas = new CAS(targetRepo);
                        Path dbPath = newCas.getDraftFlowDir().resolve("index").resolve("index.mv.db");
                        MetadataStore newDb = new MetadataStore(dbPath);
                        newDb.open();
                        
                        UiServer.this.cas = newCas;
                        UiServer.this.db = newDb;
                    }
                    message = "Successfully switched workspace to " + repoName;
                } else if (cmd.equals("branch")) {
                    String createBranch = params.get("create");
                    String deleteBranchName = params.get("delete");
                    com.draftflow.DraftFlow.BranchCmd br = new com.draftflow.DraftFlow.BranchCmd();
                    if (createBranch != null && !createBranch.trim().isEmpty()) {
                        java.lang.reflect.Field fNew = br.getClass().getDeclaredField("newBranch");
                        fNew.setAccessible(true);
                        fNew.set(br, createBranch);
                        int res = executeCommandWithDbClosed(br);
                        if (res != 0) throw new RuntimeException("Branch creation returned code: " + res);
                        message = "Successfully created branch: " + createBranch;
                    } else if (deleteBranchName != null && !deleteBranchName.trim().isEmpty()) {
                        java.lang.reflect.Field fDel = br.getClass().getDeclaredField("deleteBranch");
                        fDel.setAccessible(true);
                        fDel.set(br, deleteBranchName);
                        int res = executeCommandWithDbClosed(br);
                        if (res != 0) throw new RuntimeException("Branch deletion returned code: " + res);
                        message = "Successfully deleted branch: " + deleteBranchName;
                    } else {
                        throw new IllegalArgumentException("Missing create or delete parameter for branch command");
                    }
                } else if (cmd.equals("merge")) {
                    String revision = params.get("revision");
                    if (revision == null || revision.trim().isEmpty()) {
                        throw new IllegalArgumentException("Missing revision parameter for merge");
                    }
                    com.draftflow.DraftFlow.MergeCmd merge = new com.draftflow.DraftFlow.MergeCmd();
                    java.lang.reflect.Field fTarget = merge.getClass().getDeclaredField("target");
                    fTarget.setAccessible(true);
                    fTarget.set(merge, revision);
                    int res = executeCommandWithDbClosed(merge);
                    if (res != 0) throw new RuntimeException("Merge returned code: " + res);
                    message = "Successfully merged: " + revision;
                } else {
                    throw new IllegalArgumentException("Unknown command: " + cmd);
                }

                byte[] response = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }

    }

    private byte[] readBlobOrChunkTree(String hash) throws IOException {
        if (hash == null) return new byte[0];
        DraftFlowObject obj = cas.readObject(hash);
        if (obj == null) return new byte[0];
        if (obj.getType() == ObjectType.CHUNK_TREE) {
            ChunkTree ct = (ChunkTree) obj;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String ch : ct.getChunkHashes()) {
                Blob b = (Blob) cas.readObject(ch);
                out.write(b.getContent());
            }
            return out.toByteArray();
        } else if (obj.getType() == ObjectType.BLOB) {
            Blob b = (Blob) obj;
            return b.getContent();
        }
        return new byte[0];
    }

    private class ConflictDetailsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                String fileName = null;
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("file") && parts.length > 1) {
                            fileName = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                if (fileName == null) {
                    throw new IllegalArgumentException("Missing file parameter");
                }

                FileMetadata fm = db.getFile(fileName);
                if (fm == null || !fm.getType().equals(ObjectType.CONFLICT.name())) {
                    throw new IllegalArgumentException("File is not in a conflicted state: " + fileName);
                }

                ConflictNode node = (ConflictNode) cas.readObject(fm.getHash());
                byte[] leftBytes = readBlobOrChunkTree(node.getLeftHash());
                byte[] rightBytes = readBlobOrChunkTree(node.getRightHash());
                byte[] ancestorBytes = readBlobOrChunkTree(node.getAncestorHash());

                String leftStr = new String(leftBytes, StandardCharsets.UTF_8);
                String rightStr = new String(rightBytes, StandardCharsets.UTF_8);
                String ancestorStr = new String(ancestorBytes, StandardCharsets.UTF_8);

                String escapeLeft = escapeJson(leftStr);
                String escapeRight = escapeJson(rightStr);
                String escapeAncestor = escapeJson(ancestorStr);

                String json = String.format(
                    "{\"file\":\"%s\",\"left\":\"%s\",\"right\":\"%s\",\"ancestor\":\"%s\"}",
                    fileName.replace("\\", "\\\\").replace("\"", "\\\""),
                    escapeLeft,
                    escapeRight,
                    escapeAncestor
                );

                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }

        private String escapeJson(String str) {
            if (str == null) return "";
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\b", "\\b")
                      .replace("\f", "\\f")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t");
        }
    }

    private class FileContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                String fileName = null;
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("file") && parts.length > 1) {
                            fileName = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                if (fileName == null) {
                    throw new IllegalArgumentException("Missing file parameter");
                }
                Path fullPath = cas.getRootDir().resolve(fileName).toAbsolutePath().normalize();
                if (!Files.exists(fullPath)) {
                    throw new FileNotFoundException("File not found: " + fileName);
                }
                
                // Get current content
                String currentContent = Files.readString(fullPath, StandardCharsets.UTF_8);
                
                // Get original content (from active revision)
                String originalContent = "";
                String activeRev = db.getConfig("activeRevisionHash");
                if (activeRev != null) {
                    Revision rev = (Revision) cas.readObject(activeRev);
                    String relPath = cas.getRootDir().relativize(fullPath).toString().replace('\\', '/');
                    byte[] originalBytes = getFileContentAtCommit(rev.getTreeHash(), relPath);
                    if (originalBytes != null) {
                        originalContent = new String(originalBytes, StandardCharsets.UTF_8);
                    }
                }
                
                String json = String.format(
                    "{\"file\":\"%s\",\"original\":\"%s\",\"modified\":\"%s\"}",
                    fileName.replace("\\", "\\\\").replace("\"", "\\\""),
                    escapeJson(originalContent),
                    escapeJson(currentContent)
                );
                
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                byte[] response = ("{\"error\": \"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        }

        private String escapeJson(String str) {
            if (str == null) return "";
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\b", "\\b")
                      .replace("\f", "\\f")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t");
        }
    }

    private byte[] getFileContentAtCommit(String treeHash, String relPath) throws IOException {
        String[] parts = relPath.split("/");

        String currentTreeHash = treeHash;
        for (int i = 0; i < parts.length; i++) {
            Tree currentTree = (Tree) cas.readObject(currentTreeHash);
            String part = parts[i];
            TreeEntry entry = currentTree.getEntries().stream()
                    .filter(e -> e.getName().equals(part))
                    .findFirst().orElse(null);
            if (entry == null) {
                return null;
            }
            if (i == parts.length - 1) {
                if (entry.getType() == ObjectType.CHUNK_TREE) {
                    ChunkTree ct = (ChunkTree) cas.readObject(entry.getHash());
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    for (String ch : ct.getChunkHashes()) {
                        Blob b = (Blob) cas.readObject(ch);
                        out.write(b.getContent());
                    }
                    return out.toByteArray();
                } else {
                    Blob b = (Blob) cas.readObject(entry.getHash());
                    return b.getContent();
                }
            } else {
                if (entry.getType() != ObjectType.TREE) {
                    return null;
                }
                currentTreeHash = entry.getHash();
            }
        }
        return null;
    }

    protected java.io.InputStream getResourceAsStream(String path) {
        return getClass().getResourceAsStream(path);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1) {
                try {
                    result.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            } else {
                result.put(pair[0], "");
            }
        }
        return result;
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-User-Email");
    }

    private class SignupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                String email = jo.get("email").getAsString();
                if (email == null || !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Invalid email format.\"}");
                    return;
                }
                if (db.getUser(email) != null) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"User already exists.\"}");
                    return;
                }
                if (!jo.has("id")) {
                    jo.addProperty("id", String.valueOf(System.currentTimeMillis()));
                }
                if (!jo.has("joinedDate")) {
                    jo.addProperty("joinedDate", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
                }
                if (!jo.has("repositoryCount")) {
                    jo.addProperty("repositoryCount", 12);
                }
                db.putUser(email, GSON.toJson(jo));
                
                String name = jo.has("name") ? jo.get("name").getAsString() : (jo.has("fullName") ? jo.get("fullName").getAsString() : null);
                if (name != null) {
                    db.setConfig("author.name", name);
                }
                db.setConfig("author.email", email);

                db.commit();
                sendJsonResponse(exchange, 200, GSON.toJson(jo));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                String email = jo.get("email").getAsString();
                String password = jo.get("password").getAsString();
                String userJson = db.getUser(email);
                if (userJson == null) {
                    sendJsonResponse(exchange, 401, "{\"error\":\"Invalid email or password.\"}");
                    return;
                }
                JsonObject userObj = JsonParser.parseString(userJson).getAsJsonObject();
                if (!userObj.get("password").getAsString().equals(password)) {
                    sendJsonResponse(exchange, 401, "{\"error\":\"Invalid email or password.\"}");
                    return;
                }

                String name = userObj.has("name") ? userObj.get("name").getAsString() : (userObj.has("fullName") ? userObj.get("fullName").getAsString() : null);
                if (name != null) {
                    db.setConfig("author.name", name);
                }
                db.setConfig("author.email", email);
                db.commit();

                sendJsonResponse(exchange, 200, userJson);
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class ProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String authEmail = exchange.getRequestHeaders().getFirst("X-User-Email");
            if (authEmail == null || authEmail.isEmpty() || db.getUser(authEmail) == null) {
                sendJsonResponse(exchange, 401, "{\"error\":\"Unauthorized: Session user is invalid or missing.\"}");
                return;
            }

            String email = null;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                Map<String, String> params = parseQuery(query);
                email = params.get("email");
            }
            if (email == null || email.isEmpty()) {
                email = authEmail;
            }

            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String userJson = db.getUser(email);
                if (userJson == null) {
                    sendJsonResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                    return;
                }
                sendJsonResponse(exchange, 200, userJson);
            } else if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                try {
                    String body = readRequestBody(exchange);
                    JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                    String reqEmail = jo.has("email") ? jo.get("email").getAsString() : email;
                    if (reqEmail == null || reqEmail.isEmpty()) {
                        sendJsonResponse(exchange, 400, "{\"error\":\"Missing email parameter.\"}");
                        return;
                    }
                    if (!reqEmail.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                        sendJsonResponse(exchange, 400, "{\"error\":\"Invalid email format.\"}");
                        return;
                    }
                    if (!reqEmail.equalsIgnoreCase(authEmail)) {
                        sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden: You cannot modify another user's profile.\"}");
                        return;
                    }

                    String existingJson = db.getUser(reqEmail);
                    JsonObject mergedObj;
                    if (existingJson != null) {
                        mergedObj = JsonParser.parseString(existingJson).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> entry : jo.entrySet()) {
                            mergedObj.add(entry.getKey(), entry.getValue());
                        }
                    } else {
                        mergedObj = jo;
                    }
                    db.putUser(reqEmail, GSON.toJson(mergedObj));

                    String name = mergedObj.has("name") ? mergedObj.get("name").getAsString() : (mergedObj.has("fullName") ? mergedObj.get("fullName").getAsString() : null);
                    if (name != null) {
                        db.setConfig("author.name", name);
                    }
                    db.setConfig("author.email", reqEmail);

                    db.commit();
                    sendJsonResponse(exchange, 200, GSON.toJson(mergedObj));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private class PullRequestsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                JsonArray arr = new JsonArray();
                for (String prJson : db.getAllPullRequests()) {
                    arr.add(JsonParser.parseString(prJson));
                }
                sendJsonResponse(exchange, 200, GSON.toJson(arr));
            } else if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                try {
                    String body = readRequestBody(exchange);
                    JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                    int maxNum = 0;
                    for (String prJson : db.getAllPullRequests()) {
                        try {
                            JsonObject existingPr = JsonParser.parseString(prJson).getAsJsonObject();
                            int num = existingPr.get("number").getAsInt();
                            if (num > maxNum) maxNum = num;
                        } catch (Exception ignored) {}
                    }
                    int nextNum = maxNum + 1;
                    String prId = "pr-" + nextNum;
                    jo.addProperty("id", prId);
                    jo.addProperty("number", nextNum);
                    jo.addProperty("status", "open");
                    String nowStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
                    jo.addProperty("createdAt", nowStr);
                    jo.addProperty("updatedAt", nowStr);
                    if (!jo.has("commits")) jo.addProperty("commits", 0);
                    if (!jo.has("additions")) jo.addProperty("additions", 0);
                    if (!jo.has("deletions")) jo.addProperty("deletions", 0);
                    if (!jo.has("reviewers")) jo.add("reviewers", new JsonArray());
                    if (!jo.has("labels")) jo.add("labels", new JsonArray());
                    if (!jo.has("comments")) jo.add("comments", new JsonArray());
                    db.putPullRequest(prId, GSON.toJson(jo));
                    db.commit();
                    sendJsonResponse(exchange, 200, GSON.toJson(jo));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private class PullRequestMergeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject requestObj = JsonParser.parseString(body).getAsJsonObject();
                String prId = requestObj.get("id").getAsString();
                String prJson = db.getPullRequest(prId);
                if (prJson == null) {
                    sendJsonResponse(exchange, 404, "{\"error\":\"Pull Request not found.\"}");
                    return;
                }
                JsonObject prObj = JsonParser.parseString(prJson).getAsJsonObject();
                String sourceBranch = prObj.get("sourceBranch").getAsString();
                String targetBranch = prObj.get("targetBranch").getAsString();
                com.draftflow.DraftFlow.SwitchCmd sw = new com.draftflow.DraftFlow.SwitchCmd();
                java.lang.reflect.Field fRev = sw.getClass().getDeclaredField("revisionHash");
                fRev.setAccessible(true);
                fRev.set(sw, targetBranch);
                executeCommandWithDbClosed(sw);
                com.draftflow.DraftFlow.MergeCmd merge = new com.draftflow.DraftFlow.MergeCmd();
                java.lang.reflect.Field fTarget = merge.getClass().getDeclaredField("target");
                fTarget.setAccessible(true);
                fTarget.set(merge, sourceBranch);
                int mergeRes = executeCommandWithDbClosed(merge);
                if (mergeRes != 0) {
                    sendJsonResponse(exchange, 409, "{\"error\":\"Merge conflict occurred. Please resolve conflicts first.\"}");
                    return;
                }
                prObj.addProperty("status", "merged");
                prObj.addProperty("updatedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                db.putPullRequest(prId, GSON.toJson(prObj));
                db.commit();
                sendJsonResponse(exchange, 200, GSON.toJson(prObj));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class PullRequestCloseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject requestObj = JsonParser.parseString(body).getAsJsonObject();
                String prId = requestObj.get("id").getAsString();
                String prJson = db.getPullRequest(prId);
                if (prJson == null) {
                    sendJsonResponse(exchange, 404, "{\"error\":\"Pull Request not found.\"}");
                    return;
                }
                JsonObject prObj = JsonParser.parseString(prJson).getAsJsonObject();
                prObj.addProperty("status", "closed");
                prObj.addProperty("updatedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                db.putPullRequest(prId, GSON.toJson(prObj));
                db.commit();
                sendJsonResponse(exchange, 200, GSON.toJson(prObj));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class PullRequestCommentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject requestObj = JsonParser.parseString(body).getAsJsonObject();
                String prId = requestObj.get("id").getAsString();
                JsonObject commentObj = requestObj.get("comment").getAsJsonObject();
                String prJson = db.getPullRequest(prId);
                if (prJson == null) {
                    sendJsonResponse(exchange, 404, "{\"error\":\"Pull Request not found.\"}");
                    return;
                }
                JsonObject prObj = JsonParser.parseString(prJson).getAsJsonObject();
                JsonArray comments = prObj.has("comments") ? prObj.get("comments").getAsJsonArray() : new JsonArray();
                if (!commentObj.has("id")) {
                    commentObj.addProperty("id", "c-" + System.currentTimeMillis());
                }
                if (!commentObj.has("createdAt")) {
                    commentObj.addProperty("createdAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                }
                comments.add(commentObj);
                prObj.add("comments", comments);
                prObj.addProperty("updatedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                db.putPullRequest(prId, GSON.toJson(prObj));
                db.commit();
                sendJsonResponse(exchange, 200, GSON.toJson(prObj));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class SettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                JsonObject jo = new JsonObject();
                jo.addProperty("requiresCodeReview", Boolean.parseBoolean(db.getConfig("settings.requiresCodeReview")));
                jo.addProperty("requiresStatusChecks", Boolean.parseBoolean(db.getConfig("settings.requiresStatusChecks")));
                jo.addProperty("dismissesStaleReviews", Boolean.parseBoolean(db.getConfig("settings.dismissesStaleReviews")));
                jo.addProperty("defaultBranch", db.getConfig("settings.defaultBranch") != null ? db.getConfig("settings.defaultBranch") : "main");
                jo.addProperty("authorName", db.getConfig("author.name") != null ? db.getConfig("author.name") : "");
                jo.addProperty("authorEmail", db.getConfig("author.email") != null ? db.getConfig("author.email") : "");
                sendJsonResponse(exchange, 200, GSON.toJson(jo));
            } else if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                try {
                    String body = readRequestBody(exchange);
                    JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                    if (jo.has("requiresCodeReview")) db.setConfig("settings.requiresCodeReview", String.valueOf(jo.get("requiresCodeReview").getAsBoolean()));
                    if (jo.has("requiresStatusChecks")) db.setConfig("settings.requiresStatusChecks", String.valueOf(jo.get("requiresStatusChecks").getAsBoolean()));
                    if (jo.has("dismissesStaleReviews")) db.setConfig("settings.dismissesStaleReviews", String.valueOf(jo.get("dismissesStaleReviews").getAsBoolean()));
                    if (jo.has("defaultBranch")) db.setConfig("settings.defaultBranch", jo.get("defaultBranch").getAsString());
                    if (jo.has("authorName")) db.setConfig("author.name", jo.get("authorName").getAsString());
                    if (jo.has("authorEmail")) {
                        String emailVal = jo.get("authorEmail").getAsString();
                        if (emailVal != null && !emailVal.trim().isEmpty() && !emailVal.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                            sendJsonResponse(exchange, 400, "{\"error\":\"Invalid author email format.\"}");
                            return;
                        }
                        db.setConfig("author.email", emailVal);
                    }
                    db.commit();
                    sendJsonResponse(exchange, 200, "{\"message\":\"Settings updated successfully.\"}");
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private class RepositoriesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                Path currentRepoDir = cas.getRootDir();
                Path parentDir = currentRepoDir.getParent();
                List<JsonObject> list = new ArrayList<>();
                if (parentDir != null && Files.exists(parentDir)) {
                    try (var stream = Files.list(parentDir)) {
                        stream.filter(Files::isDirectory).forEach(p -> {
                            if (Files.exists(p.resolve(".draftflow"))) {
                                JsonObject obj = new JsonObject();
                                String name = p.getFileName().toString();
                                obj.addProperty("id", name);
                                obj.addProperty("name", name);
                                obj.addProperty("visibility", "public");
                                obj.addProperty("description", "VCS repository.");
                                obj.addProperty("defaultBranch", "main");
                                obj.addProperty("updatedAt", "recently");
                                JsonObject stats = new JsonObject();
                                stats.addProperty("totalCommits", 0);
                                stats.addProperty("totalBranches", 1);
                                stats.addProperty("totalContributors", 1);
                                obj.add("statistics", stats);
                                list.add(obj);
                            }
                        });
                    }
                }
                if (list.isEmpty()) {
                    JsonObject obj = new JsonObject();
                    String name = currentRepoDir.getFileName().toString();
                    obj.addProperty("id", name);
                    obj.addProperty("name", name);
                    obj.addProperty("visibility", "public");
                    obj.addProperty("description", "VCS repository.");
                    obj.addProperty("defaultBranch", "main");
                    obj.addProperty("updatedAt", "recently");
                    JsonObject stats = new JsonObject();
                    stats.addProperty("totalCommits", 0);
                    stats.addProperty("totalBranches", 1);
                    stats.addProperty("totalContributors", 1);
                    obj.add("statistics", stats);
                    list.add(obj);
                }
                sendJsonResponse(exchange, 200, GSON.toJson(list));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class CreateRepositoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                String name = jo.get("name").getAsString();
                Path currentRepoDir = cas.getRootDir();
                Path newRepoDir = currentRepoDir.getParent().resolve(name);
                if (Files.exists(newRepoDir)) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Repository folder already exists.\"}");
                    return;
                }
                Files.createDirectories(newRepoDir);
                String oldProp = System.getProperty("draftflow.dir");
                System.setProperty("draftflow.dir", newRepoDir.toAbsolutePath().toString());
                int res;
                try {
                    com.draftflow.DraftFlow.SetupCmd setup = new com.draftflow.DraftFlow.SetupCmd();
                    res = setup.call();
                } finally {
                    if (oldProp != null) {
                        System.setProperty("draftflow.dir", oldProp);
                    } else {
                        System.clearProperty("draftflow.dir");
                    }
                }
                if (res != 0) {
                    sendJsonResponse(exchange, 500, "{\"error\":\"Failed to initialize VCS repository.\"}");
                    return;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", name);
                obj.addProperty("name", name);
                obj.addProperty("visibility", jo.has("visibility") ? jo.get("visibility").getAsString() : "public");
                obj.addProperty("description", jo.has("description") ? jo.get("description").getAsString() : "");
                obj.addProperty("defaultBranch", "main");
                sendJsonResponse(exchange, 200, GSON.toJson(obj));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private void walkTree(String treeHash, String basePath, List<Map<String, Object>> files) throws IOException {
        if (treeHash == null) return;
        Tree tree = (Tree) cas.readObject(treeHash);
        if (tree == null || tree.getEntries() == null) return;
        for (TreeEntry entry : tree.getEntries()) {
            String fullPath = basePath.isEmpty() ? entry.getName() : basePath + "/" + entry.getName();
            if (entry.getType() == ObjectType.TREE) {
                walkTree(entry.getHash(), fullPath, files);
            } else {
                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", entry.getName());
                fileMap.put("path", fullPath);
                fileMap.put("type", "file");
                fileMap.put("blobId", entry.getHash());
                byte[] content = readBlobOrChunkTree(entry.getHash());
                fileMap.put("size", content != null ? content.length : 0);
                String ext = "";
                int lastDot = entry.getName().lastIndexOf('.');
                if (lastDot > 0) {
                    ext = entry.getName().substring(lastDot + 1);
                }
                fileMap.put("extension", ext);
                files.add(fileMap);
            }
        }
    }

    private List<Map<String, Object>> compareTrees(String treeHash1, String treeHash2) throws IOException {
        List<Map<String, Object>> files1 = new ArrayList<>();
        if (treeHash1 != null) {
            walkTree(treeHash1, "", files1);
        }
        List<Map<String, Object>> files2 = new ArrayList<>();
        if (treeHash2 != null) {
            walkTree(treeHash2, "", files2);
        }

        Map<String, Map<String, Object>> map1 = new HashMap<>();
        for (Map<String, Object> f : files1) {
            map1.put((String) f.get("path"), f);
        }
        Map<String, Map<String, Object>> map2 = new HashMap<>();
        for (Map<String, Object> f : files2) {
            map2.put((String) f.get("path"), f);
        }

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(map1.keySet());
        allPaths.addAll(map2.keySet());

        List<Map<String, Object>> diff = new ArrayList<>();
        for (String path : allPaths) {
            Map<String, Object> f1 = map1.get(path);
            Map<String, Object> f2 = map2.get(path);

            if (f1 == null) {
                Map<String, Object> item = new HashMap<>();
                item.put("fileName", path);
                item.put("status", "added");
                item.put("oldContent", new ArrayList<>());
                byte[] bytes = readBlobOrChunkTree((String) f2.get("blobId"));
                String str = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
                item.put("newContent", splitLines(str));
                diff.add(item);
            } else if (f2 == null) {
                Map<String, Object> item = new HashMap<>();
                item.put("fileName", path);
                item.put("status", "deleted");
                byte[] bytes = readBlobOrChunkTree((String) f1.get("blobId"));
                String str = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
                item.put("oldContent", splitLines(str));
                item.put("newContent", new ArrayList<>());
                diff.add(item);
            } else if (!f1.get("blobId").equals(f2.get("blobId"))) {
                Map<String, Object> item = new HashMap<>();
                item.put("fileName", path);
                item.put("status", "modified");
                byte[] bytes1 = readBlobOrChunkTree((String) f1.get("blobId"));
                String str1 = bytes1 != null ? new String(bytes1, StandardCharsets.UTF_8) : "";
                item.put("oldContent", splitLines(str1));
                byte[] bytes2 = readBlobOrChunkTree((String) f2.get("blobId"));
                String str2 = bytes2 != null ? new String(bytes2, StandardCharsets.UTF_8) : "";
                item.put("newContent", splitLines(str2));
                diff.add(item);
            }
        }
        return diff;
    }

    private List<String> splitLines(String str) {
        List<String> list = new ArrayList<>();
        if (str == null || str.isEmpty()) return list;
        String[] parts = str.split("(?<=\\n)");
        for (String p : parts) {
            list.add(p);
        }
        return list;
    }

    private class CommitTreeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String commitId = null;
                String treeId = null;
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("commit") && parts.length > 1) {
                            commitId = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        } else if (parts[0].equals("tree") && parts.length > 1) {
                            treeId = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                String targetTreeHash = null;
                if (treeId != null) {
                    targetTreeHash = treeId;
                } else if (commitId != null) {
                    String fullHash = cas.resolveHash(commitId);
                    if (fullHash != null) {
                        Revision rev = (Revision) cas.readObject(fullHash);
                        if (rev != null) {
                            targetTreeHash = rev.getTreeHash();
                        }
                    }
                }
                if (targetTreeHash == null) {
                    String activeRev = db.getConfig("activeRevisionHash");
                    if (activeRev != null) {
                        Revision rev = (Revision) cas.readObject(activeRev);
                        if (rev != null) targetTreeHash = rev.getTreeHash();
                    }
                }
                List<Map<String, Object>> files = new ArrayList<>();
                if (targetTreeHash != null) {
                    walkTree(targetTreeHash, "", files);
                }
                sendJsonResponse(exchange, 200, GSON.toJson(files));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class CommitDiffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String commitId = null;
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] parts = pair.split("=", 2);
                        if (parts[0].equals("commit") && parts.length > 1) {
                            commitId = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                if (commitId == null) {
                    throw new IllegalArgumentException("Missing commit parameter");
                }
                String fullHash = cas.resolveHash(commitId);
                if (fullHash == null) {
                    throw new IllegalArgumentException("Commit not found: " + commitId);
                }
                Revision rev = (Revision) cas.readObject(fullHash);
                if (rev == null) {
                    throw new IllegalArgumentException("Commit not found: " + commitId);
                }
                List<Map<String, Object>> diff;
                if (rev.getParentHashes().isEmpty()) {
                    diff = compareTrees(null, rev.getTreeHash());
                } else {
                    String parentHash = rev.getParentHashes().get(0);
                    Revision parentRev = (Revision) cas.readObject(parentHash);
                    diff = compareTrees(parentRev.getTreeHash(), rev.getTreeHash());
                }
                sendJsonResponse(exchange, 200, GSON.toJson(diff));
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    private class SyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String authEmail = exchange.getRequestHeaders().getFirst("X-User-Email");
                if (authEmail == null || authEmail.isEmpty() || db.getUser(authEmail) == null) {
                    sendJsonResponse(exchange, 401, "{\"error\":\"Unauthorized: Session user is invalid or missing.\"}");
                    return;
                }
                String body = readRequestBody(exchange);
                JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
                String name = jo.has("name") ? jo.get("name").getAsString() : null;
                String email = jo.has("email") ? jo.get("email").getAsString() : null;
                if (email != null && !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Invalid email format.\"}");
                    return;
                }
                if (email != null && !email.equalsIgnoreCase(authEmail)) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden: Email does not match auth user.\"}");
                    return;
                }
                if (name != null) {
                    db.setConfig("author.name", name);
                }
                if (email != null) {
                    db.setConfig("author.email", email);
                }
                db.commit();
                sendJsonResponse(exchange, 200, "{\"message\":\"Sync successful.\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                db.setConfig("author.name", null);
                db.setConfig("author.email", null);
                db.commit();
                sendJsonResponse(exchange, 200, "{\"message\":\"Logged out successfully.\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

}

