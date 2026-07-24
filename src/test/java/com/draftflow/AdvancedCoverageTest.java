package com.draftflow;

import com.draftflow.core.*;
import com.draftflow.db.*;
import com.draftflow.diff.*;
import com.draftflow.remote.*;
import com.draftflow.ui.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class AdvancedCoverageTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    private String originalDraftFlowDirProp;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    public void setUp() {
        originalDraftFlowDirProp = System.getProperty("draftflow.dir");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    public void tearDown() {
        if (originalDraftFlowDirProp != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDirProp);
        } else {
            System.clearProperty("draftflow.dir");
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testUiServerTraceAndActionHandlers() throws Exception {
        Path workDir = tempDir.resolve("repo-ui");
        Files.createDirectories(workDir);
        System.setProperty("draftflow.dir", workDir.toAbsolutePath().toString());

        CAS cas = new CAS(workDir);
        cas.init();

        Path dbPath = workDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();
        db.setConfig("activeHead", "heads/main");

        WorkspaceManager wm = new WorkspaceManager(cas, db);

        // 1. Create a commit to trace
        Path file = workDir.resolve("hello.txt");
        Files.writeString(file, "Line 1\nLine 2");

        Set<Path> changes = new HashSet<>();
        changes.add(file);
        String revHash = wm.scanAndCreateShadowCommit(changes);
        db.setConfig("activeRevisionHash", revHash);

        // 2. Start UiServer
        UiServer server = new UiServer(cas, db, 0);
        server.start();
        int port = server.getPort();
        assertTrue(port > 0);

        HttpClient client = HttpClient.newHttpClient();

        // --- Test GET Status ---
        HttpResponse<String> responseStatus = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseStatus.statusCode());
        assertTrue(responseStatus.body().contains("activeRevision"));

        // --- Test GET DAG ---
        HttpResponse<String> responseDag = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/dag")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseDag.statusCode());

        // --- Test GET Ledger ---
        HttpResponse<String> responseLedger = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/ledger")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseLedger.statusCode());

        // --- Test GET Trace (Success) ---
        HttpResponse<String> responseTrace = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/trace?file=hello.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseTrace.statusCode());
        assertTrue(responseTrace.body().contains("Line 1"));

        // --- Test GET Trace (Missing file param) ---
        HttpResponse<String> responseTraceErr1 = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/trace")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseTraceErr1.statusCode());
        assertTrue(responseTraceErr1.body().contains("error"));

        // --- Test GET Trace (Nonexistent file) ---
        HttpResponse<String> responseTraceErr2 = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/trace?file=missing.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseTraceErr2.statusCode());

        // --- Test GET Trace (No commits on detached) ---
        db.removeConfig("activeRevisionHash");
        HttpResponse<String> responseTraceErr3 = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/trace?file=hello.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseTraceErr3.statusCode());
        db.setConfig("activeRevisionHash", revHash);
        db.commit();

        // --- Test ActionHandler ---
        // Close the database to release the file lock for the command line action execution
        db.close();

        // 1. GET method (Not Allowed)
        HttpResponse<String> responseActionGet = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=save")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, responseActionGet.statusCode());

        // 2. POST method missing cmd
        HttpResponse<String> responseActionNoCmd = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseActionNoCmd.statusCode());

        // 3. POST method unknown cmd
        HttpResponse<String> responseActionBadCmd = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=foo")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseActionBadCmd.statusCode());

        // 4. POST save cmd (Commit 1)
        HttpResponse<String> responseActionSave = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=save&msg=Web%20Commit")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionSave.statusCode());

        // 4b. POST save cmd (Commit 2 to enable undo)
        HttpResponse<String> responseActionSave2 = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=save&msg=Web%20Commit%202")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionSave2.statusCode());

        // 5. POST switch cmd without target
        HttpResponse<String> responseActionSwitchErr = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=switch")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseActionSwitchErr.statusCode());

        // 6. POST switch cmd (Success)
        HttpResponse<String> responseActionSwitch = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=switch&target=" + revHash)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionSwitch.statusCode());

        // 7. POST undo cmd (Should revert switch or undo to previous commit context)
        // Since we are back at revHash, undo will fail as root. Let's switch back to heads/main first so we can undo.
        HttpResponse<String> responseActionSwitchBack = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=switch&target=main")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionSwitchBack.statusCode());

        HttpResponse<String> responseActionUndo = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=undo")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionUndo.statusCode());

        // 8. POST rebase cmd without upstream
        HttpResponse<String> responseActionRebaseErr = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=rebase")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(500, responseActionRebaseErr.statusCode());

        // 9. POST clean cmd
        HttpResponse<String> responseActionClean = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/action?cmd=clean")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, responseActionClean.statusCode());

        server.stop();
        db.close();
    }

    @Test
    public void testTreeDifferCoverage() throws IOException {
        Path workDir = tempDir.resolve("repo-diff");
        Files.createDirectories(workDir);
        CAS cas = new CAS(workDir);
        cas.init();

        String blob1Hash = cas.writeObject(new Blob("content1".getBytes(StandardCharsets.UTF_8)));

        // Tree 1: Single file1.txt
        List<TreeEntry> entries1 = new ArrayList<>();
        entries1.add(new TreeEntry("file1.txt", blob1Hash, ObjectType.BLOB, 100644));
        String tree1Hash = cas.writeObject(new Tree(entries1));

        // Tree 2: Single directory subdir containing file1.txt (Tests markAllAdded/markAllDeleted recursive)
        List<TreeEntry> subEntries = new ArrayList<>();
        subEntries.add(new TreeEntry("file1.txt", blob1Hash, ObjectType.BLOB, 100644));
        String subTreeHash = cas.writeObject(new Tree(subEntries));

        List<TreeEntry> entries2 = new ArrayList<>();
        entries2.add(new TreeEntry("subdir", subTreeHash, ObjectType.TREE, 040000));
        String tree2Hash = cas.writeObject(new Tree(entries2));

        // Diff Tree 1 -> Tree 2 (file deleted, directory added)
        List<FileDiff> diffs1 = TreeDiffer.diff(tree1Hash, tree2Hash, cas);
        assertEquals(2, diffs1.size());
        assertEquals("file1.txt", diffs1.get(0).getPath());
        assertEquals(DiffType.DELETED, diffs1.get(0).getType());
        assertEquals("subdir/file1.txt", diffs1.get(1).getPath());
        assertEquals(DiffType.ADDED, diffs1.get(1).getType());

        // Diff Tree 2 -> Tree 1 (directory deleted, file added)
        List<FileDiff> diffs2 = TreeDiffer.diff(tree2Hash, tree1Hash, cas);
        assertEquals(2, diffs2.size());
        assertEquals("file1.txt", diffs2.get(0).getPath());
        assertEquals(DiffType.ADDED, diffs2.get(0).getType());
        assertEquals("subdir/file1.txt", diffs2.get(1).getPath());
        assertEquals(DiffType.DELETED, diffs2.get(1).getType());

        // Tree 3: Type change transition for same name (file1.txt is now a directory)
        List<TreeEntry> entries3 = new ArrayList<>();
        entries3.add(new TreeEntry("file1.txt", subTreeHash, ObjectType.TREE, 040000));
        String tree3Hash = cas.writeObject(new Tree(entries3));

        List<FileDiff> diffs3 = TreeDiffer.diff(tree1Hash, tree3Hash, cas);
        assertEquals(2, diffs3.size());
        assertEquals("file1.txt", diffs3.get(0).getPath());
        assertEquals(DiffType.DELETED, diffs3.get(0).getType());
        assertEquals("file1.txt/file1.txt", diffs3.get(1).getPath());
        assertEquals(DiffType.ADDED, diffs3.get(1).getType());

        // --- Additional TreeDiffer Coverage ---
        // 1. Nested directory added/deleted recursive test
        List<TreeEntry> subSubEntries = new ArrayList<>();
        subSubEntries.add(new TreeEntry("file2.txt", blob1Hash, ObjectType.BLOB, 100644));
        String subSubTreeHash = cas.writeObject(new Tree(subSubEntries));

        List<TreeEntry> subEntries2 = new ArrayList<>();
        subEntries2.add(new TreeEntry("subsubdir", subSubTreeHash, ObjectType.TREE, 040000));
        String subTreeHash2 = cas.writeObject(new Tree(subEntries2));

        List<TreeEntry> entries4 = new ArrayList<>();
        entries4.add(new TreeEntry("subdir", subTreeHash2, ObjectType.TREE, 040000));
        String tree4Hash = cas.writeObject(new Tree(entries4));

        // Diff Empty -> Tree 4 (deep recursive markAllAdded)
        List<FileDiff> diffs4 = TreeDiffer.diff(null, tree4Hash, cas);
        assertEquals(1, diffs4.size());
        assertEquals("subdir/subsubdir/file2.txt", diffs4.get(0).getPath());

        // Diff Tree 4 -> Empty (deep recursive markAllDeleted)
        List<FileDiff> diffs5 = TreeDiffer.diff(tree4Hash, null, cas);
        assertEquals(1, diffs5.size());
        assertEquals("subdir/subsubdir/file2.txt", diffs5.get(0).getPath());

        // 2. File mode change only (identical hashes, different modes)
        List<TreeEntry> entries5 = new ArrayList<>();
        entries5.add(new TreeEntry("file1.txt", blob1Hash, ObjectType.BLOB, 100755)); // Mode changed to executable
        String tree5Hash = cas.writeObject(new Tree(entries5));

        List<FileDiff> diffs6 = TreeDiffer.diff(tree1Hash, tree5Hash, cas);
        assertEquals(1, diffs6.size());
        assertEquals("file1.txt", diffs6.get(0).getPath());
        assertEquals(DiffType.MODIFIED, diffs6.get(0).getType());
    }

    @Test
    public void testRemoteClientHttpAndRetry() throws Exception {
        // Start a mock http server
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        final int[] requestCount = {0};
        server.createContext("/", exchange -> {
            try {
                requestCount[0]++;
                String path = exchange.getRequestURI().getPath();
                
                // To test executeWithRetry: fail first 2 attempts of GET refs/heads/main, succeed on 3rd
                if (path.equals("/refs/heads/main")) {
                    if (requestCount[0] <= 2) {
                        exchange.sendResponseHeaders(500, -1);
                    } else {
                        byte[] response = "hash123".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response);
                        }
                    }
                } else if (path.equals("/refs/heads/missing")) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        RemoteClient client = new RemoteClient("http://localhost:" + port);

        // Fetch main ref - will retry twice and then succeed
        String hash = client.getRef("heads/main");
        assertEquals("hash123", hash);
        assertTrue(requestCount[0] >= 3);

        // Fetch missing ref - returns null (404)
        String missingHash = client.getRef("heads/missing");
        assertNull(missingHash);

        // Test other HTTP paths to make sure they complete without errors
        client.putRef("heads/main", "newhash");
        client.uploadPack("pack1", new byte[]{1, 2, 3});
        client.downloadPack("pack1");
        client.uploadIndex(new HashMap<>());
        client.downloadIndex();

        server.stop(0);
    }

    @Test
    public void testCliCommandsAndMain() throws Exception {
        Path workDir = tempDir.resolve("repo-cli");
        Files.createDirectories(workDir);

        System.setProperty("draftflow.dir", workDir.toAbsolutePath().toString());

        CommandLine cmd = new CommandLine(new DraftFlow());
        cmd.execute("setup");

        CAS cas = new CAS(workDir);
        Path dbPath = workDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");

        // --- 1. Test ResolveCmd ---
        String ancestorHash = cas.writeObject(new Blob("ancestor".getBytes(StandardCharsets.UTF_8)));
        String leftHash = cas.writeObject(new Blob("ours".getBytes(StandardCharsets.UTF_8)));
        String rightHash = cas.writeObject(new Blob("theirs".getBytes(StandardCharsets.UTF_8)));
        ConflictNode conflict = new ConflictNode(ancestorHash, leftHash, rightHash, "conflict.txt");
        String conflictHash = cas.writeObject(conflict);

        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata fMeta = new FileMetadata("conflict.txt", 0, 0, conflictHash, ObjectType.CONFLICT.name(), 100644);
            db.putFile(fMeta);
        }

        // Mock System.in input to select 1 (Ours)
        System.setIn(new ByteArrayInputStream("1\n".getBytes(StandardCharsets.UTF_8)));
        DraftFlow.ResolveCmd resolveCmd = new DraftFlow.ResolveCmd();
        int resolveRes = resolveCmd.call();
        assertEquals(0, resolveRes);

        // Verify resolved content
        try (MetadataStore db = new MetadataStore(dbPath)) {
            db.open();
            FileMetadata resolvedMeta = db.getFile("conflict.txt");
            assertEquals(ObjectType.BLOB.name(), resolvedMeta.getType());
        }
        assertEquals("ours", Files.readString(workDir.resolve("conflict.txt")));

        // --- 2. Test MergeCmd with Nonexistent Target ---
        DraftFlow.MergeCmd mergeCmd = new DraftFlow.MergeCmd();
        Field fTarget = mergeCmd.getClass().getDeclaredField("target");
        fTarget.setAccessible(true);
        fTarget.set(mergeCmd, "nonexistent_branch");
        int mergeRes = mergeCmd.call();
        assertEquals(1, mergeRes); // fails because branch doesn't exist

        // --- 3. Test DashboardCmd ---
        Thread t = new Thread(() -> {
            try {
                CommandLine cmd2 = new CommandLine(new DraftFlow());
                cmd2.execute("dashboard", "-p", "0");
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join();

        // --- 4. Test DraftFlow main entrypoint parsing ---
        new CommandLine(new DraftFlow()).execute("--help");
    }

    @Test
    public void testUtilityEdgeCases() throws Exception {
        Path workDir = tempDir.resolve("repo-utils");
        Files.createDirectories(workDir);
        CAS cas = new CAS(workDir);
        cas.init();

        // Test getPermanentRevision private helper directly via Reflection
        Method getPerm = DraftFlow.class.getDeclaredMethod("getPermanentRevision", String.class, CAS.class);
        getPerm.setAccessible(true);

        // Null revision
        assertNull(getPerm.invoke(null, null, cas));

        // Test findCommonAncestor private helper directly via Reflection
        Method findAnc = DraftFlow.class.getDeclaredMethod("findCommonAncestor", String.class, String.class, CAS.class);
        findAnc.setAccessible(true);

        // No ancestors
        assertNull(findAnc.invoke(null, null, null, cas));
    }
}
