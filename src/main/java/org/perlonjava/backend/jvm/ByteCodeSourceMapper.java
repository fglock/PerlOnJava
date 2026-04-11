package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps bytecode positions to their corresponding Perl source code locations.
 * Maintains debug information during compilation and provides source location
 * resolution for stack traces at runtime.
 */
public class ByteCodeSourceMapper {

    /**
     * Holds all mutable source-mapper state. One instance lives in each PerlRuntime
     * for multiplicity thread-safety.
     */
    public static class State {
        // Maps source files to their debug information
        public final Map<Integer, SourceFileInfo> sourceFiles = new HashMap<>();

        // Pool of package names to optimize memory usage
        public final List<String> packageNamePool = new ArrayList<>();
        public final Map<String, Integer> packageNameToId = new HashMap<>();

        // Pool of file names to optimize memory usage
        public final List<String> fileNamePool = new ArrayList<>();
        public final Map<String, Integer> fileNameToId = new HashMap<>();

        // Pool of subroutine names to optimize memory usage
        public final List<String> subroutineNamePool = new ArrayList<>();
        public final Map<String, Integer> subroutineNameToId = new HashMap<>();

        public void resetAll() {
            sourceFiles.clear();
            packageNamePool.clear();
            packageNameToId.clear();
            fileNamePool.clear();
            fileNameToId.clear();
            subroutineNamePool.clear();
            subroutineNameToId.clear();
        }
    }

    /** Returns the current PerlRuntime's source-mapper state. */
    private static State state() {
        return org.perlonjava.runtime.runtimetypes.PerlRuntime.current().sourceMapperState;
    }

    public static void resetAll() {
        state().resetAll();
    }

    /**
     * Gets or creates a unique identifier for a package name.
     *
     * @param packageName The fully qualified package name
     * @return The unique identifier for the package
     */
    private static int getOrCreatePackageId(String packageName) {
        State s = state();
        return s.packageNameToId.computeIfAbsent(packageName, name -> {
            s.packageNamePool.add(name);
            return s.packageNamePool.size() - 1;
        });
    }

    /**
     * Gets or creates a unique identifier for a file name.
     *
     * @param fileName The name of the source file
     * @return The unique identifier for the file
     */
    private static int getOrCreateFileId(String fileName) {
        State s = state();
        return s.fileNameToId.computeIfAbsent(fileName, name -> {
            s.fileNamePool.add(name);
            return s.fileNamePool.size() - 1;
        });
    }

    /**
     * Gets or creates a unique identifier for a subroutine name.
     *
     * @param subroutineName The name of the subroutine
     * @return The unique identifier for the subroutine name
     */
    private static int getOrCreateSubroutineId(String subroutineName) {
        State s = state();
        return s.subroutineNameToId.computeIfAbsent(subroutineName, name -> {
            s.subroutineNamePool.add(name);
            return s.subroutineNamePool.size() - 1;
        });
    }

