package org.perlonjava.backend.jvm;

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

    // Pool of subroutine names to optimize memory usage
    private static final ArrayList<String> subroutineNamePool = new ArrayList<>();
    private static final Map<String, Integer> subroutineNameToId = new HashMap<>();

    public static void resetAll() {
        sourceFiles.clear();

        packageNamePool.clear();
        packageNameToId.clear();

        fileNamePool.clear();
        fileNameToId.clear();

        subroutineNamePool.clear();
        subroutineNameToId.clear();
    }

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
     * Gets or creates a unique identifier for a subroutine name.
     *
     * @param subroutineName The name of the subroutine
     * @return The unique identifier for the subroutine name
     */
    private static int getOrCreateSubroutineId(String subroutineName) {
        return subroutineNameToId.computeIfAbsent(subroutineName, name -> {
            subroutineNamePool.add(name);
            return subroutineNamePool.size() - 1;
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

    /**
     * Saves the source location information for a given token index.
     * This method maps a token index to its corresponding line number
     * and package context in the source file, storing this information
     * in the source file's debug metadata.
     *
     * @param ctx        The current emitter context containing compilation details
     * @param tokenIndex The index of the token in the source code
     */
    public static void saveSourceLocation(EmitterContext ctx, int tokenIndex) {
        // Retrieve or create a unique identifier for the source file
        int fileId = getOrCreateFileId(ctx.errorUtil.getFileName());

        // Get or create the SourceFileInfo object for the file
        SourceFileInfo info = sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);

        // Get current subroutine name (empty string for main code)
        String subroutineName = ctx.symbolTable.getCurrentSubroutine();
        if (subroutineName == null) {
            subroutineName = "";  // Use empty string for main code
        }

        // Map the token index to a LineInfo object containing the line number, package ID, and subroutine ID
        info.tokenToLineInfo.put(tokenIndex, new LineInfo(
                ctx.errorUtil.getLineNumber(tokenIndex), // Get the line number for the token
                getOrCreatePackageId(ctx.symbolTable.getCurrentPackage()), // Get or create the package ID
                getOrCreateSubroutineId(subroutineName) // Get or create the subroutine ID
        ));
    }

    /**
     * Converts a stack trace element to its original source location.
     *
     * @param element The stack trace element to parse
     * @return The corresponding source code location
     */
    public static SourceLocation parseStackTraceElement(StackTraceElement element, HashMap<ByteCodeSourceMapper.SourceLocation, String> locationToClassName) {
        int fileId = fileNameToId.getOrDefault(element.getFileName(), -1);
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = sourceFiles.get(fileId);
        if (info == null) {
            return new SourceLocation(element.getFileName(), "", tokenIndex, null);
        }

        // Use TreeMap's floorEntry to find the nearest defined token index
        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            return new SourceLocation(element.getFileName(), "", element.getLineNumber(), null);
        }

        LineInfo lineInfo = entry.getValue();
        
        // Retrieve subroutine name
        String subroutineName = subroutineNamePool.get(lineInfo.subroutineNameId());
        // If subroutine name is empty string (main code), convert to null
        if (subroutineName != null && subroutineName.isEmpty()) {
            subroutineName = null;
        }
        
        // Create a unique location key using tokenIndex instead of line number
        // This prevents false duplicates when multiple statements are on the same line
        var locationKey = new SourceLocation(
                fileNamePool.get(fileId),
                packageNamePool.get(lineInfo.packageNameId()),
                entry.getKey(),  // Use the actual tokenIndex as the unique identifier
                subroutineName
        );

        // Check if this token position is already assigned to a different class
        String existingClassName = locationToClassName.putIfAbsent(locationKey, element.getClassName());
        if (existingClassName != null && !existingClassName.equals(element.getClassName())) {
            // Different class name already assigned to this token position - return null to avoid duplicate
            return null;
        }

        // Return the location with the actual line number for display
        return new SourceLocation(
                fileNamePool.get(fileId),
                packageNamePool.get(lineInfo.packageNameId()),
                lineInfo.lineNumber(),
                subroutineName
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
     * Associates a line number with its package context and subroutine name.
     */
    private record LineInfo(int lineNumber, int packageNameId, int subroutineNameId) {
    }

    /**
     * Represents a location in the source code, including file name,
     * package name, line number, and subroutine name.
     */
    public record SourceLocation(String sourceFileName, String packageName, int lineNumber, String subroutineName) {
    }
}
