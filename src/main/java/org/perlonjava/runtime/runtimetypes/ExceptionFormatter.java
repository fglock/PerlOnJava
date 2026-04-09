package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.ByteCodeSourceMapper;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The ExceptionFormatter class provides utility methods for formatting exceptions.
 * It extracts and formats the stack trace of the innermost cause of a given Throwable.
 */
public class ExceptionFormatter {

    /**
     * Result of formatting a stack trace, including metadata about frame types.
     *
     * @param frames                    The formatted stack frames
     * @param firstFrameFromInterpreter True if the first frame was generated from interpreter
     *                                  CallerStack (already represents the call site), false if
     *                                  from JVM class (represents the sub's own location).
     *                                  This affects how caller() should skip frames.
     * @param callerStackConsumed       The number of CallerStack entries consumed during
     *                                  stack trace construction. This includes entries used for
     *                                  both compile-time frames (use/BEGIN) and interpreter frames.
     *                                  caller()'s fallback should skip this many entries.
     */
    public record StackTraceResult(
            ArrayList<ArrayList<String>> frames,
            boolean firstFrameFromInterpreter,
            int callerStackConsumed
    ) {
    }

    /**
     * Formats the innermost cause of the given Throwable into a structured stack trace.
     *
     * @param t The Throwable to format.
     * @return A list of lists, where each inner list contains the package name, source file name,
     * and line number of a stack trace element.
     */
    public static ArrayList<ArrayList<String>> formatException(Throwable t) {
        Throwable innermostCause = findInnermostCause(t);
        return formatThrowable(innermostCause).frames();
    }

    /**
     * Formats the innermost cause with metadata about frame types.
     * Used by caller() to determine correct frame skip behavior.
     *
     * @param t The Throwable to format.
     * @return StackTraceResult with frames and metadata.
     */
    public static StackTraceResult formatExceptionDetailed(Throwable t) {
        Throwable innermostCause = findInnermostCause(t);
        return formatThrowable(innermostCause);
    }

