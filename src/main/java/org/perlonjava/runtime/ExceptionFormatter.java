package org.perlonjava.runtime;

import org.perlonjava.codegen.ByteCodeSourceMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
        ArrayList<ArrayList<String>> stackTrace = new ArrayList<>();
        AtomicInteger callerStackIndex = new AtomicInteger(); // Initialize the index for CallerStack

        // Avoid duplicate stack entries
        ConcurrentHashMap<ByteCodeSourceMapper.SourceLocation, String> locationToClassName = new ConcurrentHashMap<>();

        Arrays.stream(t.getStackTrace())
                .forEach(element -> {
                    if (element.getClassName().equals("org.perlonjava.parser.StatementParser") &&
                            element.getMethodName().equals("parseUseDeclaration")) {
                        // Artificial caller stack entry created at `use` statement
                        CallerStack.CallerInfo callerInfo = CallerStack.peek(callerStackIndex.get());
                        if (callerInfo != null) {
                            stackTrace.add(
                                    new ArrayList<>(
                                            Arrays.asList(
                                                    callerInfo.packageName(),
                                                    callerInfo.filename(),
                                                    String.valueOf(callerInfo.line())
                                            )
                                    )
                            );
                            callerStackIndex.getAndIncrement(); // Increment the index for the next potential match
                        }
                    } else if (element.getClassName().contains("org.perlonjava.anon") ||
                            element.getClassName().contains("org.perlonjava.perlmodule")) {
                        // - Compiled Perl methods like:
                        //     org.perlonjava.anon1.apply(misc/snippets/CallerTest.pm @ CallerTest:36)
                        // - Perl-like Java methods like:
                        //     org.perlonjava.perlmodule.Exporter.exportOkTags(Exporter.java:159)
                        ByteCodeSourceMapper.SourceLocation loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                        if (loc != null) {
                            stackTrace.add(
                                    new ArrayList<>(
                                            Arrays.asList(
                                                    loc.packageName(),
                                                    loc.sourceFileName(),
                                                    String.valueOf(loc.lineNumber())
                                            )
                                    )
                            );
                        }
                    }
                });

        return stackTrace;
    }
}
