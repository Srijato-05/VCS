package com.draftflow.diff;

import com.draftflow.merge.AncestorFinder;
import com.draftflow.merge.LineMerge;
import com.draftflow.merge.MergeEngine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DiffAndMergeUnitTest {

    @Test
    public void testStagedHunkComputation() {
        List<String> baseLines = Arrays.asList("line 1", "line 2", "line 3");
        List<String> targetLines = Arrays.asList("line 1", "line 2 modified", "line 3", "line 4 added");

        List<StagedHunk> hunks = StagedHunk.computeHunks(baseLines, targetLines);
        assertFalse(hunks.isEmpty());

        StagedHunk hunk = hunks.get(0);
        assertTrue(hunk.startLineBase >= 0);
        assertFalse(hunk.edits.isEmpty());
    }

    @Test
    public void testLineMergeCleanThreeWay() {
        List<String> base = Arrays.asList("Line 1", "Line 2", "Line 3");
        List<String> ours = Arrays.asList("Line 1", "Line 2 ours", "Line 3");
        List<String> theirs = Arrays.asList("Line 1", "Line 2", "Line 3 theirs");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertTrue(res.clean);
        assertEquals(3, res.mergedLines.size());
        assertEquals("Line 2 ours", res.mergedLines.get(1));
        assertEquals("Line 3 theirs", res.mergedLines.get(2));
    }

    @Test
    public void testLineMergeConflictThreeWay() {
        List<String> base = Arrays.asList("Line 1", "Line 2", "Line 3");
        List<String> ours = Arrays.asList("Line 1", "Line 2 conflict ours", "Line 3");
        List<String> theirs = Arrays.asList("Line 1", "Line 2 conflict theirs", "Line 3");

        LineMerge.MergeResult res = LineMerge.merge(base, ours, theirs);
        assertFalse(res.clean);
        boolean containsConflictMarker = res.mergedLines.stream().anyMatch(l -> l.contains("<<<<<<<") || l.contains("=======") || l.contains(">>>>>>>"));
        assertTrue(containsConflictMarker);
    }
}
