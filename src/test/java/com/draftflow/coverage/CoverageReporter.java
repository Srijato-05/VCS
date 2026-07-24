package com.draftflow.coverage;

import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CoverageReporter {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java CoverageReporter <exec-file> <classes-dir>");
            return;
        }

        File execFile = new File(args[0]);
        File classesDir = new File(args[1]);

        if (!execFile.exists()) {
            System.err.println("Execution file not found: " + execFile);
            return;
        }

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile);

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        analyzer.analyzeAll(classesDir);

        IBundleCoverage bundle = builder.getBundle("DraftFlow");

        System.out.println("=========================================================================================");
        System.out.println("                         DRAFTFLOW FULL CODE COVERAGE METRICS                            ");
        System.out.println("=========================================================================================");
        System.out.printf("%-45s | %-15s | %-15s | %-12s%n", "PACKAGE / FILE CLASS", "LINE COVERAGE", "BRANCH COVERAGE", "INSTRUCTIONS");
        System.out.println("-----------------------------------------------------------------------------------------");

        int grandCoveredLines = 0;
        int grandTotalLines = 0;
        int grandCoveredBranches = 0;
        int grandTotalBranches = 0;

        for (IPackageCoverage pkg : bundle.getPackages()) {
            String pkgName = pkg.getName().isEmpty() ? "default" : pkg.getName().replace('/', '.');
            ICounter pLine = pkg.getLineCounter();
            ICounter pBranch = pkg.getBranchCounter();
            
            double pLinePct = pLine.getTotalCount() == 0 ? 100.0 : (pLine.getCoveredCount() * 100.0 / pLine.getTotalCount());
            double pBranchPct = pBranch.getTotalCount() == 0 ? 100.0 : (pBranch.getCoveredCount() * 100.0 / pBranch.getTotalCount());

            System.out.printf("[FOLDER / PACKAGE] %-30s | %5.1f%% (%3d/%-3d) | %5.1f%% (%2d/%-2d) |%n",
                    pkgName, pLinePct, pLine.getCoveredCount(), pLine.getTotalCount(),
                    pBranchPct, pBranch.getCoveredCount(), pBranch.getTotalCount());
            System.out.println("-----------------------------------------------------------------------------------------");

            for (IClassCoverage cls : pkg.getClasses()) {
                ICounter lineCounter = cls.getLineCounter();
                ICounter branchCounter = cls.getBranchCounter();
                ICounter instCounter = cls.getInstructionCounter();

                int cLines = lineCounter.getCoveredCount();
                int tLines = lineCounter.getTotalCount();
                double linePct = tLines == 0 ? 100.0 : (cLines * 100.0 / tLines);

                int cBranch = branchCounter.getCoveredCount();
                int tBranch = branchCounter.getTotalCount();
                double branchPct = tBranch == 0 ? 100.0 : (cBranch * 100.0 / tBranch);

                int cInst = instCounter.getCoveredCount();
                int tInst = instCounter.getTotalCount();
                double instPct = tInst == 0 ? 100.0 : (cInst * 100.0 / tInst);

                String simpleName = cls.getName().substring(cls.getName().lastIndexOf('/') + 1);
                System.out.printf("  %-43s | %5.1f%% (%3d/%-3d) | %5.1f%% (%2d/%-2d) | %5.1f%%%n",
                        simpleName, linePct, cLines, tLines, branchPct, cBranch, tBranch, instPct);
            }
            System.out.println("-----------------------------------------------------------------------------------------");

            grandCoveredLines += pLine.getCoveredCount();
            grandTotalLines += pLine.getTotalCount();
            grandCoveredBranches += pBranch.getCoveredCount();
            grandTotalBranches += pBranch.getTotalCount();
        }

        double overallLinePct = grandTotalLines == 0 ? 0 : (grandCoveredLines * 100.0 / grandTotalLines);
        double overallBranchPct = grandTotalBranches == 0 ? 0 : (grandCoveredBranches * 100.0 / grandTotalBranches);

        System.out.println("=========================================================================================");
        System.out.printf("OVERALL LINE COVERAGE   : %6.2f%% (%d / %d lines covered)%n", overallLinePct, grandCoveredLines, grandTotalLines);
        System.out.printf("OVERALL BRANCH COVERAGE : %6.2f%% (%d / %d branches covered)%n", overallBranchPct, grandCoveredBranches, grandTotalBranches);
        System.out.println("=========================================================================================");
    }
}
