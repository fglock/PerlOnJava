package org.perlonjava.codegen;

import org.objectweb.asm.Label;

import java.util.*;

public class DebugInfo {
    private static final Map<String, PackageTracker> activePackages = new HashMap<>();
    private static final NavigableMap<PackageRange, Integer> packageRanges = new TreeMap<>(Comparator.comparing(PackageRange::sourceFile).thenComparingInt(PackageRange::startLine));

    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);
        String sourceFile = ctx.compilerOptions.fileName;
        String currentPackage = ctx.symbolTable.getCurrentPackage();

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
        ctx.mv.visitLineNumber(lineNumber, thisLabel);
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
        String packageName = getPackageForLocation(sourceFileName, element.getLineNumber());
        return new SourceLocation(sourceFileName, packageName, element.getLineNumber());
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
