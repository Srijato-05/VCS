package com.draftflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveDraftFlowCLITest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalErr = System.err;
    private String originalDraftFlowDir;
    private String originalDebug;

    @BeforeEach
    public void setUp() {
        System.setErr(new PrintStream(errContent));
        originalDraftFlowDir = System.getProperty("draftflow.dir");
        originalDebug = System.getProperty("DRAFTFLOW_DEBUG");
        System.setProperty("draftflow.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    public void tearDown() {
        System.setErr(originalErr);
        if (originalDraftFlowDir != null) {
            System.setProperty("draftflow.dir", originalDraftFlowDir);
        } else {
            System.clearProperty("draftflow.dir");
        }
        if (originalDebug != null) {
            System.setProperty("DRAFTFLOW_DEBUG", originalDebug);
        } else {
            System.clearProperty("DRAFTFLOW_DEBUG");
        }
    }

    @Test
    public void testCliExecutionExceptionHandlerDiagnostics() {
        DraftFlow.DraftFlowExecutionExceptionHandler handler = new DraftFlow.DraftFlowExecutionExceptionHandler();
        CommandLine cmd = new CommandLine(new DraftFlow());

        // 1. Test FileNotFound / Permissions diagnostic tip
        errContent.reset();
        int code1 = handler.handleExecutionException(new FileNotFoundException("Access is denied"), cmd, null);
        assertEquals(1, code1);
        System.err.flush();
        String err1 = errContent.toString();
        assertTrue(err1.contains("Permissions Issue") || err1.contains("Access/Permissions Failure"));

        // 2. Test Lock Contention diagnostic tip
        errContent.reset();
        int code2 = handler.handleExecutionException(new IOException("index.lock already held"), cmd, null);
        assertEquals(1, code2);
        System.err.flush();
        String err2 = errContent.toString();
        assertTrue(err2.contains("Lock Contention"));

        // 3. Test Data Corruption diagnostic tip
        errContent.reset();
        int code3 = handler.handleExecutionException(new IOException("object corrupted checksum failed"), cmd, null);
        assertEquals(1, code3);
        System.err.flush();
        String err3 = errContent.toString();
        assertTrue(err3.contains("Corruption Detected") || err3.contains("Data Corruption"));

        // 4. Test default troubleshooting tip
        errContent.reset();
        int code4 = handler.handleExecutionException(new RuntimeException("Generic crash"), cmd, null);
        assertEquals(1, code4);
        System.err.flush();
        String err4 = errContent.toString();
        assertTrue(err4.contains("Ensure you have enough disk space") || err4.contains("General System Error"));
    }

    @Test
    public void testCliDebugLogsPrint() {
        DraftFlow.DraftFlowExecutionExceptionHandler handler = new DraftFlow.DraftFlowExecutionExceptionHandler();
        CommandLine cmd = new CommandLine(new DraftFlow());

        // Debug flag enabled
        System.setProperty("DRAFTFLOW_DEBUG", "true");
        errContent.reset();
        handler.handleExecutionException(new RuntimeException("Crash for stacktrace"), cmd, null);
        String err = errContent.toString();
        assertTrue(err.contains("Crash for stacktrace"));
        assertTrue(err.contains("at com.draftflow.ComprehensiveDraftFlowCLITest"));
    }

    @Test
    public void testCliMainRouting() {
        // Run main with invalid arguments (should exit with Picocli invalid exit code 2)
        int code = DraftFlow.runMain(new String[]{"invalid-subcommand-name"});
        assertEquals(2, code);

        // Run main with help flag (exits with 0)
        int helpCode = DraftFlow.runMain(new String[]{"--help"});
        assertEquals(0, helpCode);
    }

    @Test
    public void testSubcommandsCoverage() throws Exception {
        CommandLine cmd = new CommandLine(new DraftFlow());

        // setup
        assertEquals(0, cmd.execute("setup"));

        // status
        assertEquals(0, cmd.execute("status"));

        // save
        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "hello");
        assertEquals(0, cmd.execute("save", "-m", "Commit 1"));

        // branch
        assertEquals(0, cmd.execute("branch", "feat"));

        // switch
        assertEquals(0, cmd.execute("switch", "feat"));

        // save amend
        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "hello updated");
        assertEquals(0, cmd.execute("save", "-m", "Commit 1 Amended", "--amend"));

        // stash
        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "hello dirty");
        assertEquals(0, cmd.execute("stash", "push"));
        assertEquals(0, cmd.execute("stash", "list"));
        assertEquals(0, cmd.execute("stash", "pop"));

        // clean
        java.nio.file.Files.writeString(tempDir.resolve("untracked.tmp"), "tmp");
        assertEquals(0, cmd.execute("clean", "-f"));

        // config
        assertEquals(0, cmd.execute("config", "user.name", "Tester"));
        assertEquals(0, cmd.execute("config", "user.name"));

        // hooks
        assertEquals(0, cmd.execute("hooks", "--status"));

        // keys
        assertEquals(0, cmd.execute("keys"));

        // verify
        assertEquals(0, cmd.execute("verify"));

        // prune
        assertEquals(0, cmd.execute("prune"));

        // ignore
        assertEquals(0, cmd.execute("ignore", "*.log"));
    }

    @Test
    public void testSavePatchMode() throws Exception {
        CommandLine cmd = new CommandLine(new DraftFlow());
        assertEquals(0, cmd.execute("setup"));

        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "line 1\nline 2\n");
        assertEquals(0, cmd.execute("save", "-m", "Initial commit"));

        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "line 1 modified\nline 2\nline 3 added\n");

        System.setProperty("draftflow.test.patch.choice", "y");
        try {
            int code = cmd.execute("save", "-m", "Patch save y", "-p");
            assertEquals(0, code);
        } finally {
            System.clearProperty("draftflow.test.patch.choice");
        }

        java.nio.file.Files.writeString(tempDir.resolve("f1.txt"), "line 1 modified again\n");
        System.setProperty("draftflow.test.patch.choice", "n");
        try {
            int code = cmd.execute("save", "-m", "Patch save n", "-p");
            assertEquals(0, code);
        } finally {
            System.clearProperty("draftflow.test.patch.choice");
        }
    }
}
