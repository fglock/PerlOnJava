package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Arrays;

public class ExceptionFormatter {

    public static ArrayList<ArrayList<String>> formatException(Throwable t) {
        Throwable innermostCause = findInnermostCause(t);
        return formatThrowable(innermostCause);
    }

    // Find the innermost cause in the exception chain
    public static Throwable findInnermostCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static ArrayList<ArrayList<String>> formatThrowable(Throwable t) {

        ArrayList<ArrayList<String>> stackTrace = new ArrayList<>();

        // Append the main message of the exception
        // sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");

        // Note: filename is formatted in codegen.DebugInfo like:
        // sourceFileName + " @ " + packageName

        // Filter and append the stack trace
        Arrays.stream(t.getStackTrace())
                .filter(element ->
                        element.getMethodName().contains("apply")
                                && !element.getFileName().contains(".java")
                )
                .forEach(element -> {
                    String fileName = element.getFileName();
                    String[] fileNameParts = fileName.split(" @ ", 2); // Split into at most 2 parts
                    String sourceFileName = fileNameParts[0];
                    String packageName = fileNameParts.length > 1 ? fileNameParts[1] : "";

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

