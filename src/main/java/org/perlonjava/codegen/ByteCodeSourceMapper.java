package org.perlonjava.codegen;

import org.objectweb.asm.Label;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps bytecode positions to their corresponding Perl source code locations.
 * Maintains debug information during compilation and provides source location
 * resolution for stack traces at runtime.
 */
public class ByteCodeSourceMapper {
    // Maps source files to their debug information
    private static final Map<Integer, SourceFileInfo> sourceFiles = new HashMap<>();

    // Pool of package names to optimize memory usage
    private static final ArrayList<String> packageNamePool = new ArrayList<>();
    private static final Map<String, Integer> packageNameToId = new HashMap<>();

    // Pool of file names to optimize memory usage
    private static final ArrayList<String> fileNamePool = new ArrayList<>();
    private static final Map<String, Integer> fileNameToId = new HashMap<>();

    /**
     * Gets or creates a unique identifier for a package name.
     *
     * @param packageName The fully qualified package name
     * @return The unique identifier for the package
     */
    private static int getOrCreatePackageId(String packageName) {
        return packageNameToId.computeIfAbsent(packageName, name -> {
            packageNamePool.add(name);
            return packageNamePool.size() - 1;
        });
    }

    /**
     * Gets or creates a unique identifier for a file name.
     *
     * @param fileName The name of the source file
     * @return The unique identifier for the file
     */
    private static int getOrCreateFileId(String fileName) {
        return fileNameToId.computeIfAbsent(fileName, name -> {
            fileNamePool.add(name);
            return fileNamePool.size() - 1;
        });
    }

    /**
     * Sets the source file information in the bytecode metadata.
     *
     * @param ctx The current emitter context
     */
    static void setDebugInfoFileName(EmitterContext ctx) {
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);
        sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);
        ctx.cw.visitSource(ctx.compilerOptions.fileName, null);
    }

    /**
     * Maps a token index to its source line number in the bytecode.
     *
     * @param ctx        The current emitter context
     * @param tokenIndex The index of the token in the source
     */
    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(tokenIndex, thisLabel);
    }

    public static void saveSourceLocation(EmitterContext ctx, int tokenIndex) {
        int fileId = getOrCreateFileId(ctx.errorUtil.getFileName());
        SourceFileInfo info = sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);
        info.tokenToLineInfo.put(tokenIndex, new LineInfo(
                ctx.errorUtil.getLineNumber(tokenIndex),
                getOrCreatePackageId(ctx.symbolTable.getCurrentPackage())
        ));
    }

    /**
     * Converts a stack trace element to its original source location.
     *
     * @param element The stack trace element to parse
     * @return The corresponding source code location
     */
    public static SourceLocation parseStackTraceElement(StackTraceElement element) {
        int fileId = fileNameToId.getOrDefault(element.getFileName(), -1);
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = sourceFiles.get(fileId);
        if (info == null) {
            return new SourceLocation(element.getFileName(), "", tokenIndex);
        }

        // Use TreeMap's floorEntry to find the nearest defined token index
        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            return new SourceLocation(element.getFileName(), "", element.getLineNumber());
        }

        LineInfo lineInfo = entry.getValue();
        return new SourceLocation(
                fileNamePool.get(fileId),
                packageNamePool.get(lineInfo.packageNameId()),
                lineInfo.lineNumber()
        );
    }

    /**
     * Holds debug information for a specific source file including
     * mappings between token indices and line information.
     */
    private static class SourceFileInfo {
        final int fileId;
        final TreeMap<Integer, LineInfo> tokenToLineInfo = new TreeMap<>();

        SourceFileInfo(int fileId) {
            this.fileId = fileId;
        }
    }

    /**
     * Associates a line number with its package context.
     */
    private record LineInfo(int lineNumber, int packageNameId) {
    }

    /**
     * Represents a location in the source code, including file name,
     * package name, and line number.
     */
    public record SourceLocation(String sourceFileName, String packageName, int lineNumber) {
    }
}
