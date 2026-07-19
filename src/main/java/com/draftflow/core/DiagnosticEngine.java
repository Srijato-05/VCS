package com.draftflow.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;


public class DiagnosticEngine {

    public enum LogLevel {
        DEBUG(0), INFO(1), WARN(2), ERROR(3), FATAL(4);

        private final int priority;
        LogLevel(int priority) { this.priority = priority; }
        public int getPriority() { return priority; }
    }

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Analyzes any thrown Exception and prints/logs a highly detailed, actionable guide.
     */
    public static void handleException(Throwable e, Path rootDir) {
        log(LogLevel.ERROR, "SystemException", e, rootDir);

        String reset = "\u001B[0m";
        String red = "\u001B[31m";
        String yellow = "\u001B[33m";
        String cyan = "\u001B[36m";
        String bold = "\u001B[1m";

        System.err.println("\n" + red + "==============================================================" + reset);
        System.err.println(bold + red + "               DRAFTFLOW VCS DIAGNOSTIC REPORT                " + reset);
        System.err.println(red + "==============================================================" + reset);

        if (e instanceof DraftFlowException) {
            DraftFlowException dfe = (DraftFlowException) e;
            System.err.println(red + bold + "[DIAGNOSTIC] Error Code: " + dfe.getErrorCode() + reset);
            System.err.println(bold + "EXPLANATION: " + dfe.getMessage() + reset);
            List<String> suggestions = dfe.getSuggestions();
            if (!suggestions.isEmpty()) {
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                for (int i = 0; i < suggestions.size(); i++) {
                    System.err.println(cyan + "  " + (i + 1) + ". " + suggestions.get(i) + reset);
                }
            }
        } else if (e instanceof CASCorruptException) {
            CASCorruptException cce = (CASCorruptException) e;
            System.err.println(red + bold + "[DIAGNOSTIC] Error Code: CAS_CORRUPTION" + reset);
            System.err.println(bold + "EXPLANATION: " + cce.getMessage() + reset);
            List<String> suggestions = cce.getSuggestions();
            if (!suggestions.isEmpty()) {
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                for (int i = 0; i < suggestions.size(); i++) {
                    System.err.println(cyan + "  " + (i + 1) + ". " + suggestions.get(i) + reset);
                }
            }
        } else if (e instanceof NetworkSyncException) {
            NetworkSyncException nse = (NetworkSyncException) e;
            System.err.println(red + bold + "[DIAGNOSTIC] Error Code: NETWORK_SYNC_FAILURE" + reset);
            System.err.println(bold + "EXPLANATION: " + nse.getMessage() + reset);
            List<String> suggestions = nse.getSuggestions();
            if (!suggestions.isEmpty()) {
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                for (int i = 0; i < suggestions.size(); i++) {
                    System.err.println(cyan + "  " + (i + 1) + ". " + suggestions.get(i) + reset);
                }
            }
        } else {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Database may be already in use") || msg.contains("Lock owned by") || msg.contains("index.lock already held") || e.getClass().getName().contains("JdbcSQLNonTransientConnectionException")) {
                System.err.println(red + bold + "[DIAGNOSTIC] H2 Database Lock Contention Detected!" + reset);
                System.err.println(bold + "EXPLANATION: Lock Contention: Another process may be running a DraftFlow operation" + reset);
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                System.err.println(cyan + "  1. If the DraftFlow GUI dashboard is running, please close/terminate the dashboard server." + reset);
                System.err.println(cyan + "  2. Check for other background terminal processes running DraftFlow commands in this workspace." + reset);
                System.err.println(cyan + "  3. Check the active locks on directory: " + (rootDir != null ? rootDir.resolve(".draftflow/index") : "index directory") + reset);
            } else if (msg.contains("Signature verification failed") || msg.contains("Unauthorized public key")) {
                System.err.println(red + bold + "[DIAGNOSTIC] Cryptographic Ref Signing Verification Failure!" + reset);
                System.err.println(bold + "EXPLANATION: The public key used to sign the update is not authorized by the remote server, or signature headers were corrupted." + reset);
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                System.err.println(cyan + "  1. Run 'draftflow keys --list' on the remote server to see authorized keys." + reset);
                System.err.println(cyan + "  2. Copy your local public key (found in '.draftflow/id_ecdsa.pub') and add it to the remote server using:" + reset);
                System.err.println(cyan + "     draftflow keys --add \"<YOUR_PUBLIC_KEY>\"" + reset);
                System.err.println(cyan + "  3. Verify that your local private key '.draftflow/id_ecdsa' is intact." + reset);
            } else if (e instanceof java.util.zip.DataFormatException || msg.contains("corrupt") || msg.contains("checksum failed") || msg.contains("header")) {
                System.err.println(red + bold + "[DIAGNOSTIC] CAS Object Store Corruption Detected!" + reset);
                System.err.println(bold + "EXPLANATION: Data Corruption: A stored object failed checksum verification" + reset);
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                System.err.println(cyan + "  1. Run 'draftflow verify' to scan for corrupted files in '.draftflow/objects/'." + reset);
                System.err.println(cyan + "  2. Use 'draftflow verify --repair' to automatically prune corrupt references and restore to the last stable state." + reset);
            } else if (e instanceof java.io.SyncFailedException || msg.contains("Access is denied") || msg.contains("Permission denied")) {
                System.err.println(red + bold + "[DIAGNOSTIC] Filesystem Access/Permissions Failure!" + reset);
                System.err.println(bold + "EXPLANATION: Permissions Issue: Please verify that you have read/write access to the current directory." + reset);
                System.err.println(yellow + bold + "SUGGESTED ACTIONS:" + reset);
                System.err.println(cyan + "  1. Ensure you have read/write access to the current directory." + reset);
                System.err.println(cyan + "  2. On Windows, run the terminal as Administrator if the folder is system-protected." + reset);
            } else {
                System.err.println(red + bold + "[DIAGNOSTIC] General System Error Occurred." + reset);
                System.err.println("Error Type: " + e.getClass().getName());
                System.err.println("Error Message: " + e.getMessage());
                System.err.println("Troubleshooting: Ensure you have enough disk space and that no other application is locking the database files.");
                System.err.println("Please check the detailed trace in '.draftflow/diagnostics.log'.");
            }
        }
        System.err.println(red + "==============================================================" + reset + "\n");
    }

    public static void log(LogLevel level, String context, String message, Path rootDir) {
        logToFile(level, context, message, null, rootDir);
    }

    public static void log(LogLevel level, String context, Throwable throwable, Path rootDir) {
        logToFile(level, context, throwable.getMessage(), throwable, rootDir);
    }

    private static synchronized void logToFile(LogLevel level, String context, String message, Throwable e, Path rootDir) {
        if (rootDir == null) return;

        // Log level filtering
        String configLogLevel = "INFO";
        try {
            configLogLevel = com.draftflow.DraftFlow.getLogLevel();
        } catch (Throwable ignored) {}
        if (configLogLevel == null) {
            configLogLevel = "INFO";
        }
        LogLevel threshold = LogLevel.INFO;
        try {
            threshold = LogLevel.valueOf(configLogLevel.toUpperCase());
        } catch (Exception ignored) {}

        if (level.getPriority() < threshold.getPriority()) {
            return;
        }

        Path logPath;
        String logDirEnv = System.getenv("DRAFTFLOW_LOG_DIR");
        if (logDirEnv != null && !logDirEnv.trim().isEmpty()) {
            logPath = java.nio.file.Paths.get(logDirEnv).resolve("diagnostics.log");
        } else {
            logPath = rootDir.resolve(".draftflow").resolve("diagnostics.log");
        }

        try {
            if (!Files.exists(logPath.getParent())) {
                Files.createDirectories(logPath.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(LocalDateTime.now().format(DATE_FORMATTER)).append("] ")
              .append("[").append(level.name()).append("] ")
              .append("[").append(context).append("] ")
              .append(message != null ? message : "").append("\n");
            
            if (e != null) {
                sb.append("    at ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
                for (StackTraceElement element : e.getStackTrace()) {
                    sb.append("    at ").append(element.toString()).append("\n");
                }
            }
            // Rotate logs if size exceeds 5MB
            try {
                if (Files.exists(logPath) && Files.size(logPath) > 5 * 1024 * 1024) {
                    // Shift existing rotated logs up to 10
                    for (int i = 10; i >= 1; i--) {
                        Path src = i == 1 ? logPath : logPath.getParent().resolve("diagnostics." + (i - 1) + ".log");
                        Path dst = logPath.getParent().resolve("diagnostics." + i + ".log");
                        if (Files.exists(src)) {
                            Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (IOException ignored) {}
            // Append new log entry
            Files.writeString(logPath, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
