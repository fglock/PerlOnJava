package org.perlonjava.runtime;

import org.perlonjava.codegen.ByteCodeSourceMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The ExceptionFormatter class provides utility methods for formatting exceptions.
 * It extracts and formats the stack trace of the innermost cause of a given Throwable.
 */
public class ExceptionFormatter {

    /**
     * Formats the innermost cause of the given Throwable into a structured stack trace.
     *
     * @param t The Throwable to format.
     * @return A list of lists, where each inner list contains the package name, source file name,
     * and line number of a stack trace element.
     */
    public static ArrayList<ArrayList<String>> formatException(Throwable t) {
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
    private static ArrayList<ArrayList<String>> formatThrowable(Throwable t) {
        var stackTrace = new ArrayList<ArrayList<String>>();
        int callerStackIndex = 0;
        String lastFileName = "";

        var locationToClassName = new HashMap<ByteCodeSourceMapper.SourceLocation, String>();

        for (var element : t.getStackTrace()) {
            if (element.getClassName().equals("org.perlonjava.parser.StatementParser") &&
                    element.getMethodName().equals("parseUseDeclaration")) {
                // Artificial caller stack entry created at `use` statement
                var callerInfo = CallerStack.peek(callerStackIndex);
                if (callerInfo != null) {
                    var entry = new ArrayList<String>();
                    entry.add(callerInfo.packageName());
                    entry.add(callerInfo.filename());
                    entry.add(String.valueOf(callerInfo.line()));
                    entry.add(null);  // No subroutine name available for use statements
                    stackTrace.add(entry);
                    lastFileName = callerInfo.filename() != null ? callerInfo.filename() : "";
                    callerStackIndex++;
                }
            } else if (element.getClassName().contains("org.perlonjava.anon") ||
                    element.getClassName().contains("org.perlonjava.perlmodule")) {
                // parseStackTraceElement returns null if location already seen in a different class
                var loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                if (loc != null) {
                    // Get subroutine name from the source location (now preserved in bytecode metadata)
                    String subName = loc.subroutineName();
                    
                    // Prepend package name if subroutine name doesn't already include it
                    if (subName != null && !subName.isEmpty() && !subName.contains("::")) {
                        subName = loc.packageName() + "::" + subName;
                    }
                    
                    var entry = new ArrayList<String>();
                    entry.add(loc.packageName());
                    entry.add(loc.sourceFileName());
                    entry.add(String.valueOf(loc.lineNumber()));
                    entry.add(subName);  // Add subroutine name
                    stackTrace.add(entry);
                    lastFileName = loc.sourceFileName() != null ? loc.sourceFileName() : "";
                }
            }
        }

        // Add the outermost artificial stack entry if different from last file
        var callerInfo = CallerStack.peek(callerStackIndex);
        if (callerInfo != null && callerInfo.filename() != null && !lastFileName.equals(callerInfo.filename())) {
            var entry = new ArrayList<String>();
            entry.add(callerInfo.packageName());
            entry.add(callerInfo.filename());
            entry.add(String.valueOf(callerInfo.line()));
            entry.add(null);  // No subroutine name available
            stackTrace.add(entry);
        }
        return stackTrace;
    }

}
