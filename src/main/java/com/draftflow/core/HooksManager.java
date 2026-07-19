/**
 * @file HooksManager.java
 * @description The repository hooks execution manager for the DraftFlow VCS.
 * Automatically checks and executes pre-commit, post-commit, pre-rebase, and post-checkout hooks
 * placed in `.draftflow/hooks/`.
 * 
 * DESIGN RATIONALE:
 * - Supports executing Windows Batch/CMD scripts (`cmd.exe /c ...`), executable binaries, 
 *   as well as Unix shell scripts via path detection of `sh`/`bash` interpreters (with fallbacks
 *   to local Git bash installations).
 * - Redirects process output to the standard console to let developers monitor hook output in real-time.
 */

package com.draftflow.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class HooksManager {

    private static final ThreadLocal<Path> lastLogPath = new ThreadLocal<>();

    public static Path getLastLogPath() {
        return lastLogPath.get();
    }

    private static List<String> parseShebang(Path scriptPath) {
        List<String> parts = new ArrayList<>();
        try (java.io.BufferedReader reader = Files.newBufferedReader(scriptPath, java.nio.charset.StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("#!")) {
                String shebang = firstLine.substring(2).trim();
                if (!shebang.isEmpty()) {
                    for (String part : shebang.split("\\s+")) {
                        if (!part.isEmpty()) {
                            parts.add(part);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return parts;
    }

    /**
     * Runs a hook script if it exists in .draftflow/hooks.
     * Returns true if the hook executed successfully (exit code 0) or did not exist.
     * Returns false if the hook failed (non-zero exit code or execution error).
     */
    public static boolean runHook(String hookName, Path repoRoot, String... args) {
        if (repoRoot == null || !Files.exists(repoRoot)) {
            return false;
        }
        Path hooksDir = repoRoot.resolve(".draftflow").resolve("hooks");
        Path hookPath = hooksDir.resolve(hookName);

        // Check for common extensions on Windows if the base name doesn't exist
        if (!Files.exists(hookPath) && System.getProperty("os.name").toLowerCase().contains("win")) {
            if (Files.exists(hooksDir.resolve(hookName + ".bat"))) {
                hookPath = hooksDir.resolve(hookName + ".bat");
            } else if (Files.exists(hooksDir.resolve(hookName + ".cmd"))) {
                hookPath = hooksDir.resolve(hookName + ".cmd");
            }
        }

        if (!Files.exists(hookPath)) {
            return true; // Skip if hook doesn't exist
        }

        if (!Files.isRegularFile(hookPath)) {
            System.err.println("Warning: Hook '" + hookName + "' is not a regular file.");
            return false;
        }

        // Line endings normalization
        String lowerPath = hookPath.getFileName().toString().toLowerCase();
        if (!lowerPath.endsWith(".bat") && !lowerPath.endsWith(".cmd") && !lowerPath.endsWith(".exe")) {
            try {
                String content = Files.readString(hookPath, java.nio.charset.StandardCharsets.UTF_8);
                if (content.contains("\r\n")) {
                    Files.writeString(hookPath, content.replace("\r\n", "\n"), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
        }

        try {
            List<String> command = new ArrayList<>();
            boolean isActuallyWin = java.io.File.separatorChar == '\\';

            if (isActuallyWin) {
                if (lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd")) {
                    command.add("cmd.exe");
                    command.add("/c");
                    command.add(hookPath.toAbsolutePath().toString());
                } else if (lowerPath.endsWith(".exe")) {
                    command.add(hookPath.toAbsolutePath().toString());
                } else {
                    // Unix-style shell script running on Windows
                    command.add(findShellInterpreter());
                    command.add(repoRoot.relativize(hookPath).toString().replace('\\', '/'));
                }
            } else {
                if (!Files.isExecutable(hookPath)) {
                    List<String> shebangParts = parseShebang(hookPath);
                    if (!shebangParts.isEmpty()) {
                        command.addAll(shebangParts);
                        command.add(hookPath.toAbsolutePath().toString());
                    } else {
                        command.add("/bin/sh");
                        command.add(hookPath.toAbsolutePath().toString());
                    }
                } else {
                    command.add(hookPath.toAbsolutePath().toString());
                }
            }

            for (String arg : args) {
                command.add(arg);
            }

            Path logDir = repoRoot.resolve(".draftflow").resolve("logs").resolve("hooks");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(hookName + "_" + System.currentTimeMillis() + ".log");
            lastLogPath.set(logFile);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (java.io.InputStream is = p.getInputStream();
                 java.io.OutputStream fos = Files.newOutputStream(logFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
                
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    System.out.write(buffer, 0, bytesRead);
                    System.out.flush();
                    fos.write(buffer, 0, bytesRead);
                    fos.flush();
                }
            }
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("Warning: Hook '" + hookName + "' failed to execute: " + e.getMessage());
            return false;
        }
    }

    private static String findShellInterpreter() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(java.io.File.pathSeparator);
            // First search path for sh/bash directly
            for (String p : paths) {
                try {
                    Path dir = Paths.get(p);
                    Path shExe = dir.resolve("sh.exe");
                    if (Files.exists(shExe)) {
                        return shExe.toAbsolutePath().toString();
                    }
                    Path bashExe = dir.resolve("bash.exe");
                    if (Files.exists(bashExe)) {
                        return bashExe.toAbsolutePath().toString();
                    }
                } catch (Exception ignored) {}
            }
            // Check relative to git.exe in PATH
            for (String p : paths) {
                try {
                    Path dir = Paths.get(p);
                    Path gitExe = dir.resolve("git.exe");
                    if (Files.exists(gitExe)) {
                        Path gitRoot = dir.getParent();
                        if (gitRoot != null) {
                            Path shBin = gitRoot.resolve("bin").resolve("sh.exe");
                            if (Files.exists(shBin)) {
                                return shBin.toAbsolutePath().toString();
                            }
                            Path shUsrBin = gitRoot.resolve("usr").resolve("bin").resolve("sh.exe");
                            if (Files.exists(shUsrBin)) {
                                return shUsrBin.toAbsolutePath().toString();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        // Common installations fallback
        String[] commonPaths = {
            "C:\\Program Files\\Git\\bin\\sh.exe",
            "C:\\Program Files\\Git\\usr\\bin\\sh.exe",
            "C:\\Program Files (x86)\\Git\\bin\\sh.exe",
            "C:\\Program Files (x86)\\Git\\usr\\bin\\sh.exe"
        };
        for (String cp : commonPaths) {
            try {
                Path path = Paths.get(cp);
                if (Files.exists(path)) {
                    return path.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {}
        }
        return "sh";
    }
}
