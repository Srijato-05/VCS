package com.draftflow.coverage;

import com.draftflow.core.CASCorruptException;
import com.draftflow.core.DiagnosticEngine;
import com.draftflow.core.DraftFlowException;
import com.draftflow.core.HooksFailureException;
import com.draftflow.core.HooksManager;
import com.draftflow.core.LockContentionException;
import com.draftflow.core.NetworkSyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FullCoverageExceptionsAndDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    public void testCustomExceptionsConstructorsAndGetters() {
        // DraftFlowException
        DraftFlowException ex1 = new DraftFlowException("ERR_TEST", "Test msg", List.of("step1", "step2"));
        assertEquals("ERR_TEST", ex1.getErrorCode());
        assertEquals("Test msg", ex1.getMessage());
        assertEquals(2, ex1.getSuggestions().size());

        DraftFlowException ex2 = new DraftFlowException("ERR_TEST", "Test msg", List.of("step1"), new IOException("cause"));
        assertNotNull(ex2.getCause());

        DraftFlowException ex3 = new DraftFlowException("ERR_TEST", "Test msg");
        assertEquals("ERR_TEST", ex3.getErrorCode());

        DraftFlowException ex4 = new DraftFlowException("ERR_TEST", "Test msg", new IOException("cause"));
        assertNotNull(ex4.getCause());

        // LockContentionException
        LockContentionException lce1 = new LockContentionException("lock error", List.of("step1"));
        assertEquals("lock error", lce1.getMessage());

        LockContentionException lce2 = new LockContentionException("lock error", List.of("step1"), new IOException("cause"));
        assertNotNull(lce2.getCause());

        // HooksFailureException
        HooksFailureException hfe1 = new HooksFailureException("hook error", List.of("fix script"));
        assertEquals("hook error", hfe1.getMessage());

        HooksFailureException hfe2 = new HooksFailureException("hook error", List.of("fix script"), new IOException("cause"));
        assertNotNull(hfe2.getCause());

        // CASCorruptException
        CASCorruptException cce1 = new CASCorruptException("hash123", List.of("rebuild index"));
        assertTrue(cce1.getMessage().contains("hash123"));

        CASCorruptException cce2 = new CASCorruptException("hash123", List.of("rebuild index"), new IOException());
        assertNotNull(cce2.getCause());

        // NetworkSyncException
        NetworkSyncException nse1 = new NetworkSyncException("http://remote.com", List.of("check network"));
        assertTrue(nse1.getMessage().contains("http://remote.com"));

        NetworkSyncException nse2 = new NetworkSyncException("http://remote.com", List.of("check network"), new IOException());
        assertNotNull(nse2.getCause());
    }

    @Test
    public void testDiagnosticEngineLoggingAndFormatting() throws Exception {
        DiagnosticEngine.handleException(new DraftFlowException("ERR_100", "test error", List.of("action 1")), tempDir);
        DiagnosticEngine.handleException(new CASCorruptException("hash999", List.of("action CAS")), tempDir);
        DiagnosticEngine.handleException(new NetworkSyncException("http://remote.org", List.of("action Net")), tempDir);
        DiagnosticEngine.handleException(new RuntimeException("Database may be already in use"), tempDir);
        DiagnosticEngine.handleException(new RuntimeException("Signature verification failed"), tempDir);
        DiagnosticEngine.handleException(new java.util.zip.DataFormatException("corrupt data"), tempDir);
        DiagnosticEngine.handleException(new java.io.SyncFailedException("Permission denied"), tempDir);
        DiagnosticEngine.handleException(new RuntimeException("general ex"), tempDir);

        DiagnosticEngine.log(DiagnosticEngine.LogLevel.DEBUG, "CTX", "debug message", tempDir);
        DiagnosticEngine.log(DiagnosticEngine.LogLevel.WARN, "CTX", new Exception("warn ex"), tempDir);

        // Test log rotation
        Path logPath = tempDir.resolve(".draftflow").resolve("diagnostics.log");
        Files.createDirectories(logPath.getParent());
        byte[] bigData = new byte[6 * 1024 * 1024];
        Files.write(logPath, bigData);
        DiagnosticEngine.log(DiagnosticEngine.LogLevel.ERROR, "CTX", "overflow message", tempDir);

        DiagnosticEngine.LogLevel[] levels = DiagnosticEngine.LogLevel.values();
        assertTrue(levels.length >= 5);
        assertEquals(0, DiagnosticEngine.LogLevel.DEBUG.getPriority());
        assertEquals(DiagnosticEngine.LogLevel.INFO, DiagnosticEngine.LogLevel.valueOf("INFO"));
    }

    @Test
    public void testHooksManagerLifecycle() throws Exception {
        // Run missing hook
        boolean preCommitRes = HooksManager.runHook("pre-commit", tempDir);
        assertTrue(preCommitRes);

        // Run non-existent repo
        boolean noRepoRes = HooksManager.runHook("pre-commit", tempDir.resolve("nonexistent"));
        assertTrue(!noRepoRes);

        // Run valid script hook
        Path hookDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hookDir);
        Path hookFile = hookDir.resolve("pre-commit.bat");
        Files.writeString(hookFile, "@echo off\nexit 0\n");

        boolean res2 = HooksManager.runHook("pre-commit", tempDir);
        assertTrue(res2);

        Path lastLog = HooksManager.getLastLogPath();
        assertNotNull(lastLog);
    }
}
