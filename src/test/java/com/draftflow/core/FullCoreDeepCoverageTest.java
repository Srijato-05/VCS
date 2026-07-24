package com.draftflow.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FullCoreDeepCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    public void testExceptions() {
        CASCorruptException ex1 = new CASCorruptException("hash123", Arrays.asList("Step 1", "Step 2"));
        assertEquals("hash123", ex1.getMessage());
        assertEquals(2, ex1.getSuggestions().size());

        LockContentionException ex2 = new LockContentionException("lock path", new IOException("locked"));
        assertEquals("lock path", ex2.getMessage());
        assertNotNull(ex2.getCause());
    }

    @Test
    public void testCompressorAndBinaryDelta() throws Exception {
        byte[] original = "DraftFlow Version Control System High Performance Compressed Object String".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Compressor.compress(original);
        assertNotNull(compressed);
        byte[] decompressed = Compressor.decompress(compressed);
        assertArrayEquals(original, decompressed);

        byte[] base = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\nLine 7\nLine 8\nLine 9\nLine 10\nLine 11\nLine 12\nLine 13\nLine 14\nLine 15\nLine 16\n".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Line 1\nLine 2 modified\nLine 3\nLine 4\nLine 5\nLine 6\nLine 7\nLine 8\nLine 9\nLine 10\nLine 11\nLine 12\nLine 13\nLine 14\nLine 15\nLine 16\nLine 17 added\n".getBytes(StandardCharsets.UTF_8);

        byte[] delta = BinaryDelta.compress(base, target);
        assertNotNull(delta);
        byte[] restored = BinaryDelta.decompress(base, delta);
        assertArrayEquals(target, restored);
    }

    @Test
    public void testSignatureHelperKeys() throws Exception {
        CAS cas = new CAS(tempDir);
        cas.init();

        SignatureHelper.KeyPairStrings pair = SignatureHelper.generateKeyPair();
        assertNotNull(pair.privateKeyBase64);
        assertNotNull(pair.publicKeyBase64);

        byte[] payload = "Data to sign".getBytes(StandardCharsets.UTF_8);
        String sig = SignatureHelper.sign(payload, pair.privateKeyBase64);
        assertNotNull(sig);

        assertTrue(SignatureHelper.verify(payload, sig, pair.publicKeyBase64));

        Revision rev = new Revision(
                "tree-123",
                Arrays.asList("parent-123"),
                "change-123",
                "User <user@dev.com>",
                System.currentTimeMillis(),
                "Signed commit message",
                false
        );

        Revision signedRev = SignatureHelper.signRevisionIfKeyExists(rev, cas);
        assertNotNull(signedRev);
    }

    @Test
    public void testGitIgnoreMatcherPatterns() {
        GitIgnoreMatcher matcher = new GitIgnoreMatcher(tempDir, Arrays.asList("*.tmp", "build/", "!build/important.tmp", "# comment line"));
        assertTrue(matcher.isIgnored(tempDir.resolve("cache.tmp")));
        assertTrue(matcher.isIgnored(tempDir.resolve("build/cache.dat")));
        assertFalse(matcher.isIgnored(tempDir.resolve("build/important.tmp")));
        assertFalse(matcher.isIgnored(tempDir.resolve("src/App.java")));
    }
}