    /**
     * Sets the source file information in the bytecode metadata.
     *
     * @param ctx The current emitter context
     */
    static void setDebugInfoFileName(EmitterContext ctx) {
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);
        state().sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);
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
        State s = state();
        // Use the ORIGINAL filename (compile-time) for the key, not the #line-adjusted one.
        // This is because JVM stack traces report the original filename from visitSource().
        // The #line-adjusted filename is stored separately in LineInfo for caller() reporting.
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);

        // Get or create the SourceFileInfo object for the file
        SourceFileInfo info = s.sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);

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
                    + " existingPkg=" + s.packageNamePool.get(existingEntry.packageNameId())
                    + " existingSourceFile=" + s.fileNamePool.get(existingEntry.sourceFileNameId()));
            }
            return;
        }
        
        // First time seeing this tokenIndex - compute and store
        // Use getSourceLocationAccurate() which properly handles #line directives.
        // Unlike getLineNumberAccurate() (which only counts physical newlines),
        // getSourceLocationAccurate() walks all tokens and processes #line N "file"
        // directives, returning the correct adjusted filename and line number.
        var sourceLoc = ctx.errorUtil.getSourceLocationAccurate(tokenIndex);
        int lineNumber = sourceLoc.lineNumber();
        String sourceFileName = sourceLoc.fileName();
        int packageId = getOrCreatePackageId(ctx.symbolTable.getCurrentPackage());
        
        // FIX: If current sourceFile equals original file (no #line active in emit context),
        // check for a nearby parse-time entry that has a #line-adjusted filename.
        // This handles the case where parse-time captured the #line directive but
        // emit-time has a different tokenIndex and stale errorUtil without #line state.
        if (sourceFileName != null && sourceFileName.equals(ctx.compilerOptions.fileName)) {
            // Look for nearby entry (within 50 tokens) that has #line-adjusted filename
            var nearbyEntry = info.tokenToLineInfo.floorEntry(tokenIndex);
            if (nearbyEntry != null && (tokenIndex - nearbyEntry.getKey()) < 50) {
                String nearbySourceFile = s.fileNamePool.get(nearbyEntry.getValue().sourceFileNameId());
                if (!nearbySourceFile.equals(ctx.compilerOptions.fileName)) {
                    // Nearby entry has #line-adjusted filename - inherit it
                    sourceFileName = nearbySourceFile;
                    // Also use the nearby entry's line number calculation for consistency
                    // (the #line directive affects line numbering)
                    lineNumber = nearbyEntry.getValue().lineNumber() + 
                        (ctx.errorUtil.getLineNumber(tokenIndex) - ctx.errorUtil.getLineNumber(nearbyEntry.getKey()));
                }
            }
        }
        
        int sourceFileNameId = getOrCreateFileId(sourceFileName);

        if (System.getenv("DEBUG_CALLER") != null) {
            System.err.println("DEBUG saveSourceLocation: STORE origFile=" + ctx.compilerOptions.fileName 
                + " sourceFile=" + sourceFileName
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
        State s = state();
        int fileId = s.fileNameToId.getOrDefault(fileName, -1);
        if (fileId == -1) {
            if (System.getenv("DEBUG_CALLER") != null) {
                System.err.println("DEBUG getPackageAtLocation: NO FILE ID for fileName=" + fileName);
            }
            return null;
        }

        SourceFileInfo info = s.sourceFiles.get(fileId);
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

        String pkg = s.packageNamePool.get(entry.getValue().packageNameId());
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
        State s = state();
        int fileId = s.fileNameToId.getOrDefault(element.getFileName(), -1);
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = s.sourceFiles.get(fileId);
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
        String sourceFileName = s.fileNamePool.get(lineInfo.sourceFileNameId());
        int lineNumber = lineInfo.lineNumber();
        String packageName = s.packageNamePool.get(lineInfo.packageNameId());
        
        // FIX: If the found entry's sourceFile equals the original file (no #line applied),
        // check for nearby entries that have a #line-adjusted filename.
        // This handles entries stored before the #line directive was processed.
        if (sourceFileName != null && sourceFileName.equals(element.getFileName())) {
            // First, check LOWER entries (in case #line was applied before this code)
            // Find the first entry with a #line-adjusted filename to calculate the offset
            var lowerEntry = info.tokenToLineInfo.lowerEntry(entry.getKey());
            int lineOffset = 0;
            boolean foundLineDirective = false;
            
            while (lowerEntry != null && (entry.getKey() - lowerEntry.getKey()) < 300) {
                String lowerSourceFile = s.fileNamePool.get(lowerEntry.getValue().sourceFileNameId());
                if (System.getenv("DEBUG_CALLER") != null) {
                    System.err.println("DEBUG parseStackTraceElement: checking lowerEntry key=" + lowerEntry.getKey() + " sourceFile=" + lowerSourceFile + " line=" + lowerEntry.getValue().lineNumber() + " entryKey=" + entry.getKey());
                }
                if (!lowerSourceFile.equals(element.getFileName())) {
                    // Found an entry with #line-adjusted filename
                    // Calculate the offset: the difference between the original line and the #line-adjusted line
                    // We need to find where the #line directive was applied
                    foundLineDirective = true;
                    sourceFileName = lowerSourceFile;
                    
                    // The offset is calculated as: original_line_at_directive - adjusted_line_at_directive
                    // For this entry: tokenIndex gives us a rough position
                    // Use the original line number (lineNumber variable) adjusted by the difference
                    // between the #line position and the current position
                    
                    // Simple approach: use the #line entry's adjusted line + offset based on original lines
                    // The original entry has line=19 (physical line in eval), the #line entry has line=2 (adjusted)
                    // at tokenIndex=24. The physical line at tokenIndex=24 would have been around line 3-4.
                    // So the offset is roughly: physical_line_at_directive - adjusted_line_at_directive = 3-4 - 2 = 1-2
                    
                    // Better: use the relationship between the found entry and the #line entry
                    // entry.line = 19 (physical), lowerEntry.line = 2 (adjusted), lowerEntry.tokenIndex = 24
                    // Assume token distance roughly correlates to line distance
                    int tokenDistFromLineDirective = entry.getKey() - lowerEntry.getKey();
                    // The #line-adjusted line at lowerEntry + extrapolation
                    // Estimate ~6 tokens per line for typical Perl code (based on empirical testing)
                    int estimatedExtraLines = tokenDistFromLineDirective / 6;
                    lineNumber = lowerEntry.getValue().lineNumber() + estimatedExtraLines;
                    
                    packageName = s.packageNamePool.get(lowerEntry.getValue().packageNameId());
                    if (System.getenv("DEBUG_CALLER") != null) {
                        System.err.println("DEBUG parseStackTraceElement: APPLYING lowerEntry sourceFile=" + sourceFileName + " adjustedLine=" + lineNumber + " tokenDist=" + tokenDistFromLineDirective);
                    }
                    break;
                }
                // This lower entry still has the original file, keep looking
                lowerEntry = info.tokenToLineInfo.lowerEntry(lowerEntry.getKey());
            }
            
            // If still not found, check HIGHER entries (in case #line is applied after this code)
            if (!foundLineDirective) {
                int currentKey = entry.getKey();
                var higherEntry = info.tokenToLineInfo.higherEntry(currentKey);
                while (higherEntry != null && (higherEntry.getKey() - entry.getKey()) < 50) {
                    String higherSourceFile = s.fileNamePool.get(higherEntry.getValue().sourceFileNameId());
                    if (System.getenv("DEBUG_CALLER") != null) {
                        System.err.println("DEBUG parseStackTraceElement: checking higherEntry key=" + higherEntry.getKey() + " sourceFile=" + higherSourceFile + " entryKey=" + entry.getKey() + " currentKey=" + currentKey);
                    }
                    if (!higherSourceFile.equals(element.getFileName())) {
                        // Higher entry has #line-adjusted filename - use it
                        sourceFileName = higherSourceFile;
                        lineNumber = higherEntry.getValue().lineNumber() - 
                            (higherEntry.getKey() - entry.getKey());  // Approximate adjustment
                        if (lineNumber < 1) lineNumber = 1;
                        packageName = s.packageNamePool.get(higherEntry.getValue().packageNameId());
                        if (System.getenv("DEBUG_CALLER") != null) {
                            System.err.println("DEBUG parseStackTraceElement: APPLYING higherEntry sourceFile=" + sourceFileName + " adjustedLine=" + lineNumber);
                        }
                        break;
                    }
                    // This higher entry still has the original file, keep looking
                    currentKey = higherEntry.getKey();
                    higherEntry = info.tokenToLineInfo.higherEntry(currentKey);
                    if (System.getenv("DEBUG_CALLER") != null) {
                        System.err.println("DEBUG parseStackTraceElement: next higherEntry for key=" + currentKey + " is " + 
                            (higherEntry != null ? "key=" + higherEntry.getKey() : "null"));
                    }
                }
            }
        }
        
        if (System.getenv("DEBUG_CALLER") != null) {
            System.err.println("DEBUG parseStackTraceElement: file=" + element.getFileName() 
                + " sourceFile=" + sourceFileName
                + " lookupTokenIndex=" + tokenIndex + " foundTokenIndex=" + entry.getKey()
                + " line=" + lineNumber + " pkg=" + packageName);
        }

        // Retrieve subroutine name
        String subroutineName = s.subroutineNamePool.get(lineInfo.subroutineNameId());
        // If subroutine name is empty string (main code), convert to null
        if (subroutineName != null && subroutineName.isEmpty()) {
            subroutineName = null;
        }

        // Create a unique location key using tokenIndex instead of line number
        // This prevents false duplicates when multiple statements are on the same line
        // Use the #line-adjusted filename for the key to properly dedupe by reported location
        var locationKey = new SourceLocation(
                sourceFileName,
                packageName,
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
                packageName,
                lineNumber,
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
