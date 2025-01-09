package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Arrays;

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
     * Formats the stack trace of the given Throwable into a structured list.
     * This method emulates the Perl method "Carp::longmess".
     * It only includes stack trace elements that contain the method name "apply" and do not
     * originate from Java source files.
     *
     * @param t The Throwable whose stack trace is to be formatted.
     * @return A list of lists, where each inner list contains the package name, source file name,
     * and line number of a relevant stack trace element.
     */
    private static ArrayList<ArrayList<String>> formatThrowable(Throwable t) {
        ArrayList<ArrayList<String>> stackTrace = new ArrayList<>();

        // System.out.println("innermostCause: "); innermostCause.printStackTrace();

        // Filter and append the stack trace

        // Filter compiled Perl methods like:
        //     org.perlonjava.anon1.apply(misc/snippets/CallerTest.pm @ CallerTest:36)
        // Filter `org.perlonjava.perlmodule` Perl-like Java methods like:
        //     org.perlonjava.perlmodule.Exporter.exportOkTags(Exporter.java:159)

        Arrays.stream(t.getStackTrace())
                .filter(element ->
                         element.getClassName().contains("org.perlonjava.anon")
                         || element.getClassName().contains("org.perlonjava.perlmodule")
                )
                .forEach(element -> {
                    String fileName = element.getFileName();
                    // Split the file name into source file name and package name.
                    // The filename is formatted in codegen.DebugInfo like:
                    // sourceFileName + " @ " + packageName
                    String[] fileNameParts = fileName.split(" @ ", 2); // Split into at most 2 parts
                    String sourceFileName = fileNameParts[0];
                    String packageName = fileNameParts.length > 1 ? fileNameParts[1] : "";

                    // Add the formatted stack trace element to the list
                    stackTrace.add(
                            new ArrayList<>(
                                    Arrays.asList(
                                            packageName,
                                            sourceFileName,
                                            String.valueOf(element.getLineNumber())
                                    )
                            )
                    );
                });
        return stackTrace;
    }
}
