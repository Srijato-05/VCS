package com.draftflow.merge;

import com.draftflow.core.Blob;
import com.draftflow.core.CAS;
import com.draftflow.core.ConflictNode;
import com.draftflow.core.Tree;
import com.draftflow.core.TreeEntry;
import com.draftflow.core.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveMergeEngineTest {

    @TempDir
    Path tempDir;

    // --- LineMerge Tests ---

    @Test
    public void testLineMergeClean() {
        List<String> base = Arrays.asList("line1", "line2", "line3", "line4", "line5");
        List<String> ours = Arrays.asList("line1", "line2 ours", "line3", "line4", "line5");
        List<String> theirs = Arrays.asList("line1", "line2", "line3", "line4 theirs", "line5");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertTrue(res.clean);
        assertEquals(Arrays.asList("line1", "line2 ours", "line3", "line4 theirs", "line5"), res.mergedLines);
    }

    @Test
    public void testLineMergeConflict() {
        List<String> base = Arrays.asList("line1", "line2", "line3");
        List<String> ours = Arrays.asList("line1", "line2 ours", "line3");
        List<String> theirs = Arrays.asList("line1", "line2 theirs", "line3");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        assertTrue(res.mergedLines.contains("<<<<<<< OURS"));
        assertTrue(res.mergedLines.contains("======="));
        assertTrue(res.mergedLines.contains(">>>>>>> THEIRS"));
    }

    @Test
    public void testLineMergeDeleteConflict() {
        List<String> base = Arrays.asList("line1", "line2", "line3");
        List<String> ours = Arrays.asList("line1", "line3"); // deleted line2
        List<String> theirs = Arrays.asList("line1", "line2 modified", "line3"); // modified line2

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        assertTrue(res.mergedLines.stream().anyMatch(l -> l.startsWith("<<<<<<< OURS")));
    }

    @Test
    public void testLineMergeDivergentComplexityGuard() {
        // Create highly divergent large lists to exceed the 25 million matrix cell limit
        List<String> largeBase = new ArrayList<>();
        List<String> largeOurs = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            largeBase.add("base" + i);
            largeOurs.add("ours" + (i * 2));
        }
        
        assertThrows(IllegalArgumentException.class, () -> {
            LineMerge.diff(largeBase, largeOurs);
        });
    }

    // --- MergeEngine Tests ---

    @Test
    public void testMergeTreesIdentical() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        String blobHash = cas.writeObject(new Blob("content".getBytes(StandardCharsets.UTF_8)));
        TreeEntry entry = new TreeEntry("a.txt", blobHash, ObjectType.BLOB, 100644);
        Tree tree = new Tree(Collections.singletonList(entry));
        String treeHash = cas.writeObject(tree);

        // Merge identical trees
        MergeEngine.MergeResult res = MergeEngine.mergeTrees(treeHash, treeHash, treeHash, cas);
        assertTrue(res.clean);
        assertEquals(treeHash, res.treeHash);
        assertTrue(res.conflicts.isEmpty());
    }

    @Test
    public void testMergeTreesOneSidedChange() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        String baseBlob = cas.writeObject(new Blob("base".getBytes(StandardCharsets.UTF_8)));
        TreeEntry baseEntry = new TreeEntry("a.txt", baseBlob, ObjectType.BLOB, 100644);
        String baseTree = cas.writeObject(new Tree(Collections.singletonList(baseEntry)));

        String oursBlob = cas.writeObject(new Blob("ours change".getBytes(StandardCharsets.UTF_8)));
        TreeEntry oursEntry = new TreeEntry("a.txt", oursBlob, ObjectType.BLOB, 100644);
        String oursTree = cas.writeObject(new Tree(Collections.singletonList(oursEntry)));

        // Merge theirs (no change) vs ours (changed)
        MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTree, oursTree, baseTree, cas);
        assertTrue(res.clean);
        assertEquals(oursTree, res.treeHash);
    }

    @Test
    public void testMergeTreesConflictingModification() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        String baseBlob = cas.writeObject(new Blob("base\nlines".getBytes(StandardCharsets.UTF_8)));
        TreeEntry baseEntry = new TreeEntry("a.txt", baseBlob, ObjectType.BLOB, 100644);
        String baseTree = cas.writeObject(new Tree(Collections.singletonList(baseEntry)));

        String oursBlob = cas.writeObject(new Blob("ours\nlines".getBytes(StandardCharsets.UTF_8)));
        TreeEntry oursEntry = new TreeEntry("a.txt", oursBlob, ObjectType.BLOB, 100644);
        String oursTree = cas.writeObject(new Tree(Collections.singletonList(oursEntry)));

        String theirsBlob = cas.writeObject(new Blob("theirs\nlines".getBytes(StandardCharsets.UTF_8)));
        TreeEntry theirsEntry = new TreeEntry("a.txt", theirsBlob, ObjectType.BLOB, 100644);
        String theirsTree = cas.writeObject(new Tree(Collections.singletonList(theirsEntry)));

        MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTree, oursTree, theirsTree, cas);
        assertFalse(res.clean);
        assertEquals(1, res.conflicts.size());
        assertEquals("a.txt", res.conflicts.get(0));

        // Verify conflict node was written
        Tree mergedTree = (Tree) cas.readObject(res.treeHash);
        TreeEntry conflictEntry = mergedTree.getEntries().get(0);
        assertEquals(ObjectType.CONFLICT, conflictEntry.getType());

        ConflictNode conflictNode = (ConflictNode) cas.readObject(conflictEntry.getHash());
        assertEquals(baseBlob, conflictNode.getAncestorHash());
        assertEquals(oursBlob, conflictNode.getLeftHash());
        assertEquals(theirsBlob, conflictNode.getRightHash());
    }
}
