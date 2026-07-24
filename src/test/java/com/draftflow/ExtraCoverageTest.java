package com.draftflow;

import com.draftflow.core.*;
import com.draftflow.db.*;
import com.draftflow.watcher.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

public class ExtraCoverageTest {

    @TempDir
    Path tempDir;

    // --- MetadataStore Tests ---

    @Test
    public void testMetadataStoreCorruptionAndLocks() throws Exception {
        Path dbFile = tempDir.resolve("test_corruption.mv.db");

        // 1. Create a corrupt file with invalid content
        Files.writeString(dbFile, "corrupted database content");

        MetadataStore store = new MetadataStore(dbFile);
        store.open();
        // Check that it recovered and successfully opened
        assertNull(store.getFile("any"));

        // Test shutdown hook execution
        java.lang.reflect.Field fShutdownHook = MetadataStore.class.getDeclaredField("shutdownHook");
        fShutdownHook.setAccessible(true);
        Thread hook = (Thread) fShutdownHook.get(store);
        if (hook != null) {
            hook.run();
        }

        store.close();

        // 2. Lock the database file using FileOutputStream to trigger inner IOException in move/delete
        Path dbFile2 = tempDir.resolve("test_lock.mv.db");
        Files.writeString(dbFile2, "invalid content");
        
        try (FileOutputStream fos = new FileOutputStream(dbFile2.toFile())) {
            fos.write(1);
            // Keeping the file output stream open locks the file on Windows
            MetadataStore store2 = new MetadataStore(dbFile2);
            store2.open();
            store2.close();
        }

        // 3. Fallback database path when dbFile is a directory
        Path dirDb = tempDir.resolve("test_dir.mv.db");
        Files.createDirectories(dirDb);
        MetadataStore store3 = new MetadataStore(dirDb);
        store3.open();
        store3.close();
    }

