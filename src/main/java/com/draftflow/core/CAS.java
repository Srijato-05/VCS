/**
 * @file CAS.java
 * @description The Content Addressable Storage (CAS) engine for DraftFlow VCS.
 * Manages the underlying object database directory structure under `.draftflow/objects/`.
 * Implements deduplication, atomic file writes, compression, and post-read SHA-256 integrity verification.
 * 
 * DESIGN RATIONALE:
 * - Emulates git's object storage model. Each object is identified by the SHA-256 hash of its header + payload.
 * - Stored objects are compressed using GZIP/DEFLATE to reduce disk usage.
 * - Writing files uses an atomic temporary file + rename move to avoid half-written/corrupt file nodes if the JVM crashes.
 * - Implements file lock channels to prevent multiple CLI processes from corrupting the index concurrently.
 */

package com.draftflow.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CAS {
    private final Path rootDir;
    private final Path draftFlowDir;
    private final Path objectsDir;

    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock lock;

    public CAS(Path rootDir) {
        this.rootDir = rootDir;
        this.draftFlowDir = rootDir.resolve(".draftflow");
        this.objectsDir = draftFlowDir.resolve("objects");
    }

    public void init() throws IOException {
        Files.createDirectories(draftFlowDir);
        Files.createDirectories(objectsDir);
        Files.createDirectories(draftFlowDir.resolve("refs").resolve("heads"));
        Files.createDirectories(draftFlowDir.resolve("refs").resolve("changes"));
        Files.createDirectories(draftFlowDir.resolve("index"));
        Files.createDirectories(draftFlowDir.resolve("logs"));

        Path configPath = draftFlowDir.resolve("config.json");
        if (!Files.exists(configPath)) {
            String defaultConfig = "{\n  \"version\": \"1.0\",\n  \"hashAlgorithm\": \"SHA-256\",\n  \"exclude\": [\".git\", \".draftflow\", \"build\", \"out\", \"target\", \".gradle\", \".idea\", \"bin\", \".vscode\"]\n}";
            Files.writeString(configPath, defaultConfig);
        }
    }

    public boolean isInitialized() {
        return Files.exists(draftFlowDir) && Files.exists(objectsDir);
    }

    public String writeObject(DraftFlowObject obj) throws IOException {
        byte[] serializedWithHeader = obj.serializeWithHeader();
        String hash = Hasher.hash(serializedWithHeader);
        
        Path objectPath = getObjectPath(hash);
        if (Files.exists(objectPath)) {
            return hash; // Deduplication: already exists on disk
        }

        Files.createDirectories(objectPath.getParent());
        byte[] compressed = Compressor.compress(serializedWithHeader);
        
        // Atomic write via temp file and atomic rename
        String fileName = objectPath.getFileName().toString();
        Path tempPath = objectPath.resolveSibling(fileName + ".tmp_" + java.util.UUID.randomUUID());
        Files.write(tempPath, compressed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tempPath, objectPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tempPath, objectPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        
        return hash;
    }

    public DraftFlowObject readObject(String hash) throws IOException {
        Path objectPath = getObjectPath(hash);
        if (!Files.exists(objectPath)) {
            throw new IOException("Object not found: " + hash);
        }

        byte[] compressed = Files.readAllBytes(objectPath);
        byte[] decompressed;
        try {
            decompressed = Compressor.decompress(compressed);
        } catch (Exception e) {
            throw new IOException("Decompression failed for: " + hash, e);
        }

        // SHA-256 post-read integrity check
        String recalculated = Hasher.hash(decompressed);
        if (!recalculated.equals(hash)) {
            throw new IOException("CAS data corruption detected: expected hash " + hash + " but calculated " + recalculated);
        }

        // Parse header: "[type] [size]\0[payload]"
        int spaceIndex = -1;
        int nullIndex = -1;
        
        for (int i = 0; i < decompressed.length; i++) {
            if (decompressed[i] == ' ' && spaceIndex == -1) {
                spaceIndex = i;
            }
            if (decompressed[i] == '\0') {
                nullIndex = i;
                break;
            }
        }

        if (spaceIndex == -1 || nullIndex == -1) {
            throw new IOException("Corrupt object header for: " + hash);
        }

        String typeStr = new String(Arrays.copyOfRange(decompressed, 0, spaceIndex)).toUpperCase();
        ObjectType type = ObjectType.valueOf(typeStr);
        
        byte[] payload = Arrays.copyOfRange(decompressed, nullIndex + 1, decompressed.length);

        return switch (type) {
            case BLOB -> new Blob(payload);
            case TREE -> Tree.deserialize(payload);
            case REVISION -> Revision.deserialize(payload);
            case CONFLICT -> ConflictNode.deserialize(payload);
            case CHUNK_TREE -> ChunkTree.deserialize(payload);
            case DELTA_BLOB -> {
                DeltaBlob deltaBlob = DeltaBlob.deserialize(payload);
                Blob baseBlob = (Blob) readObject(deltaBlob.getBaseBlobHash());
                byte[] targetBytes = BinaryDelta.decompress(baseBlob.getContent(), deltaBlob.getDeltaBytes());
                yield new Blob(targetBytes);
            }
        };
    }

    private Path getObjectPath(String hash) {
        String dir = hash.substring(0, 2);
        String file = hash.substring(2);
        return objectsDir.resolve(dir).resolve(file);
    }

    public boolean exists(String hash) {
        if (hash == null || hash.length() < 4) {
            return false;
        }
        return Files.exists(getObjectPath(hash));
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getDraftFlowDir() {
        return draftFlowDir;
    }

    public Path getObjectsDir() {
        return objectsDir;
    }

    public DraftFlowConfig getConfig() {
        Path configPath = draftFlowDir.resolve("config.json");
        try {
            if (Files.exists(configPath)) {
                return DraftFlowConfig.load(configPath);
            }
        } catch (Exception e) {
            System.err.println("Warning: config.json was corrupted or unparseable. Automatically regenerating it...");
        }

        try {
            Files.createDirectories(draftFlowDir);
            String defaultConfig = "{\n  \"version\": \"1.0\",\n  \"hashAlgorithm\": \"SHA-256\",\n  \"exclude\": [\".git\", \".draftflow\", \"build\", \"out\", \"target\", \".gradle\", \".idea\", \"bin\", \".vscode\"]\n}";
            Files.writeString(configPath, defaultConfig, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return DraftFlowConfig.load(configPath);
        } catch (Exception e) {
            return new DraftFlowConfig();
        }
    }

    public synchronized boolean tryAcquireLock(long timeoutMs) throws IOException {
        Path lockFile = draftFlowDir.resolve("index.lock");
        Files.createDirectories(lockFile.getParent());
        
        long start = System.currentTimeMillis();
        while (true) {
            try {
                if (lockChannel == null) {
                    lockChannel = java.nio.channels.FileChannel.open(lockFile, 
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                }
                lock = lockChannel.tryLock();
                if (lock != null) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore and try again
            }

            if (System.currentTimeMillis() - start > timeoutMs) {
                closeLockChannel();
                return false;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeLockChannel();
                return false;
            }
        }
    }

    public void acquireLock() throws IOException {
        if (!tryAcquireLock(5000)) {
            throw new IOException("Another DraftFlow process is holding the workspace lock. Please wait or release any hanging commands.");
        }
    }

    public synchronized void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
        } catch (IOException e) {
            // Ignore
        }
        closeLockChannel();
        
        try {
            Files.deleteIfExists(draftFlowDir.resolve("index.lock"));
        } catch (IOException e) {
            // Ignore
        }
    }

    private void closeLockChannel() {
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException e) {
                // Ignore
            }
            lockChannel = null;
        }
    }

    public String resolveHash(String shortHash) throws IOException {
        if (shortHash == null || shortHash.length() < 4) {
            return null;
        }
        if (shortHash.length() == 64) {
            Path path = getObjectPath(shortHash);
            return Files.exists(path) ? shortHash : null;
        }

        String prefix = shortHash.substring(0, 2);
        String suffix = shortHash.substring(2);
        Path dir = objectsDir.resolve(prefix);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return null;
        }

        List<String> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(suffix)) {
                    matches.add(prefix + name);
                }
            }
        }

        if (matches.size() == 1) {
            return matches.get(0);
        } else if (matches.size() > 1) {
            System.err.println("Ambiguous hash: " + shortHash + " matches " + matches);
            return null;
        }
        return null;
    }

    public String writeBlobWithDelta(byte[] content, String baseBlobHash) throws IOException {
        if (baseBlobHash == null) {
            return writeObject(new Blob(content));
        }
        try {
            Blob baseBlob = (Blob) readObject(baseBlobHash);
            byte[] baseBytes = baseBlob.getContent();
            byte[] deltaBytes = BinaryDelta.compress(baseBytes, content);
            if (deltaBytes.length < content.length * 0.7) {
                DeltaBlob deltaBlob = new DeltaBlob(baseBlobHash, deltaBytes);
                return writeObject(deltaBlob);
            }
        } catch (Exception e) {
            // Fallback to normal blob write on error
        }
        return writeObject(new Blob(content));
    }
}