    /**
     * Finds the innermost cause in the exception chain of the given Throwable.
     *
     * @param t The Throwable to inspect.
     * @return The innermost Throwable cause.
     */
    public static Throwable findInnermostCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Formats the stack trace of a Throwable, replacing specific entries with artificial caller stack entries.
     *
     * @param t The Throwable whose stack trace is to be formatted.
     * @return A list of lists, where each inner list represents a stack trace element with package name, source file, and line number.
     */
    private static StackTraceResult formatThrowable(Throwable t) {
        var stackTrace = new ArrayList<ArrayList<String>>();
        int callerStackIndex = 0;
        String lastFileName = "";
        // Track whether the first Perl frame was from the interpreter (CallerStack).
        // Interpreter frames from CallerStack already represent the CALL SITE,
        // while JVM frames represent the sub's OWN location.
        // This distinction matters for caller()'s frame skip logic.
        boolean firstFrameFromInterpreter = false;

        var locationToClassName = new HashMap<ByteCodeSourceMapper.SourceLocation, String>();

        // Snapshot interpreter frames so we can consume them in order.
        // Each BytecodeInterpreter.execute() JVM frame corresponds to one Perl call
        // level; consuming them in order gives the correct nested call stack.
        var interpreterFrames = InterpreterState.getStack();
        var interpreterPcs = InterpreterState.getPcStack();
        // Start at index 0 - caller() will skip this (the current function)
        int interpreterFrameIndex = 0;
        
        // Track whether we've added a frame for the current Perl call level.
        // Multiple execute() frames can occur for the same call level (for internal ops).
        // InterpretedCode.apply marks the END of a call level, so we reset after seeing it.
        boolean addedFrameForCurrentLevel = false;
        
        // Track consecutive runSpecialBlock frames to avoid consuming CallerStack entries
        // twice for the overloaded 3-arg/4-arg method pair.
        boolean lastWasRunSpecialBlock = false;

        for (var element : t.getStackTrace()) {
            boolean isRunSpecialBlock = element.getClassName().equals("org.perlonjava.frontend.parser.SpecialBlockParser") &&
                    element.getMethodName().equals("runSpecialBlock");
            
            if (isRunSpecialBlock && !lastWasRunSpecialBlock) {
                // BEGIN/END/etc wrapper: the CallerStack entry pushed by runSpecialBlock has
                // the CORRECT package, file, and line for the BEGIN block context. Use it to
                // correct the preceding anon class frame, which may have wrong source mapper
                // data when its tokenIndex falls in a gap in ByteCodeSourceMapper entries.
                var callerInfo = CallerStack.peek(callerStackIndex);
                if (callerInfo != null) {
                    if (!stackTrace.isEmpty()) {
                        var lastEntry = stackTrace.getLast();
                        String runSpecialPkg = callerInfo.packageName();
                        lastEntry.set(0, runSpecialPkg != null ? runSpecialPkg : "main");
                        if (callerInfo.filename() != null) {
                            lastEntry.set(1, callerInfo.filename());
                        }
                        lastEntry.set(2, String.valueOf(callerInfo.line()));
                    }
                    lastFileName = callerInfo.filename() != null ? callerInfo.filename() : "";
                    callerStackIndex++;
                }
                lastWasRunSpecialBlock = true;
                continue;
            }
            lastWasRunSpecialBlock = isRunSpecialBlock;
            // Skip the second (overloaded) runSpecialBlock frame
            if (isRunSpecialBlock) continue;
            
            if (element.getClassName().equals("org.perlonjava.frontend.parser.StatementParser") &&
                    element.getMethodName().equals("parseUseDeclaration")) {
                // Artificial caller stack entry created at `use` statement
                var callerInfo = CallerStack.peek(callerStackIndex);
                if (callerInfo != null) {
                    var entry = new ArrayList<String>();
                    String ciPkg = callerInfo.packageName();
                    entry.add(ciPkg != null ? ciPkg : "main");
                    entry.add(callerInfo.filename());
                    entry.add(String.valueOf(callerInfo.line()));
                    entry.add(null);  // No subroutine name available for use statements
                    stackTrace.add(entry);
                    lastFileName = callerInfo.filename() != null ? callerInfo.filename() : "";
                    callerStackIndex++;
                }
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.InterpretedCode") &&
                    element.getMethodName().equals("apply")) {
                // InterpretedCode.apply marks the END of a Perl call level.
                // After this, the next execute frame starts a new call level.
                if (addedFrameForCurrentLevel) {
                    interpreterFrameIndex++;
                    addedFrameForCurrentLevel = false;
                }
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.BytecodeInterpreter") &&
                    element.getMethodName().equals("execute")) {
                // Only add an entry for the current Perl call level once
                if (!addedFrameForCurrentLevel && interpreterFrameIndex < interpreterFrames.size()) {
                    var frame = interpreterFrames.get(interpreterFrameIndex);
                    if (frame != null && frame.code() != null) {
                        // For interpreter frames, use tokenIndex/PC-based lookup to get
                        // the sub's own location. Unlike CallerStack entries (which may come
                        // from compile-time contexts like runSpecialBlock), the PC-based
                        // lookup always gives the correct location for this interpreter frame.
                        // The caller's location will come from the NEXT frame in the stack
                        // (either a JVM anon class or another interpreter frame), and
                        // caller() will skip this frame to reach it.
                        
                        String pkg = null;
                        String filename = frame.code().sourceName;
                        String line = String.valueOf(frame.code().sourceLine);
                        // Get tokenIndex from PC mapping
                        Integer tokenIndex = null;
                        int pc = -1;
                        if (interpreterFrameIndex < interpreterPcs.size()) {
                            pc = interpreterPcs.get(interpreterFrameIndex);
                            if (frame.code().pcToTokenIndex != null && !frame.code().pcToTokenIndex.isEmpty()) {
                                var entryPc = frame.code().pcToTokenIndex.floorEntry(pc);
                                if (entryPc != null) {
                                    tokenIndex = entryPc.getValue();
                                }
                            }
                        }
                        // Look up package from ByteCodeSourceMapper using tokenIndex
                        if (tokenIndex != null && frame.code().sourceName != null) {
                            pkg = ByteCodeSourceMapper.getPackageAtLocation(frame.code().sourceName, tokenIndex);
                        }
                        if (pkg == null) {
                            // Fallback: runtime package for innermost frame, compile-time for others
                            pkg = (interpreterFrameIndex == 0)
                                    ? InterpreterState.currentPackage.get().toString()
                                    : frame.packageName();
                        }

                        // Use tokenIndex for line lookup
                        if (tokenIndex != null && frame.code().errorUtil != null) {
                            ErrorMessageUtil.SourceLocation loc = frame.code().errorUtil.getSourceLocationAccurate(tokenIndex);
                            filename = loc.fileName();
                            line = String.valueOf(loc.lineNumber());
                        }

                        String subName = frame.subroutineName();
                        // Don't add package prefix if subName already contains "::" or is a special name like "(eval)"
                        if (subName != null && !subName.isEmpty() && !subName.contains("::") && !subName.startsWith("(")) {
                            subName = pkg + "::" + subName;
                        }

                        var entry = new ArrayList<String>();
                        entry.add(pkg != null ? pkg : "main");
                        entry.add(filename);
                        entry.add(line);
                        entry.add(subName);
                        // Interpreter frames from tokenIndex/PC represent the sub's OWN
                        // location (like JVM frames), so firstFrameFromInterpreter stays
                        // false and caller() will skip this frame to reach the actual caller.
                        stackTrace.add(entry);
                        lastFileName = filename != null ? filename : "";
                        addedFrameForCurrentLevel = true;
                    }
                }
            } else if (element.getClassName().contains("org.perlonjava.anon") ||
                    element.getClassName().contains("org.perlonjava.runtime.perlmodule")) {
                // parseStackTraceElement returns null if location already seen in a different class
                var loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                if (loc != null) {
                    // Get subroutine name from the source location (now preserved in bytecode metadata)
                    String subName = loc.subroutineName();

                    // Prepend package name if subroutine name doesn't already include it
                    // Don't add package prefix for special names like "(eval)"
                    if (subName != null && !subName.isEmpty() && !subName.contains("::") && !subName.startsWith("(")) {
                        subName = loc.packageName() + "::" + subName;
                    }

                    var entry = new ArrayList<String>();
                    String pkgName = loc.packageName();
                    entry.add(pkgName != null ? pkgName : "main");
                    entry.add(loc.sourceFileName());
                    entry.add(String.valueOf(loc.lineNumber()));
                    entry.add(subName);  // Add subroutine name
                    stackTrace.add(entry);
                    lastFileName = loc.sourceFileName() != null ? loc.sourceFileName() : "";
                }
            }
        }

        // Compute the total number of CallerStack entries consumed.
        // This includes entries consumed by compile-time frame processing (callerStackIndex)
        // and entries consumed by interpreter frame processing (interpreterFrameIndex).
        // The outermost entry check below uses the effective index to avoid re-reading
        // CallerStack entries already consumed by interpreter frame processing.
        int effectiveCallerStackIndex = Math.max(callerStackIndex, interpreterFrameIndex);

        // Add the outermost artificial stack entry if different from last file
        var callerInfo = CallerStack.peek(effectiveCallerStackIndex);
        if (callerInfo != null && callerInfo.filename() != null && !lastFileName.equals(callerInfo.filename())) {
            var entry = new ArrayList<String>();
            String outerPkg = callerInfo.packageName();
            entry.add(outerPkg != null ? outerPkg : "main");
            entry.add(callerInfo.filename());
            entry.add(String.valueOf(callerInfo.line()));
            entry.add(null);  // No subroutine name available
            stackTrace.add(entry);
            effectiveCallerStackIndex++;
        }
        return new StackTraceResult(stackTrace, firstFrameFromInterpreter, effectiveCallerStackIndex);
    }

}