    @Test
    public void testMetadataStoreShutdownRegistryException() throws Exception {
        Path dbFile = tempDir.resolve("test_shutdown.mv.db");
        MetadataStore store = new MetadataStore(dbFile);
        
        // Replace registry with one that throws exception
        store.shutdownHookRegistry = new MetadataStore.ShutdownHookRegistry() {
            @Override
            public void addShutdownHook(Thread hook) {}
            @Override
            public void removeShutdownHook(Thread hook) {
                throw new RuntimeException("Simulated shutdown hook removal exception");
            }
        };

        store.open();
        // Invoke close to trigger catch block
        store.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMetadataStoreCacheNull() throws Exception {
        Path dbFile = tempDir.resolve("test_cache.mv.db");
        MetadataStore store = new MetadataStore(dbFile);
        store.open();
        
        // Retrieve indexMap via reflection
        java.lang.reflect.Field fIndexMap = MetadataStore.class.getDeclaredField("indexMap");
        fIndexMap.setAccessible(true);
        org.h2.mvstore.MVMap<String, String> indexMap = (org.h2.mvstore.MVMap<String, String>) fIndexMap.get(store);
        indexMap.put("corrupt_path", "{invalid-json}");
        store.close();

        // Re-open. It should skip the corrupt file because FileMetadata.fromJson returns null
        MetadataStore store2 = new MetadataStore(dbFile);
        store2.open();
        assertNull(store2.getFile("corrupt_path"));
        store2.close();
    }

    // --- FSWatcher Tests ---

    @Test
    @SuppressWarnings("unchecked")
    public void testFSWatcherErrorsAndEvents() throws Exception {
        Path root = tempDir.resolve("watcher_root");
        Files.createDirectories(root);
        
        DraftFlowConfig config = new DraftFlowConfig();
        
        final List<Set<Path>> changesReceived = new CopyOnWriteArrayList<>();
        FSWatcher watcher = new FSWatcher(root, config, new FSWatcher.WatcherListener() {
            @Override
            public void onFilesChanged(Set<Path> changedPaths) {
                changesReceived.add(changedPaths);
            }
        });

        // 1. Mock WatchService to throw on close()
        watcher.start();

        WatchService mockService = new WatchService() {
            @Override
            public void close() throws IOException {
                throw new IOException("Simulated close failure");
            }
            @Override
            public WatchKey poll() { return null; }
            @Override
            public WatchKey poll(long timeout, TimeUnit unit) { return null; }
            @Override
            public WatchKey take() { return null; }
        };

        java.lang.reflect.Field fWatchService = FSWatcher.class.getDeclaredField("watchService");
        fWatchService.setAccessible(true);
        fWatchService.set(watcher, mockService);

        // stop() calls watchService.close() which throws and triggers the catch block
        watcher.stop();

        // 2. Trigger invalid/null key branches in watchLoop
        FSWatcher watcher2 = new FSWatcher(root, config, new FSWatcher.WatcherListener() {
            @Override
            public void onFilesChanged(Set<Path> changedPaths) {}
        });

        // Use custom WatchService to return custom keys
        final BlockingQueue<WatchKey> keysQueue = new LinkedBlockingQueue<>();
        final WatchKey sentinelKey = new WatchKey() {
            @Override public boolean isValid() { return false; }
            @Override public List<WatchEvent<?>> pollEvents() { return null; }
            @Override public boolean reset() { return false; }
            @Override public void cancel() {}
            @Override public Watchable watchable() { return null; }
        };

        WatchService customService = new WatchService() {
            @Override
            public void close() {}
            @Override
            public WatchKey poll() { return null; }
            @Override
            public WatchKey poll(long timeout, TimeUnit unit) { return null; }
            @Override
            public WatchKey take() throws InterruptedException {
                WatchKey k = keysQueue.take();
                if (k == sentinelKey) {
                    throw new InterruptedException("Stop loop");
                }
                return k;
            }
        };

        fWatchService.set(watcher2, customService);

        java.lang.reflect.Field fRunning = FSWatcher.class.getDeclaredField("running");
        fRunning.setAccessible(true);
        fRunning.set(watcher2, true);

        // Run watchLoop on a separate thread
        java.lang.reflect.Method mWatchLoop = FSWatcher.class.getDeclaredMethod("watchLoop");
        mWatchLoop.setAccessible(true);

        Thread testThread = new Thread(() -> {
            try {
                mWatchLoop.invoke(watcher2);
            } catch (Exception e) {
                // Ignore expected stop
            }
        });
        testThread.setDaemon(true);
        testThread.start();

        // Send a key that is NOT in the watchKeys map (dir == null)
        WatchKey unregisteredKey = new WatchKey() {
            @Override
            public boolean isValid() { return true; }
            @Override
            public List<WatchEvent<?>> pollEvents() { return Collections.emptyList(); }
            @Override
            public boolean reset() { return true; }
            @Override
            public void cancel() {}
            @Override
            public Watchable watchable() { return null; }
        };
        keysQueue.put(unregisteredKey);

        // Send an overflow event key
        final WatchKey overflowKey = new WatchKey() {
            @Override
            public boolean isValid() { return true; }
            @Override
            public List<WatchEvent<?>> pollEvents() {
                WatchEvent<Object> ev = new WatchEvent<>() {
                    @Override
                    public Kind<Object> kind() { return StandardWatchEventKinds.OVERFLOW; }
                    @Override
                    public int count() { return 1; }
                    @Override
                    public Object context() { return null; }
                };
                return List.of(ev);
            }
            @Override
            public boolean reset() { return false; } // will trigger key reset returning false!
            @Override
            public void cancel() {}
            @Override
            public Watchable watchable() { return null; }
        };

        java.lang.reflect.Field fWatchKeys = FSWatcher.class.getDeclaredField("watchKeys");
        fWatchKeys.setAccessible(true);
        Map<WatchKey, Path> watchKeys = (Map<WatchKey, Path>) fWatchKeys.get(watcher2);
        watchKeys.put(overflowKey, root);

        keysQueue.put(overflowKey);

        // Stop the loop by putting sentinel key
        keysQueue.put(sentinelKey);
        testThread.interrupt();
        testThread.join(1000);
    }

    // --- GitIgnoreMatcher Tests ---

    @Test
    public void testGitIgnoreMatcherPatterns() throws Exception {
        Path root = tempDir.resolve("ignore_root");
        Files.createDirectories(root);

        // Create a directory named .dfignore to trigger IOException in loadIgnoreFile
        Files.createDirectories(root.resolve(".dfignore"));

        // Matcher initialization
        GitIgnoreMatcher matcher = new GitIgnoreMatcher(root, List.of("/", "/myPattern", "[", "*.log", "subPattern"));

        // Empty cleaned pattern, trailing slash
        // Glob exception handling for "["
        // Recursive startsWith exclusions
        assertTrue(matcher.isIgnored(root.resolve("myPattern")));
        assertTrue(matcher.isIgnored(root.resolve("test.log")));
        assertTrue(matcher.isIgnored(root.resolve("subPattern")));
    }

    // --- BinaryDelta Tests ---

    @Test
    public void testBinaryDeltaOperations() throws Exception {
        byte[] base = "abcdefghijklmnopqrstuvwxyz1234567890".getBytes();
        byte[] target = "abcdefghijklmnopqRstuvwxyz1234567890".getBytes(); // modified in middle

        byte[] delta = BinaryDelta.compress(base, target);
        byte[] decompressed = BinaryDelta.decompress(base, delta);
        assertArrayEquals(target, decompressed);

        // Decompress identical content
        byte[] deltaId = BinaryDelta.compress(base, base);
        assertArrayEquals(base, BinaryDelta.decompress(base, deltaId));

        // Malformed delta commands
        byte[] malformedDelta = new byte[]{ 0x03 }; // unknown command 3
        assertThrows(IOException.class, () -> BinaryDelta.decompress(base, malformedDelta));

        // Out of bounds copy
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeByte(0x01); // CMD_COPY
        dos.writeInt(50);    // offset out of bounds
        dos.writeInt(10);    // length
        byte[] oobDelta = bos.toByteArray();
        assertThrows(IOException.class, () -> BinaryDelta.decompress(base, oobDelta));
    }

    // --- HooksManager Tests ---

    @Test
    public void testHooksManagerOSSpecificAndFailures() throws Exception {
        String originalOs = System.getProperty("os.name");
        try {
            // Mock OS to Linux to test non-win path
            System.setProperty("os.name", "Linux");
            Path hookRoot = tempDir.resolve("hook_root");
            Files.createDirectories(hookRoot);
            
            // Create a dummy hook script
            Path hooksDir = hookRoot.resolve(".draftflow").resolve("hooks");
            Files.createDirectories(hooksDir);
            Path hookFile = hooksDir.resolve("pre-commit");
            Files.writeString(hookFile, "#!/bin/sh\nexit 0");
            
            // Should skip if doesn't exist
            assertTrue(HooksManager.runHook("nonexistent", hookRoot));

            // Run it on mock Linux (will execute /bin/sh if present, or fail gracefully)
            HooksManager.runHook("pre-commit", hookRoot);

            // Mock exception by passing directory as executable
            Path dirHook = hooksDir.resolve("dir-hook");
            Files.createDirectories(dirHook);
            assertFalse(HooksManager.runHook("dir-hook", hookRoot));

        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    // --- DraftFlow runMain exception handler coverage ---
    @Test
    public void testDraftFlowMainExceptionHandling() throws Exception {
        // 1. Call runMain with no args (runs call() printing usage)
        int code = DraftFlow.runMain(new String[0]);
        assertEquals(0, code);

        // 2. Call runMain with invalid command (returns 2 from picocli)
        int code2 = DraftFlow.runMain(new String[]{"nonexistent-command"});
        assertEquals(2, code2);

        // 3. Trigger RuntimeException in exception handler (e.g. running commit outside a repo)
        Path emptyDir = tempDir.resolve("empty-repo");
        Files.createDirectories(emptyDir);
        String oldDFDir = System.getProperty("draftflow.dir");
        try {
            System.setProperty("draftflow.dir", emptyDir.toString());
            System.setProperty("DRAFTFLOW_DEBUG", "true");
            int code3 = DraftFlow.runMain(new String[]{"save", "-m", "msg"});
            assertEquals(1, code3);
        } finally {
            if (oldDFDir != null) {
                System.setProperty("draftflow.dir", oldDFDir);
            } else {
                System.clearProperty("draftflow.dir");
            }
            System.clearProperty("DRAFTFLOW_DEBUG");
        }

        // 4. Directly test DraftFlowExecutionExceptionHandler branches
        DraftFlow.DraftFlowExecutionExceptionHandler handler = new DraftFlow.DraftFlowExecutionExceptionHandler();
        CommandLine cmd = new CommandLine(new DraftFlow());
        
        // FileNotFoundException diagnostic
        int codeFNF = handler.handleExecutionException(new FileNotFoundException("file not found"), cmd, null);
        assertEquals(1, codeFNF);

        // Access is denied diagnostic
        int codeDenied = handler.handleExecutionException(new RuntimeException("Access is denied"), cmd, null);
        assertEquals(1, codeDenied);

        // Lock Contention diagnostic
        int codeLock = handler.handleExecutionException(new RuntimeException("database lock error"), cmd, null);
        assertEquals(1, codeLock);

        // Corruption diagnostic
        int codeCorrupt = handler.handleExecutionException(new RuntimeException("corrupted index"), cmd, null);
        assertEquals(1, codeCorrupt);

        // Default diagnostic
        int codeDefault = handler.handleExecutionException(new RuntimeException("generic error"), cmd, null);
        assertEquals(1, codeDefault);
    }

    // --- UiServer extra coverage ---
    @Test
    @SuppressWarnings("unchecked")
    public void testUiServerExtraCoverage() throws Exception {
        Path workDir = tempDir.resolve("ui_extra");
        Files.createDirectories(workDir);
        CAS cas = new CAS(workDir);
        cas.init();
        Path dbPath = workDir.resolve(".draftflow").resolve("index").resolve("index.mv.db");
        MetadataStore db = new MetadataStore(dbPath);
        db.open();

        // 1. Test getDashboardHtml classloader mocking
        com.draftflow.ui.UiServer s1 = new com.draftflow.ui.UiServer(cas, db, 0) {
            @Override
            protected InputStream getResourceAsStream(String path) {
                return new ByteArrayInputStream("custom-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        java.lang.reflect.Method mGetDashboardHtml = com.draftflow.ui.UiServer.class.getDeclaredMethod("getDashboardHtml");
        mGetDashboardHtml.setAccessible(true);
        byte[] bytes1 = (byte[]) mGetDashboardHtml.invoke(s1);
        assertEquals("custom-content", new String(bytes1, java.nio.charset.StandardCharsets.UTF_8));

        com.draftflow.ui.UiServer s2 = new com.draftflow.ui.UiServer(cas, db, 0) {
            @Override
            protected InputStream getResourceAsStream(String path) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Simulated read failure");
                    }
                };
            }
        };
        byte[] bytes2 = (byte[]) mGetDashboardHtml.invoke(s2);
        assertTrue(new String(bytes2, java.nio.charset.StandardCharsets.UTF_8).contains("DraftFlow Premium Web GUI"));

        // 2. ActionHandler empty query parameters
        java.lang.reflect.Method mParseQuery = null;
        for (Class<?> clazz : com.draftflow.ui.UiServer.class.getDeclaredClasses()) {
            if (clazz.getSimpleName().equals("ActionHandler")) {
                mParseQuery = clazz.getDeclaredMethod("parseQuery", String.class);
                mParseQuery.setAccessible(true);
                // Instantiate ActionHandler using reflection
                java.lang.reflect.Constructor<?> cons = clazz.getDeclaredConstructor(com.draftflow.ui.UiServer.class);
                cons.setAccessible(true);
                Object actionHandlerObj = cons.newInstance(s1);
                Map<String, String> res = (Map<String, String>) mParseQuery.invoke(actionHandlerObj, "cmd=clean&emptyParam");
                assertEquals("clean", res.get("cmd"));
                assertEquals("", res.get("emptyParam"));
                break;
            }
        }

        db.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFSWatcherDynamicDirectoryAndEmptyKeys() throws Exception {
        Path root = tempDir.resolve("watcher_dyn_root");
        Files.createDirectories(root);

        // Create a sub-directory and a file to walk
        Path dynDir = root.resolve("dynamicDir");
        Files.createDirectories(dynDir);
        Path nestedFile = dynDir.resolve("nested.txt");
        Files.writeString(nestedFile, "nested content");

        DraftFlowConfig config = new DraftFlowConfig();

        FSWatcher watcher = new FSWatcher(root, config, changedPaths -> {});

        // Mock WatchService to return ENTRY_CREATE event for "dynamicDir"
        final BlockingQueue<WatchKey> keysQueue = new LinkedBlockingQueue<>();
        
        WatchKey mockKey = new WatchKey() {
            @Override
            public boolean isValid() { return true; }
            @Override
            public List<WatchEvent<?>> pollEvents() {
                WatchEvent<Path> ev = new WatchEvent<>() {
                    @Override
                    public Kind<Path> kind() { return StandardWatchEventKinds.ENTRY_CREATE; }
                    @Override
                    public int count() { return 1; }
                    @Override
                    public Path context() { return Paths.get("dynamicDir"); }
                };
                return List.of(ev);
            }
            @Override
            public boolean reset() { return false; } // will trigger key reset returning false!
            @Override
            public void cancel() {}
            @Override
            public Watchable watchable() { return null; }
        };

        WatchService customService = new WatchService() {
            @Override
            public void close() {}
            @Override
            public WatchKey poll() { return null; }
            @Override
            public WatchKey poll(long timeout, TimeUnit unit) { return null; }
            @Override
            public WatchKey take() throws InterruptedException {
                WatchKey k = keysQueue.take();
                if (k == null) {
                    throw new InterruptedException("Stop");
                }
                return k;
            }
        };

        java.lang.reflect.Field fWatchService = FSWatcher.class.getDeclaredField("watchService");
        fWatchService.setAccessible(true);
        fWatchService.set(watcher, customService);

        java.lang.reflect.Field fRunning = FSWatcher.class.getDeclaredField("running");
        fRunning.setAccessible(true);
        fRunning.set(watcher, true);

        java.lang.reflect.Field fWatchKeys = FSWatcher.class.getDeclaredField("watchKeys");
        fWatchKeys.setAccessible(true);
        Map<WatchKey, Path> watchKeys = (Map<WatchKey, Path>) fWatchKeys.get(watcher);
        watchKeys.put(mockKey, root);

        java.lang.reflect.Method mWatchLoop = FSWatcher.class.getDeclaredMethod("watchLoop");
        mWatchLoop.setAccessible(true);

        Thread testThread = new Thread(() -> {
            try {
                mWatchLoop.invoke(watcher);
            } catch (Exception ignored) {}
        });
        testThread.setDaemon(true);
        testThread.start();

        keysQueue.put(mockKey);
        Thread.sleep(100);
        watcher.stop();
        testThread.interrupt();
        testThread.join(2000);
    }
}
