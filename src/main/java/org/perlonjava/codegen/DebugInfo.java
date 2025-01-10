package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import java.util.*;

public class DebugInfo {
    private static final Map<String, PackageTracker> activePackages = new HashMap<>();
    private static final NavigableMap<PackageRange, Integer> packageRanges = new TreeMap<>(Comparator.comparing(PackageRange::sourceFile).thenComparingInt(PackageRange::startLine));
    private static final Map<Integer, Integer> tokenToLineMap = new HashMap<>();

    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);
        String sourceFile = ctx.compilerOptions.fileName;
        String currentPackage = ctx.symbolTable.getCurrentPackage();

        // Store token to line mapping
        tokenToLineMap.put(tokenIndex, lineNumber);

        PackageTracker tracker = activePackages.get(sourceFile);
        if (tracker == null || !tracker.currentPackage.equals(currentPackage)) {
            if (tracker != null) {
                packageRanges.put(
                        new PackageRange(tracker.sourceFile, tracker.currentPackage, tracker.startLine),
                        lineNumber - 1
                );
            }
            tracker = new PackageTracker();
            tracker.currentPackage = currentPackage;
            tracker.startLine = lineNumber;
            tracker.sourceFile = sourceFile;
            activePackages.put(sourceFile, tracker);
        }

        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(tokenIndex, thisLabel);  // Using tokenIndex instead of lineNumber
    }

    // New method to get line number from token index
    public static int getLineNumberFromToken(int tokenIndex) {
        return tokenToLineMap.getOrDefault(tokenIndex, -1);
    }

    static void setDebugInfoFileName(EmitterContext ctx) {
        ctx.cw.visitSource(ctx.compilerOptions.fileName, null);
    }

    public static String getPackageForLocation(String sourceFile, int lineNumber) {
        var entry = packageRanges.floorEntry(new PackageRange(sourceFile, "", lineNumber));
        if (entry != null && entry.getValue() >= lineNumber) {
            return entry.getKey().packageName();
        }
        PackageTracker tracker = activePackages.get(sourceFile);
        if (tracker != null && lineNumber >= tracker.startLine) {
            return tracker.currentPackage;
        }
        return "";
    }

    public static SourceLocation parseStackTraceElement(StackTraceElement element) {
        String sourceFileName = element.getFileName();
        int tokenIndex = element.getLineNumber(); // Now getting tokenIndex from stack trace
        int actualLineNumber = getLineNumberFromToken(tokenIndex);
        String packageName = getPackageForLocation(sourceFileName, actualLineNumber);
        return new SourceLocation(sourceFileName, packageName, actualLineNumber);
    }

    private static class PackageTracker {
        String currentPackage;
        int startLine;
        String sourceFile;
    }

    private record PackageRange(String sourceFile, String packageName, int startLine) {
    }

    public record SourceLocation(String sourceFileName, String packageName, int lineNumber) {
    }
}
