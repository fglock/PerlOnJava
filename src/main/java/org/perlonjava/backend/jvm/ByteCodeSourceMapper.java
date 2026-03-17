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
     * Also saves the source location info with the correct class context.
     * This is called during emit when we have the correct class name for subroutines.
     *
     * @param ctx        The current emitter context
     * @param tokenIndex The index of the token in the source
     */
    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(tokenIndex, thisLabel);
        
        // Also save source location during emit - this ensures subroutine statements
        // are saved with the correct package context from the emit-time symbol table
        saveSourceLocation(ctx, tokenIndex);
    }

    /**
     * Saves the source location information for a given token index.
     * This method maps a token index to its corresponding line number
     * and package context in the source file, storing this information
     * in the source file's debug metadata.
     * 
     * IMPORTANT: If an entry already exists for this tokenIndex, we preserve the
     * existing LINE NUMBER but update the package and subroutine info. This is
     * because:
     * - Parse-time calls have CORRECT line numbers (getLineNumber works in order)
     * - Emit-time calls have CORRECT package context (subroutine scope is established)
     * - getLineNumber() uses a forward-only cache that fails for out-of-order access
     *
     * @param ctx        The current emitter context containing compilation details
     * @param tokenIndex The index of the token in the source code
     */
    public static void saveSourceLocation(EmitterContext ctx, int tokenIndex) {
        // Use the ORIGINAL filename (compile-time) for the key, not the #line-adjusted one.
        // This is because JVM stack traces report the original filename from visitSource().
        // The #line-adjusted filename is stored separately in LineInfo for caller() reporting.
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);

        // Get or create the SourceFileInfo object for the file
        SourceFileInfo info = sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);

        // Get current subroutine name (empty string for main code)
        String subroutineName = ctx.symbolTable.getCurrentSubroutine();
        if (subroutineName == null) {
            subroutineName = "";  // Use empty string for main code
        }

        // Check if entry already exists - if so, preserve ALL parse-time data.
        // 
        // Why: Parse-time correctly tracks package changes (the parser executes
        // `package Foo;` and updates the symbol table). Emit-time does NOT replay
        // package changes - it just visits AST nodes. So parse-time has correct
        // line numbers AND correct packages, while emit-time may have stale packages.
        //
        // The emit-time call to saveSourceLocation (from setDebugInfoLineNumber)
        // exists to ensure entries are created for ALL bytecode locations. But if
        // an entry already exists from parse-time, we should preserve it entirely.
        LineInfo existingEntry = info.tokenToLineInfo.get(tokenIndex);
        if (existingEntry != null) {
            // Entry already exists from parse-time - preserve it entirely
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG saveSourceLocation: SKIP (exists) file=" + ctx.compilerOptions.fileName 
                    + " tokenIndex=" + tokenIndex + " existingLine=" + existingEntry.lineNumber()
                    + " existingPkg=" + packageNamePool.get(existingEntry.packageNameId())
                    + " existingSourceFile=" + fileNamePool.get(existingEntry.sourceFileNameId()));
            }
            return;
        }
        
        // First time seeing this tokenIndex - compute and store
        int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);
        int packageId = getOrCreatePackageId(ctx.symbolTable.getCurrentPackage());
        
        // Store the #line-adjusted filename (may differ from ctx.compilerOptions.fileName)
        // This is what caller() should report to match Perl's behavior
        int sourceFileNameId = getOrCreateFileId(ctx.errorUtil.getFileName());

        if (System.getenv("DEBUG_CALLER") != null) {
            System.err.println("DEBUG saveSourceLocation: STORE origFile=" + ctx.compilerOptions.fileName 
                + " sourceFile=" + ctx.errorUtil.getFileName()
                + " tokenIndex=" + tokenIndex + " line=" + lineNumber 
                + " pkg=" + ctx.symbolTable.getCurrentPackage() + " sub=" + subroutineName);
        }

        // Map the token index to a LineInfo object containing line, package, subroutine, and source file
        info.tokenToLineInfo.put(tokenIndex, new LineInfo(
                lineNumber,
                packageId,
                getOrCreateSubroutineId(subroutineName),
                sourceFileNameId
        ));
    }

    /**
     * Gets the package name at a specific source location.
     * Used by the interpreter path to look up package from tokenIndex,
     * providing unified package tracking with the JVM bytecode path.
     *
     * @param fileName   The source file name
     * @param tokenIndex The token index to look up
     * @return The package name at that location, or null if not found
     */
    public static String getPackageAtLocation(String fileName, int tokenIndex) {
        int fileId = fileNameToId.getOrDefault(fileName, -1);
        if (fileId == -1) {
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG getPackageAtLocation: NO FILE ID for fileName=" + fileName);
            }
            return null;
        }

        SourceFileInfo info = sourceFiles.get(fileId);
        if (info == null) {
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG getPackageAtLocation: NO SOURCE INFO for fileName=" + fileName + " fileId=" + fileId);
            }
            return null;
        }

        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG getPackageAtLocation: NO ENTRY for fileName=" + fileName + " tokenIndex=" + tokenIndex);
            }
            return null;
        }

        String pkg = packageNamePool.get(entry.getValue().packageNameId());
        if (System.getenv("DEBUG_CALLER") != null) {
            System.err.println("DEBUG getPackageAtLocation: fileName=" + fileName + " tokenIndex=" + tokenIndex 
                + " foundTokenIndex=" + entry.getKey() + " pkg=" + pkg);
        }
        return pkg;
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
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG parseStackTraceElement: NO INFO for file=" + element.getFileName() + " fileId=" + fileId);
            }
            return new SourceLocation(element.getFileName(), "", tokenIndex, null);
        }

        // Use TreeMap's floorEntry to find the nearest defined token index
        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG parseStackTraceElement: NO ENTRY for file=" + element.getFileName() + " tokenIndex=" + tokenIndex);
            }
            return new SourceLocation(element.getFileName(), "", element.getLineNumber(), null);
        }

        LineInfo lineInfo = entry.getValue();
        
        // Get the #line directive-adjusted source filename for caller() reporting
        String sourceFileName = fileNamePool.get(lineInfo.sourceFileNameId());
        
        if (System.getenv("DEBUG_CALLER") != null) {
            System.err.println("DEBUG parseStackTraceElement: file=" + element.getFileName() 
                + " sourceFile=" + sourceFileName
                + " lookupTokenIndex=" + tokenIndex + " foundTokenIndex=" + entry.getKey()
                + " line=" + lineInfo.lineNumber() + " pkg=" + packageNamePool.get(lineInfo.packageNameId()));
        }

        // Retrieve subroutine name
        String subroutineName = subroutineNamePool.get(lineInfo.subroutineNameId());
        // If subroutine name is empty string (main code), convert to null
        if (subroutineName != null && subroutineName.isEmpty()) {
            subroutineName = null;
        }

        // Create a unique location key using tokenIndex instead of line number
        // This prevents false duplicates when multiple statements are on the same line
        // Use the #line-adjusted filename for the key to properly dedupe by reported location
        var locationKey = new SourceLocation(
                sourceFileName,
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

        // Return the location with the #line-adjusted filename and actual line number for display
        return new SourceLocation(
                sourceFileName,
                packageNamePool.get(lineInfo.packageNameId()),
                lineInfo.lineNumber(),
                subroutineName
        );
    }

    /**
     * Associates a line number with its package context, subroutine name, and source file.
     * The sourceFileNameId stores the #line directive-adjusted filename for accurate
     * caller() reporting, which may differ from the original compile-time filename.
     */
    private record LineInfo(int lineNumber, int packageNameId, int subroutineNameId, int sourceFileNameId) {
    }

    /**
     * Represents a location in the source code, including file name,
     * package name, line number, and subroutine name.
     */
    public record SourceLocation(String sourceFileName, String packageName, int lineNumber, String subroutineName) {
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
}
