package com.draftflow.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveHooksManagerTest {

    @TempDir
    Path tempDir;

    @Test
    public void testRunHookNonexistent() {
        // A nonexistent hook should return true (skipped)
        assertTrue(HooksManager.runHook("pre-commit", tempDir));
    }

    @Test
    public void testRunHookNotRegularFile() throws IOException {
        Path hooksDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hooksDir);
        Path hookPath = hooksDir.resolve("pre-commit");
        Files.createDirectories(hookPath); // Make it a directory, not a regular file

        assertFalse(HooksManager.runHook("pre-commit", tempDir));
    }

    @Test
    public void testRunHookSuccess() throws IOException {
        Path hooksDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hooksDir);

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String hookName = "pre-commit";
        if (isWin) {
            Path hookFile = hooksDir.resolve("pre-commit.bat");
            Files.writeString(hookFile, "@echo off\nexit /b 0");
        } else {
            Path hookFile = hooksDir.resolve("pre-commit");
            Files.writeString(hookFile, "#!/bin/sh\nexit 0");
            hookFile.toFile().setExecutable(true);
        }

        assertTrue(HooksManager.runHook(hookName, tempDir));
    }

    @Test
    public void testRunHookFailure() throws IOException {
        Path hooksDir = tempDir.resolve(".draftflow").resolve("hooks");
        Files.createDirectories(hooksDir);

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String hookName = "pre-commit";
        if (isWin) {
            Path hookFile = hooksDir.resolve("pre-commit.bat");
            Files.writeString(hookFile, "@echo off\nexit /b 1");
        } else {
            Path hookFile = hooksDir.resolve("pre-commit");
            Files.writeString(hookFile, "#!/bin/sh\nexit 1");
            hookFile.toFile().setExecutable(true);
        }

        assertFalse(HooksManager.runHook(hookName, tempDir));
    }

    @Test
    public void testInvalidRepoRoot() {
        assertFalse(HooksManager.runHook("pre-commit", null));
        assertFalse(HooksManager.runHook("pre-commit", tempDir.resolve("nonexistent")));
    }
}
