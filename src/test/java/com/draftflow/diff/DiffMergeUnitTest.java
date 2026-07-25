package com.draftflow.diff;

import com.draftflow.core.BinaryDelta;
import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.ObjectType;
import com.draftflow.core.Tree;
import com.draftflow.core.TreeEntry;
import com.draftflow.merge.AncestorFinder;
import com.draftflow.merge.LineMerge;
import com.draftflow.merge.MergeEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DiffMergeUnitTest {

    @TempDir
    Path tempDir;

    private CAS cas;

    @BeforeEach
    public void setUp() throws Exception {
        Path workDir = tempDir.resolve("diff-repo");
        Files.createDirectories(workDir);
        cas = new CAS(workDir);
        cas.init();
    }

    // --- 1. BinaryDelta Tests ---
    @Test
    public void testBinaryDeltaCompressDecompress() throws Exception {
        byte[] data = "Hello DraftFlow VCS World!".getBytes(StandardCharsets.UTF_8);
        byte[] delta = BinaryDelta.compress(data, data);
        byte[] restored = BinaryDelta.decompress(data, delta);
        assertArrayEquals(data, restored);
    }

    @Test
    public void testBinaryDeltaDifferentPayloads() throws Exception {
        byte[] base = "Line 1\nLine 2\nLine 3\n".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Line 1\nLine 2 modified\nLine 3\nLine 4\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = BinaryDelta.compress(base, target);
        byte[] restored = BinaryDelta.decompress(base, delta);
        assertArrayEquals(target, restored);
    }

    // --- 2. LineMerge Tests ---
    @Test
    public void testLineMergeDiffIdentical() {
        List<String> lines = Arrays.asList("a", "b", "c");
        List<LineMerge.Edit> edits = LineMerge.diff(lines, lines);
        assertEquals(3, edits.size());
        assertTrue(edits.stream().allMatch(e -> e.type == LineMerge.EditType.KEEP));
    }

    @Test
    public void testLineMergeDiffInsertAndDelete() {
        List<String> base = Arrays.asList("a", "b", "c");
        List<String> target = Arrays.asList("a", "x", "c", "d");

        List<LineMerge.Edit> edits = LineMerge.diff(base, target);
        assertFalse(edits.isEmpty());
    }

    @Test
    public void testLineMergeIdenticalInputs() {
        List<String> lines = Arrays.asList("x", "y");
        LineMerge.MergeResult res = LineMerge.merge(lines, lines, lines);
        assertTrue(res.clean);
        assertEquals(lines, res.mergedLines);
    }

    // --- 3. TreeDiffer Tests ---
    @Test
    public void testTreeDifferIdenticalTrees() throws Exception {
        Blob blob = new Blob("data".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);

        Tree tree = new Tree(Collections.singletonList(new TreeEntry("f.txt", blobHash, ObjectType.BLOB, 100644)));
        String treeHash = cas.writeObject(tree);

        List<FileDiff> diffs = TreeDiffer.diff(treeHash, treeHash, cas);
        assertTrue(diffs.isEmpty());
    }

    @Test
    public void testTreeDifferAddedFile() throws Exception {
        Blob blob = new Blob("data".getBytes(StandardCharsets.UTF_8));
        String blobHash = cas.writeObject(blob);

        Tree tree = new Tree(Collections.singletonList(new TreeEntry("f.txt", blobHash, ObjectType.BLOB, 100644)));
        String treeHash = cas.writeObject(tree);

        List<FileDiff> diffs = TreeDiffer.diff(null, treeHash, cas);
        assertEquals(1, diffs.size());
        assertEquals(DiffType.ADDED, diffs.get(0).getType());
    }

    // --- 4. StagedHunk Tests ---
    @Test
    public void testStagedHunkApply() {
        List<String> base = Arrays.asList("line 1", "line 2", "line 3");
        List<String> target = Arrays.asList("line 1", "line 2 modified", "line 3", "line 4 added");

        List<StagedHunk> hunks = StagedHunk.computeHunks(base, target);
        assertFalse(hunks.isEmpty());

        List<String> applied = StagedHunk.applyHunks(base, target, hunks);
        assertEquals(target, applied);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Line variation 1", "Line variation 2", "Line variation 3", "Line variation 4", "Line variation 5"
    })
    public void testLineMergeDiffVariations(String variation) {
        List<String> b = Arrays.asList("base header", variation, "base footer");
        List<String> t = Arrays.asList("base header", variation + " modified", "base footer");
        List<LineMerge.Edit> edits = LineMerge.diff(b, t);
        assertFalse(edits.isEmpty());
    }
}
