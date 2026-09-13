package org.perlonjava.backend.jvm;

import org.objectweb.asm.Label;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

/**
 * Maps bytecode positions to their corresponding Perl source code locations.
 * Maintains debug information during compilation and provides source location
 * resolution for stack traces at runtime.
 */
public class ByteCodeSourceMapper {
    public static final class State {
        final Map<Integer, SourceFileInfo> sourceFiles = new HashMap<>();
        final ArrayList<String> packageNamePool = new ArrayList<>();
        final Map<String, Integer> packageNameToId = new HashMap<>();
        final ArrayList<String> fileNamePool = new ArrayList<>();
        final Map<String, Integer> fileNameToId = new HashMap<>();
        final ArrayList<String> subroutineNamePool = new ArrayList<>();
        final Map<String, Integer> subroutineNameToId = new HashMap<>();
        // JVM line-number entries are unsigned shorts. Reserve the high end for
        // logical locations from nested quoted-source parsers, whose local token
        // indices cannot safely be interpreted in the outer source map.
        int nextSyntheticLineNumber = 60_000;

        void clear() {
            sourceFiles.clear();
            packageNamePool.clear();
            packageNameToId.clear();
            fileNamePool.clear();
            fileNameToId.clear();
            subroutineNamePool.clear();
            subroutineNameToId.clear();
            nextSyntheticLineNumber = 60_000;
        }

        /** Copy immutable source-location metadata into an ithread runtime. */
        public void snapshotInto(State target) {
            target.clear();
            target.packageNamePool.addAll(packageNamePool);
            target.packageNameToId.putAll(packageNameToId);
            target.fileNamePool.addAll(fileNamePool);
            target.fileNameToId.putAll(fileNameToId);
            target.subroutineNamePool.addAll(subroutineNamePool);
            target.subroutineNameToId.putAll(subroutineNameToId);
            target.nextSyntheticLineNumber = nextSyntheticLineNumber;
            sourceFiles.forEach((fileId, source) -> {
                SourceFileInfo copy = new SourceFileInfo(source.fileId);
                copy.tokenToLineInfo.putAll(source.tokenToLineInfo);
                target.sourceFiles.put(fileId, copy);
            });
        }
    }

    private static State state() {
        return PerlRuntime.current().sourceMapperState;
    }

    public static void resetAll() {
        state().clear();
    }

    /**
     * Gets or creates a unique identifier for a package name.
     *
     * @param packageName The fully qualified package name
     * @return The unique identifier for the package
     */
    private static int getOrCreatePackageId(String packageName) {
        State state = state();
        return state.packageNameToId.computeIfAbsent(packageName, name -> {
            state.packageNamePool.add(name);
            return state.packageNamePool.size() - 1;
        });
    }

    /**
     * Gets or creates a unique identifier for a file name.
     *
     * @param fileName The name of the source file
     * @return The unique identifier for the file
     */
    private static int getOrCreateFileId(String fileName) {
        State state = state();
        return state.fileNameToId.computeIfAbsent(fileName, name -> {
            state.fileNamePool.add(name);
            return state.fileNamePool.size() - 1;
        });
    }

    /**
     * Gets or creates a unique identifier for a subroutine name.
     *
     * @param subroutineName The name of the subroutine
     * @return The unique identifier for the subroutine name
     */
    private static int getOrCreateSubroutineId(String subroutineName) {
        State state = state();
        return state.subroutineNameToId.computeIfAbsent(subroutineName, name -> {
            state.subroutineNamePool.add(name);
            return state.subroutineNamePool.size() - 1;
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
     * Emits a debug location whose logical source coordinate is independent of
     * the enclosing file's token stream. This is required for code parsed from
     * interpolated strings and heredocs: their lexer indices begin at zero.
     */
    static void setDebugInfoSourceLocation(EmitterContext ctx, String sourceFileName,
                                           int sourceLineNumber) {
        State state = state();
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);
        SourceFileInfo info = state.sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);
        int syntheticLineNumber = state.nextSyntheticLineNumber;
        while (syntheticLineNumber > 0 && info.tokenToLineInfo.containsKey(syntheticLineNumber)) {
            syntheticLineNumber--;
        }
        if (syntheticLineNumber <= 0) {
            throw new IllegalStateException("exhausted synthetic source-location line numbers");
        }
        state.nextSyntheticLineNumber = syntheticLineNumber - 1;

        String subroutineName = ctx.symbolTable.getCurrentSubroutine();
        if (subroutineName == null) {
            subroutineName = "";
        }
        info.tokenToLineInfo.put(syntheticLineNumber, new LineInfo(
                sourceLineNumber,
                getOrCreatePackageId(ctx.symbolTable.getCurrentPackage()),
                getOrCreateSubroutineId(subroutineName),
                getOrCreateFileId(sourceFileName)));

        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(syntheticLineNumber, thisLabel);
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
        State state = state();
        // Use the ORIGINAL filename (compile-time) for the key, not the #line-adjusted one.
        // This is because JVM stack traces report the original filename from visitSource().
        // The #line-adjusted filename is stored separately in LineInfo for caller() reporting.
        int fileId = getOrCreateFileId(ctx.compilerOptions.fileName);

        // Get or create the SourceFileInfo object for the file
        SourceFileInfo info = state.sourceFiles.computeIfAbsent(fileId, SourceFileInfo::new);

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
            // Parse-time location state is authoritative.  In particular, the
            // mutable #line cursor may have advanced to a later directive by
            // emission time, whereas this token's recorded entry preserves its
            // original logical file and line for caller()/warn/die.
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
        
        int sourceFileNameId = getOrCreateFileId(sourceFileName);


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
        State state = state();
        int fileId = state.fileNameToId.getOrDefault(fileName, -1);
        if (fileId == -1) {
            return null;
        }

        SourceFileInfo info = state.sourceFiles.get(fileId);
        if (info == null) {
            return null;
        }

        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            return null;
        }

        String pkg = state.packageNamePool.get(entry.getValue().packageNameId());
        return pkg;
    }

    /**
     * Converts a stack trace element to its original source location.
     *
     * @param element The stack trace element to parse
     * @return The corresponding source code location
     */
    public static SourceLocation parseStackTraceElement(StackTraceElement element, HashMap<ByteCodeSourceMapper.SourceLocation, String> locationToClassName) {
        State state = state();
        int fileId = state.fileNameToId.getOrDefault(element.getFileName(), -1);
        int tokenIndex = element.getLineNumber();

        SourceFileInfo info = state.sourceFiles.get(fileId);
        if (info == null) {
            return new SourceLocation(element.getFileName(), "", tokenIndex, null);
        }

        // Use TreeMap's floorEntry to find the nearest defined token index
        Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
        if (entry == null) {
            return new SourceLocation(element.getFileName(), "", element.getLineNumber(), null);
        }

        LineInfo lineInfo = entry.getValue();
        
        // Get the #line directive-adjusted source filename for caller() reporting
        String sourceFileName = state.fileNamePool.get(lineInfo.sourceFileNameId());
        int lineNumber = lineInfo.lineNumber();
        String packageName = state.packageNamePool.get(lineInfo.packageNameId());
        
        // Retrieve subroutine name
        String subroutineName = state.subroutineNamePool.get(lineInfo.subroutineNameId());
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
