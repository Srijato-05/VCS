package com.draftflow.merge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveLineMergeTest {

    @Test
    public void testLineMergeIdenticalOrBaseMatches() {
        List<String> base = Arrays.asList("line1", "line2", "line3");
        List<String> ours = Arrays.asList("line1", "line2", "line3");
        List<String> theirs = Arrays.asList("line1", "line2", "line3");

        // Ours and theirs are identical
        LineMerge.MergeResult res1 = LineMerge.merge(base, ours, theirs);
        assertTrue(res1.clean);
        assertEquals(ours, res1.mergedLines);

        // Ours equals base, theirs has edits
        List<String> theirsEdits = Arrays.asList("line1", "line2 modified", "line3", "line4");
        LineMerge.MergeResult res2 = LineMerge.merge(base, base, theirsEdits);
        assertTrue(res2.clean);
        assertEquals(theirsEdits, res2.mergedLines);

        // Theirs equals base, ours has edits
        List<String> oursEdits = Arrays.asList("line1 modified", "line2", "line3");
        LineMerge.MergeResult res3 = LineMerge.merge(base, oursEdits, base);
        assertTrue(res3.clean);
        assertEquals(oursEdits, res3.mergedLines);
    }

    @Test
    public void testCleanMerges() {
        List<String> base = Arrays.asList("A", "B", "C", "D");
        List<String> ours = Arrays.asList("A", "B modified", "C", "D");
        List<String> theirs = Arrays.asList("A", "B", "C", "D modified");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertTrue(res.clean);
        assertEquals(Arrays.asList("A", "B modified", "C", "D modified"), res.mergedLines);
    }

    @Test
    public void testOverlappingInsertConflict() {
        List<String> base = Arrays.asList("A", "B");
        List<String> ours = Arrays.asList("A", "insert ours", "B");
        List<String> theirs = Arrays.asList("A", "insert theirs", "B");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        assertTrue(res.mergedLines.contains("<<<<<<< OURS"));
        assertTrue(res.mergedLines.contains("insert ours"));
        assertTrue(res.mergedLines.contains("======="));
        assertTrue(res.mergedLines.contains("insert theirs"));
        assertTrue(res.mergedLines.contains(">>>>>>> THEIRS"));
    }

    @Test
    public void testIdenticalInsertion() {
        List<String> base = Arrays.asList("A", "B");
        List<String> ours = Arrays.asList("A", "shared insert", "B");
        List<String> theirs = Arrays.asList("A", "shared insert", "B");

        // Identical insertion on both branches should merge cleanly without conflict markers
        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertTrue(res.clean);
        assertEquals(Arrays.asList("A", "shared insert", "B"), res.mergedLines);
    }

    @Test
    public void testDeleteVsModifyConflict() {
        List<String> base = Arrays.asList("A", "B", "C");
        List<String> ours = Arrays.asList("A", "C"); // B deleted
        List<String> theirs = Arrays.asList("A", "B modified", "C"); // B modified

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        assertTrue(res.mergedLines.size() > 0);
    }

    @Test
    public void testModifyVsDeleteConflict() {
        List<String> base = Arrays.asList("A", "B", "C");
        List<String> ours = Arrays.asList("A", "B modified", "C"); // B modified
        List<String> theirs = Arrays.asList("A", "C"); // B deleted

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        assertTrue(res.mergedLines.size() > 0);
    }

    @Test
    public void testCleanDoubleDeletion() {
        List<String> base = Arrays.asList("A", "B", "C");
        List<String> ours = Arrays.asList("A", "C"); // B deleted
        List<String> theirs = Arrays.asList("A", "C"); // B deleted

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertTrue(res.clean);
        assertEquals(Arrays.asList("A", "C"), res.mergedLines);
    }

    @Test
    public void testDiffPrefixSuffixTrimming() {
        List<String> base = Arrays.asList("prefix1", "prefix2", "baseMiddle", "suffix1", "suffix2");
        List<String> target = Arrays.asList("prefix1", "prefix2", "targetMiddle", "suffix1", "suffix2");

        List<LineMerge.Edit> edits = LineMerge.diff(base, target);
        // Prefix/suffix should yield KEEP, middle yields DELETE and INSERT
        assertEquals(6, edits.size());
        assertEquals(LineMerge.EditType.KEEP, edits.get(0).type);
        assertEquals("prefix1", edits.get(0).line);
        assertEquals(LineMerge.EditType.KEEP, edits.get(1).type);
        assertEquals("prefix2", edits.get(1).line);

        assertEquals(LineMerge.EditType.DELETE, edits.get(2).type);
        assertEquals("baseMiddle", edits.get(2).line);

        // Wait! In LineMerge.diff middleEdits adds DELETE and then INSERT
        // Let's print or check other elements
        boolean hasInsert = false;
        for (LineMerge.Edit e : edits) {
            if (e.type == LineMerge.EditType.INSERT && e.line.equals("targetMiddle")) {
                hasInsert = true;
            }
        }
        assertTrue(hasInsert);
    }

    @Test
    public void testDiffComplexityLimit() {
        // Create two highly divergent lists exceeding 25,000,000 DP cells (e.g. 5001 x 5001 = 25,010,001)
        List<String> base = new ArrayList<>(5001);
        List<String> target = new ArrayList<>(5001);
        for (int i = 0; i < 5001; i++) {
            base.add("base_" + i);
            target.add("target_" + i);
        }

        assertThrows(IllegalArgumentException.class, () -> LineMerge.diff(base, target));
    }
}
