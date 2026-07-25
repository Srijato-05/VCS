/**
 * @file GitIgnoreMatcher.java
 * @description Exclusions pattern matcher for DraftFlow VCS.
 * Implements ignore pattern processing by parsing rules from `.dfignore` and `.gitignore` configuration files
 * and matching target paths using exact prefix checks or standard file system glob matchers.
 * 
 * DESIGN RATIONALE:
 * - Employs a dual-tier matching strategy: exact directory path prefix comparisons (for fast, constant-time exclusions)
 *   combined with recursive JDK standard `PathMatcher` globs (for flexible wildcards like `*.log`).
 * - Always forces internal exclusions (`.git`, `.draftflow`) to safeguard database contents from being self-tracked.
 */

package com.draftflow.core;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

public class GitIgnoreMatcher {
    private final Path rootDir;
    private final List<PathMatcher> matchers = new ArrayList<>();
    private final List<String> exactExcludes = new ArrayList<>();
    private final List<String> negationPatterns = new ArrayList<>();

    public GitIgnoreMatcher(Path rootDir, List<String> additionalExcludes) {
        this.rootDir = rootDir;

        // Default internal exclusions
        exactExcludes.add(".draftflow");
        exactExcludes.add(".git");

        // Load config-based exclusions
        if (additionalExcludes != null) {
            for (String pattern : additionalExcludes) {
                addPattern(pattern);
            }
        }

        // Load .dfignore exclusions
        loadIgnoreFile(rootDir.resolve(".dfignore"));

        // Load .gitignore exclusions
        loadIgnoreFile(rootDir.resolve(".gitignore"));
    }

    private void loadIgnoreFile(Path ignorePath) {
        if (Files.exists(ignorePath)) {
            try {
                List<String> lines = Files.readAllLines(ignorePath);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    addPattern(trimmed);
                }
            } catch (IOException e) {
                // Fail-safe: ignore read errors
            }
        }
    }

    private void addPattern(String pattern) {
        if (pattern.startsWith("#")) {
            return;
        }
        if (pattern.startsWith("!")) {
            negationPatterns.add(pattern.substring(1).trim());
            return;
        }

        // Strip trailing slash but keep it as exact exclude prefix
        boolean dirOnly = pattern.endsWith("/");
        String cleaned = dirOnly ? pattern.substring(0, pattern.length() - 1) : pattern;

        if (cleaned.isEmpty()) {
            return;
        }

        // Direct directory/file match (e.g. .gradle, build, bin)
        exactExcludes.add(cleaned);

        // 1. Root-level glob matching
        String rootGlob = cleaned;
        if (rootGlob.startsWith("/")) {
            rootGlob = rootGlob.substring(1);
        }
        if (dirOnly) {
            rootGlob = rootGlob + "/**";
        }
        try {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + rootGlob));
        } catch (Exception e) {
            // Ignore
        }

        // 2. Recursive-level glob matching (for subdirectories)
        if (!cleaned.startsWith("**/") && !cleaned.startsWith("/")) {
            String recurGlob = "**/" + cleaned;
            if (dirOnly) {
                recurGlob = recurGlob + "/**";
            }
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + recurGlob));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    public boolean isIgnored(Path path) {
        Path relative = rootDir.relativize(path);
        String relStr = relative.toString().replace('\\', '/');

        if (relStr.isEmpty()) {
            return false;
        }

        for (String neg : negationPatterns) {
            String cleanNeg = neg.endsWith("/") ? neg.substring(0, neg.length() - 1) : neg;
            if (relStr.equals(cleanNeg) || relStr.endsWith("/" + cleanNeg)) {
                return false;
            }
            try {
                String glob = neg.startsWith("**/") ? neg : "**/" + neg;
                if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(relative)) {
                    return false;
                }
            } catch (Exception ignored) {}
        }

        // 1. Exact/prefix match verification
        for (String exclude : exactExcludes) {
            if (relStr.equals(exclude) || relStr.startsWith(exclude + "/")) {
                return true;
            }
        }

        // 2. Glob path matcher verification
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }

        return false;
    }
}
