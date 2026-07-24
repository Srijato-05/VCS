/**
 * @file WorkspaceManager.java
 * @description The workspace state coordinator for the DraftFlow VCS.
 * Performs scans of the working copy directory, updates indexing in the SQLite/H2 database, 
 * creates background shadow draft commits, and materializes files back to disk on checkouts.
 * 
 * DESIGN RATIONALE:
 * - Scans file dimensions and last-modified parameters before hashing to minimize redundant disk read IO.
 * - Routes files larger than 1MB to the FastCDC chunk tree engine to compute chunk hashes, while files smaller
 *   than 1MB are written as standard blobs.
 * - Integrates with the LFS manager to write metadata pointers for files registered in the large-file cache.
 * - Uses a multi-phase checkout layout: files are first written to temporary paths in `.draftflow` before an atomic swap
 *   move changes active file paths, protecting active files from partial disk failures.
 */

package com.draftflow.core;

import com.draftflow.cdc.FastCDC;
import com.draftflow.db.FileMetadata;
import com.draftflow.db.MetadataStore;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;

public class WorkspaceManager {
    private final CAS cas;
    private final MetadataStore db;

    public WorkspaceManager(CAS cas, MetadataStore db) {
        this.cas = cas;
        this.db = db;
    }

    /**
     * Scans the working directory for changes on the given set of files
     * and generates a new background shadow commit (draft revision).
     */
    /**
     * Scans the working directory for changes on the given set of files
     * and generates a new background shadow commit (draft revision).
     */
    public synchronized String scanAndCreateShadowCommit(Set<Path> changedPaths) throws IOException {
        Path rootDir = cas.getRootDir();
        DraftFlowConfig config = cas.getConfig();
        GitIgnoreMatcher ignoreMatcher = new GitIgnoreMatcher(rootDir, config.getExclude());

        // 1. Process changed files and update the index database
        for (Path path : changedPaths) {
            if (ignoreMatcher.isIgnored(path)) {
                continue;
            }
            Path relativePath = rootDir.relativize(path);
            String relStr = relativePath.toString().replace('\\', '/');

            try {
                if (Files.exists(path)) {
                    if (Files.isDirectory(path)) {
                        continue; // Skip directories (only track files)
                    }

                    // Check if file is modified relative to what's in index
                    long size = Files.size(path);
                    long lastMod = Files.getLastModifiedTime(path).toMillis();
                    FileMetadata cached = db.getFile(relStr);

                    if (cached != null && cached.getSize() == size && cached.getLastModified() == lastMod) {
                        continue; // Unmodified
                    }

                    // File has changed, index it
                    String objectHash;
                    String typeStr;

                    if (com.draftflow.core.LFSManager.isLfsFile(path, config)) {
                        String ptrContent = com.draftflow.core.LFSManager.createLfsPointer(rootDir, path);
                        byte[] pointerBytes = ptrContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        Blob blob = new Blob(pointerBytes);
                        objectHash = cas.writeObject(blob);
                        typeStr = ObjectType.BLOB.name();
                    } else {
                        byte[] data = Files.readAllBytes(path);
                        if (size > 1024 * 1024) { // > 1MB: Content-Defined Chunking
                            List<FastCDC.Chunk> chunks = FastCDC.chunk(data);
                            List<String> chunkHashes = new ArrayList<>();
                            List<Integer> chunkSizes = new ArrayList<>();

                            for (FastCDC.Chunk chunk : chunks) {
                                byte[] chunkBytes = chunk.getBytes();
                                Blob chunkBlob = new Blob(chunkBytes);
                                String chunkHash = cas.writeObject(chunkBlob);
                                chunkHashes.add(chunkHash);
                                chunkSizes.add(chunkBytes.length);
                            }

                            ChunkTree chunkTree = new ChunkTree(chunkHashes, chunkSizes, size);
                            objectHash = cas.writeObject(chunkTree);
                            typeStr = ObjectType.CHUNK_TREE.name();
                        } else { // <= 1MB: Standard Blob
                            if (cached != null && cached.getType().equals(ObjectType.BLOB.name())) {
                                objectHash = cas.writeBlobWithDelta(data, cached.getHash());
                                DraftFlowObject written = cas.readObject(objectHash);
                                typeStr = written.getType().name();
                            } else {
                                Blob blob = new Blob(data);
                                objectHash = cas.writeObject(blob);
                                typeStr = ObjectType.BLOB.name();
                            }
                        }
                    }

                    // Get mode
                    int mode = Files.isExecutable(path) ? 100755 : 100644;

                    FileMetadata newMeta = new FileMetadata(relStr, size, lastMod, objectHash, typeStr, mode);
                    db.putFile(newMeta);
                } else {
                    // File deleted
                    db.removeFile(relStr);
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not read file " + relStr + " (" + e.getMessage() + "). Retaining previous index state.");
            }
        }

        // 2. Rebuild tree structure from index
        List<FileMetadata> trackedFiles = db.getAllFiles();
        String rootTreeHash = rebuildTree(trackedFiles);

        // 3. Get active branch parent
        String activeHead = db.getConfig("activeHead"); // e.g. "heads/main"
        String parentHash = null;
        if (activeHead != null) {
            parentHash = db.getRef(activeHead);
        } else {
            parentHash = db.getConfig("activeRevisionHash");
        }

        List<String> parents = new ArrayList<>();
        if (parentHash != null) {
            parents.add(parentHash);
        }

        String activeChangeId = db.getConfig("activeChangeId");
        if (activeChangeId == null) {
            activeChangeId = UUID.randomUUID().toString();
            db.setConfig("activeChangeId", activeChangeId);
        }

        // 4. Write draft revision
        Revision draftRev = new Revision(
                rootTreeHash,
                parents,
                activeChangeId,
                getAuthor(),
                System.currentTimeMillis(),
                "shadow-revision (WIP)",
                true // Is draft
        );

        String draftRevHash = cas.writeObject(draftRev);

        // 5. Update active head pointer to this draft
        if (activeHead != null) {
            db.setRef(activeHead, draftRevHash);
        }
        db.setConfig("activeRevisionHash", draftRevHash);
        db.commit();

        return draftRevHash;
    }

    private static class TreeFile {
        final String relPath;
        final String hash;
        final ObjectType type;
        final int mode;

        TreeFile(String relPath, String hash, ObjectType type, int mode) {
            this.relPath = relPath;
            this.hash = hash;
            this.type = type;
            this.mode = mode;
        }
    }

    private void collectFiles(String currentPath, String treeHash, List<TreeFile> filesList) throws IOException {
        Tree tree = (Tree) cas.readObject(treeHash);
        for (TreeEntry entry : tree.getEntries()) {
            String relPath = currentPath.isEmpty() ? entry.getName() : currentPath + "/" + entry.getName();
            if (entry.getType() == ObjectType.TREE) {
                collectFiles(relPath, entry.getHash(), filesList);
            } else {
                filesList.add(new TreeFile(relPath, entry.getHash(), entry.getType(), entry.getMode()));
            }
        }
    }

    private Path writeTempFile(TreeFile file) throws IOException {
        Path rootDir = cas.getRootDir();
        Path targetPath = rootDir.resolve(file.relPath);
        Files.createDirectories(targetPath.getParent());
        
        Path tempPath = targetPath.getParent().resolve(targetPath.getFileName().toString() + ".df_tmp_" + UUID.randomUUID());
        
        try {
            if (file.type == ObjectType.BLOB || file.type == ObjectType.DELTA_BLOB) {
                Blob blob = (Blob) cas.readObject(file.hash);
                byte[] contentBytes = blob.getContent();
                String contentStr = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
                com.draftflow.core.LFSManager.LfsPointer ptr = com.draftflow.core.LFSManager.parsePointer(contentStr);
                if (ptr != null) {
                    com.draftflow.core.LFSManager.restoreLfsFile(rootDir, ptr, tempPath);
                } else {
                    Files.write(tempPath, contentBytes);
                }
            } else if (file.type == ObjectType.CHUNK_TREE) {
                ChunkTree chunkTree = (ChunkTree) cas.readObject(file.hash);
                try (OutputStream os = Files.newOutputStream(tempPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (String chunkHash : chunkTree.getChunkHashes()) {
                        Blob chunk = (Blob) cas.readObject(chunkHash);
                        os.write(chunk.getContent());
                    }
                }
            } else if (file.type == ObjectType.CONFLICT) {
                ConflictNode conflict = (ConflictNode) cas.readObject(file.hash);
                String leftContent = "";
                if (conflict.getLeftHash() != null) {
                    try {
                        Blob leftBlob = (Blob) cas.readObject(conflict.getLeftHash());
                        leftContent = new String(leftBlob.getContent());
                    } catch (Exception e) {
                        leftContent = "[Error reading Left Object]";
                    }
                } else {
                    leftContent = "(Deleted)";
                }

                String rightContent = "";
                if (conflict.getRightHash() != null) {
                    try {
                        Blob rightBlob = (Blob) cas.readObject(conflict.getRightHash());
                        rightContent = new String(rightBlob.getContent());
                    } catch (Exception e) {
                        rightContent = "[Error reading Right Object]";
                    }
                } else {
                    rightContent = "(Deleted)";
                }

                String markerText = "<<<<<<< OURS\n" + leftContent + "\n=======\n" + rightContent + "\n>>>>>>> THEIRS\n";
                Files.writeString(tempPath, markerText);
            }
            return tempPath;
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    /**
     * Checks out the target revision, cleaning untracked files and materializing objects to disk.
     */
    public synchronized void restoreWorkingCopy(String targetRevisionHash) throws IOException {
        Path rootDir = cas.getRootDir();
        
        Revision rev;
        try {
            rev = (Revision) cas.readObject(targetRevisionHash);
        } catch (IOException e) {
            throw new IOException("Failed to read revision " + targetRevisionHash + " from CAS", e);
        }

        String rootTreeHash = rev.getTreeHash();
        List<TreeFile> targetFiles = new ArrayList<>();
        collectFiles("", rootTreeHash, targetFiles);

        // 1. Identify files to delete
        Set<String> targetRelPaths = new HashSet<>();
        for (TreeFile tf : targetFiles) {
            targetRelPaths.add(tf.relPath);
        }

        List<FileMetadata> previousFiles = db.getAllFiles();
        List<Path> filesToDelete = new ArrayList<>();
        for (FileMetadata fm : previousFiles) {
            if (!targetRelPaths.contains(fm.getPath())) {
                filesToDelete.add(rootDir.resolve(fm.getPath()));
            }
        }

        // 2. Materialize all target files to temp files first
        Map<Path, Path> tempToTarget = new HashMap<>();
        List<Path> createdTempFiles = new ArrayList<>();
        try {
            for (TreeFile tf : targetFiles) {
                Path tempFile = writeTempFile(tf);
                tempToTarget.put(tempFile, rootDir.resolve(tf.relPath));
                createdTempFiles.add(tempFile);
            }
        } catch (Exception e) {
            // Abort transaction: Clean up all temp files created so far
            for (Path temp : createdTempFiles) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
            throw new IOException("Failed to materialize workspace files to temp paths during checkout. Workspace left unmodified.", e);
        }

        // 3. Perform atomic swap on disk
        // First delete untracked files
        for (Path p : filesToDelete) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("Warning: Could not delete untracked file " + p + " (" + e.getMessage() + ")");
            }
        }

        // Then move temp files into place
        List<FileMetadata> newMetadataList = new ArrayList<>();
        try {
            for (Map.Entry<Path, Path> entry : tempToTarget.entrySet()) {
                Path temp = entry.getKey();
                Path target = entry.getValue();

                Files.createDirectories(target.getParent());
                safeMoveWithRetry(temp, target);

                String relPath = rootDir.relativize(target).toString().replace('\\', '/');
                TreeFile tf = targetFiles.stream().filter(f -> f.relPath.equals(relPath)).findFirst().orElse(null);
                int mode = tf != null ? tf.mode : 100644;
                if (mode == 100755) {
                    target.toFile().setExecutable(true, false);
                } else {
                    target.toFile().setExecutable(false, false);
                }

                long size = Files.size(target);
                long lastMod = Files.getLastModifiedTime(target).toMillis();
                newMetadataList.add(new FileMetadata(relPath, size, lastMod, tf.hash, tf.type.name(), mode));
            }
        } catch (Exception e) {
            // Partial swap fail-safe alignment
            db.clearIndex();
            for (FileMetadata fm : newMetadataList) {
                db.putFile(fm);
            }
            db.commit();

            for (Path temp : createdTempFiles) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
            throw new IOException("Workspace checkout failed partially during atomic swap. Index has been updated to match successfully checked-out files.", e);
        }

        // 4. Update index and configs
        db.clearIndex();
        for (FileMetadata fm : newMetadataList) {
            db.putFile(fm);
        }
        db.setConfig("activeRevisionHash", targetRevisionHash);
        db.setConfig("activeChangeId", rev.getChangeId());
        
        String activeHead = db.getConfig("activeHead");
        if (activeHead != null) {
            db.setRef(activeHead, targetRevisionHash);
        }
        db.commit();
    }

    private void safeMoveWithRetry(Path source, Path target) throws IOException {
        int maxRetries = 10;
        int delayMs = 50;
        IOException lastEx = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return; // Success
            } catch (IOException e) {
                lastEx = e;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Move interrupted", ie);
                }
            }
        }
        throw lastEx;
    }

    public String rebuildTree(List<FileMetadata> files) throws IOException {
        TreeNode rootNode = new TreeNode("", ObjectType.TREE, 040000);

        for (FileMetadata file : files) {
            String[] segments = file.getPath().split("/");
            TreeNode current = rootNode;

            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                boolean isLast = (i == segments.length - 1);

                if (isLast) {
                    ObjectType type = ObjectType.valueOf(file.getType());
                    current.children.put(segment, new TreeNode(segment, type, file.getMode(), file.getHash()));
                } else {
                    current.children.putIfAbsent(segment, new TreeNode(segment, ObjectType.TREE, 040000));
                    current = current.children.get(segment);
                }
            }
        }

        return writeTreeNode(rootNode);
    }

    private String writeTreeNode(TreeNode node) throws IOException {
        if (node.type != ObjectType.TREE) {
            return node.hash;
        }

        List<TreeEntry> entries = new ArrayList<>();
        for (TreeNode child : node.children.values()) {
            String childHash = writeTreeNode(child);
            entries.add(new TreeEntry(child.name, childHash, child.type, child.mode));
        }

        Tree tree = new Tree(entries);
        return cas.writeObject(tree);
    }

    private static class TreeNode {
        final String name;
        final ObjectType type;
        final int mode;
        String hash;
        final Map<String, TreeNode> children = new HashMap<>();

        // Directory node constructor
        TreeNode(String name, ObjectType type, int mode) {
            this.name = name;
            this.type = type;
            this.mode = mode;
        }

        // File node constructor
        TreeNode(String name, ObjectType type, int mode, String hash) {
            this.name = name;
            this.type = type;
            this.mode = mode;
            this.hash = hash;
        }
    }

    public synchronized void applyRevisionDiff(String baseRevHash, String targetRevHash) throws IOException {
        Path rootDir = cas.getRootDir();

        Revision baseRev = (Revision) cas.readObject(baseRevHash);
        List<TreeFile> baseFiles = new ArrayList<>();
        collectFiles("", baseRev.getTreeHash(), baseFiles);

        Revision targetRev = (Revision) cas.readObject(targetRevHash);
        List<TreeFile> targetFiles = new ArrayList<>();
        collectFiles("", targetRev.getTreeHash(), targetFiles);

        Map<String, TreeFile> baseMap = new HashMap<>();
        for (TreeFile tf : baseFiles) {
            baseMap.put(tf.relPath, tf);
        }

        Map<String, TreeFile> targetMap = new HashMap<>();
        for (TreeFile tf : targetFiles) {
            targetMap.put(tf.relPath, tf);
        }

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(baseMap.keySet());
        allPaths.addAll(targetMap.keySet());

        for (String relPath : allPaths) {
            Path diskPath = rootDir.resolve(relPath);
            TreeFile baseFile = baseMap.get(relPath);
            TreeFile targetFile = targetMap.get(relPath);

            if (baseFile == null && targetFile != null) {
                // Added
                byte[] targetBytes = getFileBytes(targetFile.hash, targetFile.type);
                Files.createDirectories(diskPath.getParent());
                Files.write(diskPath, targetBytes);
                if (targetFile.mode == 100755) {
                    diskPath.toFile().setExecutable(true, false);
                } else {
                    diskPath.toFile().setExecutable(false, false);
                }
                long size = Files.size(diskPath);
                long lastMod = Files.getLastModifiedTime(diskPath).toMillis();
                db.putFile(new FileMetadata(relPath, size, lastMod, targetFile.hash, targetFile.type.name(), targetFile.mode));
            } else if (baseFile != null && targetFile == null) {
                // Deleted
                Files.deleteIfExists(diskPath);
                db.removeFile(relPath);
            } else if (baseFile != null && targetFile != null) {
                if (!baseFile.hash.equals(targetFile.hash)) {
                    byte[] baseBytes = getFileBytes(baseFile.hash, baseFile.type);
                    byte[] targetBytes = getFileBytes(targetFile.hash, targetFile.type);

                    byte[] diskBytes = new byte[0];
                    if (Files.exists(diskPath)) {
                        diskBytes = Files.readAllBytes(diskPath);
                    }

                    if (Arrays.equals(diskBytes, baseBytes)) {
                        Files.createDirectories(diskPath.getParent());
                        Files.write(diskPath, targetBytes);
                        if (targetFile.mode == 100755) {
                            diskPath.toFile().setExecutable(true, false);
                        } else {
                            diskPath.toFile().setExecutable(false, false);
                        }
                        long size = Files.size(diskPath);
                        long lastMod = Files.getLastModifiedTime(diskPath).toMillis();
                        db.putFile(new FileMetadata(relPath, size, lastMod, targetFile.hash, targetFile.type.name(), targetFile.mode));
                    } else {
                        List<String> baseLines = Arrays.asList(new String(baseBytes, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n"));
                        List<String> oursLines = Arrays.asList(new String(diskBytes, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n"));
                        List<String> theirsLines = Arrays.asList(new String(targetBytes, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n"));

                        com.draftflow.merge.LineMerge.MergeResult mergeRes = com.draftflow.merge.LineMerge.merge(baseLines, oursLines, theirsLines);
                        StringBuilder sb = new StringBuilder();
                        for (String line : mergeRes.mergedLines) {
                            sb.append(line).append("\n");
                        }
                        byte[] mergedBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        Files.write(diskPath, mergedBytes);

                        if (!mergeRes.clean) {
                            String oursHash = cas.writeObject(new Blob(diskBytes));
                            ConflictNode conflictNode = new ConflictNode(baseFile.hash, oursHash, targetFile.hash, relPath);
                            String conflictHash = cas.writeObject(conflictNode);
                            db.putFile(new FileMetadata(relPath, mergedBytes.length, System.currentTimeMillis(), conflictHash, ObjectType.CONFLICT.name(), targetFile.mode));
                        } else {
                            String mergedHash = cas.writeObject(new Blob(mergedBytes));
                            long size = Files.size(diskPath);
                            long lastMod = Files.getLastModifiedTime(diskPath).toMillis();
                            db.putFile(new FileMetadata(relPath, size, lastMod, mergedHash, ObjectType.BLOB.name(), targetFile.mode));
                        }
                    }
                }
            }
        }
        db.commit();
    }

    private byte[] getFileBytes(String hash, ObjectType type) throws IOException {
        if (type == ObjectType.BLOB || type == ObjectType.DELTA_BLOB) {
            Blob b = (Blob) cas.readObject(hash);
            byte[] content = b.getContent();
            String contentStr = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            com.draftflow.core.LFSManager.LfsPointer ptr = com.draftflow.core.LFSManager.parsePointer(contentStr);
            if (ptr != null) {
                Path lfsCache = cas.getRootDir().resolve(".draftflow").resolve("lfs");
                Path targetFile = lfsCache.resolve(ptr.oid.substring(0, 2)).resolve(ptr.oid.substring(2));
                if (Files.exists(targetFile)) {
                    return Files.readAllBytes(targetFile);
                }
            }
            return content;
        } else if (type == ObjectType.CHUNK_TREE) {
            ChunkTree ct = (ChunkTree) cas.readObject(hash);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String ch : ct.getChunkHashes()) {
                Blob b = (Blob) cas.readObject(ch);
                out.write(b.getContent());
            }
            return out.toByteArray();
        }
        throw new IOException("Cannot read bytes for type: " + type);
    }

    private String getAuthor() {
        if (db != null) {
            String name = db.getConfig("author.name");
            String email = db.getConfig("author.email");
            if (name != null && !name.trim().isEmpty()) {
                if (email != null && !email.trim().isEmpty()) {
                    return name + " <" + email + ">";
                }
                return name;
            }
        }
        return System.getProperty("user.name", "User");
    }
}
