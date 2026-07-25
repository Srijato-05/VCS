package com.draftflow.merge;

import com.draftflow.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MergeEdgeCasesTest {

    @TempDir
    Path tempDir;

    @Test
    public void testLineMergeDivergentComplexity() {
        // midM * midN > 25,000,000L triggers complexity exception
        List<String> base = new ArrayList<>();
        List<String> target = new ArrayList<>();
        // Make them completely divergent in the middle
        for (int i = 0; i < 5001; i++) {
            base.add("base-" + i);
            target.add("target-" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> {
            LineMerge.diff(base, target);
        });
    }

    @Test
    public void testLineMergeVariousEdits() {
        // Ours/Theirs identical edit check
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A", "B", "C");
            List<String> theirs = List.of("A", "B", "C");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertTrue(res.clean);
            assertEquals(List.of("A", "B", "C"), res.mergedLines);
        }

        // Ours equals base
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A", "B");
            List<String> theirs = List.of("A", "B", "C");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertTrue(res.clean);
            assertEquals(List.of("A", "B", "C"), res.mergedLines);
        }

        // Theirs equals base
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A", "B", "C");
            List<String> theirs = List.of("A", "B");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertTrue(res.clean);
            assertEquals(List.of("A", "B", "C"), res.mergedLines);
        }

        // Ours deleted, theirs modified/inserted (Delete vs Modify conflict)
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A"); // B deleted
            List<String> theirs = List.of("A", "C", "B"); // C inserted before B
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
            assertTrue(res.mergedLines.contains("<<<<<<< OURS (Delete)"));
        }

        // Theirs deleted, ours modified/inserted (Delete vs Modify conflict)
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A", "C", "B"); // C inserted before B
            List<String> theirs = List.of("A"); // B deleted
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
            assertTrue(res.mergedLines.contains("<<<<<<< OURS (Modify)"));
        }

        // Both inserted different content
        {
            List<String> base = List.of("A");
            List<String> ours = List.of("A", "B");
            List<String> theirs = List.of("A", "C");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
            assertTrue(res.mergedLines.contains("<<<<<<< OURS"));
        }

        // Both deleted
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("A");
            List<String> theirs = List.of("A");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertTrue(res.clean);
            assertEquals(List.of("A"), res.mergedLines);
        }

        // Ours deleted, theirs kept, theirs has inserts (conflict)
        {
            List<String> base = List.of("A");
            List<String> ours = new ArrayList<>(); // deleted A
            List<String> theirs = List.of("B", "A"); // kept A, inserted B before A
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
        }

        // Theirs deleted, ours kept, ours has inserts (conflict)
        {
            List<String> base = List.of("A");
            List<String> ours = List.of("B", "A"); // kept A, inserted B before A
            List<String> theirs = new ArrayList<>(); // deleted A
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
        }
    }

    @Test
    public void testMergeEngineVariousScenarios() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        // 1. Flattening and merging when trees have type conflicts (e.g. file vs folder)
        // Set up base: file.txt (BLOB)
        Blob blobBase = new Blob("base content".getBytes(StandardCharsets.UTF_8));
        String blobBaseHash = cas.writeObject(blobBase);
        TreeEntry entryBase = new TreeEntry("file.txt", blobBaseHash, ObjectType.BLOB, 100644);
        Tree treeBase = new Tree(List.of(entryBase));
        String baseTreeHash = cas.writeObject(treeBase);

        // Set up ours: file.txt is deleted, but we have file.txt as a folder (TREE)
        TreeEntry entryOursSub = new TreeEntry("sub.txt", blobBaseHash, ObjectType.BLOB, 100644);
        Tree treeOursSub = new Tree(List.of(entryOursSub));
        String treeOursSubHash = cas.writeObject(treeOursSub);
        TreeEntry entryOurs = new TreeEntry("file.txt", treeOursSubHash, ObjectType.TREE, 040000);
        Tree treeOurs = new Tree(List.of(entryOurs));
        String oursTreeHash = cas.writeObject(treeOurs);

        // Set up theirs: file.txt modified (BLOB)
        Blob blobTheirs = new Blob("theirs content".getBytes(StandardCharsets.UTF_8));
        String blobTheirsHash = cas.writeObject(blobTheirs);
        TreeEntry entryTheirs = new TreeEntry("file.txt", blobTheirsHash, ObjectType.BLOB, 100644);
        Tree treeTheirs = new Tree(List.of(entryTheirs));
        String theirsTreeHash = cas.writeObject(treeTheirs);

        // Merge trees - this will trigger type conflicts or non-file conflicts
        MergeEngine.MergeResult result = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
        assertFalse(result.clean);
        assertEquals(1, result.conflicts.size());
        assertTrue(result.conflicts.contains("file.txt"));

        // 2. Test reading CHUNK_TREE in readFileBytes
        // We write a mock CHUNK_TREE and read it
        Blob c1 = new Blob("hello ".getBytes(StandardCharsets.UTF_8));
        Blob c2 = new Blob("world".getBytes(StandardCharsets.UTF_8));
        String c1Hash = cas.writeObject(c1);
        String c2Hash = cas.writeObject(c2);
        ChunkTree ct = new ChunkTree(List.of(c1Hash, c2Hash), List.of(6, 5), 11);
        String ctHash = cas.writeObject(ct);

        MergeEngine.TreeEntryInfo info = new MergeEngine.TreeEntryInfo();
        info.path = "chunked.txt";
        info.hash = ctHash;
        info.type = ObjectType.CHUNK_TREE;
        info.mode = 100644;

        // Use reflection to call private method readFileBytes
        try {
            java.lang.reflect.Method m = MergeEngine.class.getDeclaredMethod("readFileBytes", MergeEngine.TreeEntryInfo.class, CAS.class);
            m.setAccessible(true);
            byte[] bytes = (byte[]) m.invoke(null, info, cas);
            assertEquals("hello world", new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail(e);
        }

        // Test readFileBytes on non-file type (e.g. TREE) triggers IllegalArgumentException
        MergeEngine.TreeEntryInfo treeInfo = new MergeEngine.TreeEntryInfo();
        treeInfo.path = "dir";
        treeInfo.hash = "somehash";
        treeInfo.type = ObjectType.TREE;
        treeInfo.mode = 040000;

        try {
            java.lang.reflect.Method m = MergeEngine.class.getDeclaredMethod("readFileBytes", MergeEngine.TreeEntryInfo.class, CAS.class);
            m.setAccessible(true);
            m.invoke(null, treeInfo, cas);
            fail("Expected exception not thrown");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            assertTrue(ite.getCause() instanceof IllegalArgumentException);
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    public void testAncestorFinderEdgeCases() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        // 1. Equal revisions
        String rev = "dummy_rev";
        assertEquals(rev, AncestorFinder.findLCA(rev, rev, cas));

        // 2. Null in queueA/queueB
        List<String> parentsA = new ArrayList<>();
        parentsA.add(null);
        Revision rA = new Revision("tree1", parentsA, "changeA", "author", System.currentTimeMillis(), "msgA", false);
        String rAHash = cas.writeObject(rA);

        List<String> parentsB = new ArrayList<>();
        parentsB.add(null);
        Revision rB = new Revision("tree1", parentsB, "changeB", "author", System.currentTimeMillis(), "msgB", false);
        String rBHash = cas.writeObject(rB);

        assertNull(AncestorFinder.findLCA(rAHash, rBHash, cas));

        // 3. Diamond structure to hit visitedB.add(curr) being false
        Revision rC = new Revision("tree1", Collections.emptyList(), "changeC", "author", System.currentTimeMillis(), "msgC", false);
        String rCHash = cas.writeObject(rC);

        Revision rL = new Revision("tree1", List.of(rCHash), "changeL", "author", System.currentTimeMillis(), "msgL", false);
        String rLHash = cas.writeObject(rL);

        Revision rR = new Revision("tree1", List.of(rCHash), "changeR", "author", System.currentTimeMillis(), "msgR", false);
        String rRHash = cas.writeObject(rR);

        Revision rB2 = new Revision("tree1", List.of(rLHash, rRHash), "changeB2", "author", System.currentTimeMillis(), "msgB2", false);
        String rB2Hash = cas.writeObject(rB2);

        Revision rX = new Revision("tree1", Collections.emptyList(), "changeX", "author", System.currentTimeMillis(), "msgX", false);
        String rXHash = cas.writeObject(rX);

        assertNull(AncestorFinder.findLCA(rXHash, rB2Hash, cas));
    }

    @Test
    public void testMergeEngineAdditionalEdgeCases() throws IOException {
        CAS cas = new CAS(tempDir);
        cas.init();

        // 1. baseRevHash == null (disjoint revisions merge)
        String emptyTreeHash = cas.writeObject(new Tree(Collections.emptyList()));
        Revision r1 = new Revision(emptyTreeHash, Collections.emptyList(), "c1", "author", System.currentTimeMillis(), "m1", false);
        String r1Hash = cas.writeObject(r1);
        Revision r2 = new Revision(emptyTreeHash, Collections.emptyList(), "c2", "author", System.currentTimeMillis(), "m2", false);
        String r2Hash = cas.writeObject(r2);
        assertNotNull(MergeEngine.mergeRevisions(r1Hash, r2Hash, cas));

        // 2. Both entries equal (e.g. both null)
        assertNotNull(MergeEngine.mergeTrees(null, null, null, cas));

        // 3. Only Theirs changed relative to Base (and Theirs deleted, i.e. null)
        {
            Blob blob = new Blob("content".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entryBase = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(entryBase)));

            String oursTreeHash = baseTreeHash;
            String theirsTreeHash = cas.writeObject(new Tree(Collections.emptyList()));

            MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertTrue(res.clean);
            Tree tree = (Tree) cas.readObject(res.treeHash);
            assertTrue(tree.getEntries().isEmpty());
        }

        // 4. Only Ours changed relative to Base (and Ours deleted, i.e. null)
        {
            Blob blob = new Blob("content".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entryBase = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(entryBase)));

            String theirsTreeHash = baseTreeHash;
            String oursTreeHash = cas.writeObject(new Tree(Collections.emptyList()));

            MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertTrue(res.clean);
            Tree tree = (Tree) cas.readObject(res.treeHash);
            assertTrue(tree.getEntries().isEmpty());
        }

        // 5. Both changed differently, both deleted (null)
        {
            Blob blob = new Blob("content".getBytes(StandardCharsets.UTF_8));
            String blobHash = cas.writeObject(blob);
            TreeEntry entryBase = new TreeEntry("file.txt", blobHash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(entryBase)));

            String oursTreeHash = cas.writeObject(new Tree(Collections.emptyList()));
            String theirsTreeHash = oursTreeHash;

            MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertTrue(res.clean);
            Tree tree = (Tree) cas.readObject(res.treeHash);
            assertTrue(tree.getEntries().isEmpty());
        }

        // 6. One deleted, one modified/added (Conflict)
        {
            Blob blobBase = new Blob("base".getBytes(StandardCharsets.UTF_8));
            String blobBaseHash = cas.writeObject(blobBase);
            TreeEntry entryBase = new TreeEntry("file.txt", blobBaseHash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(entryBase)));

            String oursTreeHash = cas.writeObject(new Tree(Collections.emptyList()));

            Blob blobTheirs = new Blob("theirs".getBytes(StandardCharsets.UTF_8));
            String blobTheirsHash = cas.writeObject(blobTheirs);
            TreeEntry entryTheirs = new TreeEntry("file.txt", blobTheirsHash, ObjectType.BLOB, 100644);
            String theirsTreeHash = cas.writeObject(new Tree(List.of(entryTheirs)));

            MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertFalse(res.clean);
            assertEquals(1, res.conflicts.size());
        }

        // 7. Type mismatch / Non-file conflicts (Ours is TREE, Theirs is BLOB)
        {
            Blob blobBase = new Blob("base".getBytes(StandardCharsets.UTF_8));
            String blobBaseHash = cas.writeObject(blobBase);
            TreeEntry entryBase = new TreeEntry("file.txt", blobBaseHash, ObjectType.BLOB, 100644);
            String baseTreeHash = cas.writeObject(new Tree(List.of(entryBase)));

            TreeEntry subEntry = new TreeEntry("sub.txt", blobBaseHash, ObjectType.BLOB, 100644);
            String subTreeHash = cas.writeObject(new Tree(List.of(subEntry)));
            TreeEntry entryOurs = new TreeEntry("file.txt", subTreeHash, ObjectType.TREE, 040000);
            String oursTreeHash = cas.writeObject(new Tree(List.of(entryOurs)));

            Blob blobTheirs = new Blob("theirs".getBytes(StandardCharsets.UTF_8));
            String blobTheirsHash = cas.writeObject(blobTheirs);
            TreeEntry entryTheirs = new TreeEntry("file.txt", blobTheirsHash, ObjectType.BLOB, 100644);
            String theirsTreeHash = cas.writeObject(new Tree(List.of(entryTheirs)));

            MergeEngine.MergeResult res = MergeEngine.mergeTrees(baseTreeHash, oursTreeHash, theirsTreeHash, cas);
            assertFalse(res.clean);
            assertEquals(1, res.conflicts.size());
        }

        // 8. Test writeMergedFile with > 1MB data (CHUNK_TREE creation)
        {
            byte[] largeData = new byte[1024 * 1024 + 100];
            java.util.Arrays.fill(largeData, (byte) 'A');

            try {
                java.lang.reflect.Method m = MergeEngine.class.getDeclaredMethod("writeMergedFile", String.class, byte[].class, int.class, CAS.class);
                m.setAccessible(true);
                MergeEngine.TreeEntryInfo info = (MergeEngine.TreeEntryInfo) m.invoke(null, "large.txt", largeData, 100644, cas);
                assertEquals(ObjectType.CHUNK_TREE, info.type);

                java.lang.reflect.Method readM = MergeEngine.class.getDeclaredMethod("readFileBytes", MergeEngine.TreeEntryInfo.class, CAS.class);
                readM.setAccessible(true);
                byte[] readBytes = (byte[]) readM.invoke(null, info, cas);
                assertArrayEquals(largeData, readBytes);
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    @Test
    public void testLineMergeAdditionalEdgeCases() {
        // 1. Ours and theirs both inserted the same line, but differ elsewhere
        {
            List<String> base = List.of("A", "B");
            List<String> ours = List.of("X", "A", "Y", "B");
            List<String> theirs = List.of("X", "A", "Z", "B");
            LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
            assertFalse(res.clean);
            assertEquals(List.of("<<<<<<< OURS", "X", "Y", "=======", "X", "Z", ">>>>>>> THEIRS", "A", "B"), res.mergedLines);
        }

        // 2. Generate various inputs to hit DP branches in LineMerge.diff
        List<List<String>> testLists = List.of(
            List.of(),
            List.of("A"),
            List.of("B"),
            List.of("A", "B"),
            List.of("B", "A"),
            List.of("A", "B", "C"),
            List.of("B", "C", "A"),
            List.of("C", "A", "B")
        );

        for (List<String> base : testLists) {
            for (List<String> target : testLists) {
                List<LineMerge.Edit> edits = LineMerge.diff(base, target);
                assertNotNull(edits);
            }
        }
    }
}
