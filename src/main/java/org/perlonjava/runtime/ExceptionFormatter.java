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
                    stackTrace.add(new ArrayList<>(List.of(
                            callerInfo.packageName(),
                            callerInfo.filename(),
                            String.valueOf(callerInfo.line())
                    )));
                    lastFileName = callerInfo.filename();
                    callerStackIndex++;
                }
            } else if (element.getClassName().contains("org.perlonjava.anon") ||
                    element.getClassName().contains("org.perlonjava.perlmodule")) {
                // parseStackTraceElement returns null if location already seen in a different class
                var loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                if (loc != null) {
                    stackTrace.add(new ArrayList<>(List.of(
                            loc.packageName(),
                            loc.sourceFileName(),
                            String.valueOf(loc.lineNumber())
                    )));
                    lastFileName = loc.sourceFileName();
                }
            }
        }

        // Add the outermost artificial stack entry if different from last file
        var callerInfo = CallerStack.peek(callerStackIndex);
        if (callerInfo != null && !lastFileName.equals(callerInfo.filename())) {
            stackTrace.add(new ArrayList<>(List.of(
                    callerInfo.packageName(),
                    callerInfo.filename(),
                    String.valueOf(callerInfo.line())
            )));
        }
        return stackTrace;
    }

}
