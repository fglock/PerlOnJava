package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import java.util.*;

/**
 * Maps bytecode positions to their corresponding Perl source code locations.
 * Maintains debug information during compilation and provides source location
 * resolution for stack traces at runtime.
 */
public class ByteCodeSourceMapper {
    // Maps source files to their debug information
    private static final Map<String, SourceFileInfo> sourceFiles = new HashMap<>();

    // Pool of package names to optimize memory usage
    private static final ArrayList<String> packageNamePool = new ArrayList<>();

    // Quick lookup for package name identifiers
    private static final Map<String, Integer> packageNameToId = new HashMap<>();

    /**
     * Holds debug information for a specific source file including
     * mappings between token indices and line information.
     */
    private static class SourceFileInfo {
        final String fileName;
        final Map<Integer, LineInfo> tokenToLineInfo = new HashMap<>();

        SourceFileInfo(String fileName) {
            this.fileName = fileName;
        }
    }

    /**
     * Associates a line number with its package context.
     */
    private record LineInfo(int lineNumber, int packageNameId) {}

    /**
     * Gets or creates a unique identifier for a package name.
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
     * Sets the source file information in the bytecode metadata.
     * @param ctx The current emitter context
     */
    static void setDebugInfoFileName(EmitterContext ctx) {
        String sourceFile = ctx.compilerOptions.fileName;
        sourceFiles.computeIfAbsent(sourceFile, SourceFileInfo::new);
        ctx.cw.visitSource(ctx.compilerOptions.fileName, null);
    }

    /**
     * Maps a token index to its source line number in the bytecode.
     * @param ctx The current emitter context
     * @param tokenIndex The index of the token in the source
     */
    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        String sourceFile = ctx.compilerOptions.fileName;
        SourceFileInfo info = sourceFiles.computeIfAbsent(sourceFile, SourceFileInfo::new);

        info.tokenToLineInfo.put(tokenIndex, new LineInfo(
                ctx.errorUtil.getLineNumber(tokenIndex),
                getOrCreatePackageId(ctx.symbolTable.getCurrentPackage())
        ));

        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(tokenIndex, thisLabel);
    }

    /**
     * Converts a stack trace element to its original source location.
     * @param element The stack trace element to parse
     * @return The corresponding source code location
     */
    public static SourceLocation parseStackTraceElement(StackTraceElement element) {
        String sourceFile = element.getFileName();
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = sourceFiles.get(sourceFile);
        if (info == null) {
            return new SourceLocation(sourceFile, "", tokenIndex);
        }

        LineInfo lineInfo = info.tokenToLineInfo.get(tokenIndex);
        return new SourceLocation(
                sourceFile,
                packageNamePool.get(lineInfo.packageNameId()),
                lineInfo.lineNumber()
        );
    }

    /**
     * Represents a location in the source code, including file name,
     * package name, and line number.
     */
    public record SourceLocation(String sourceFileName, String packageName, int lineNumber) {}
}
