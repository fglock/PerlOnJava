package org.perlonjava.runtime;

import java.util.Arrays;

public class ExceptionFormatter {

    public static String formatException(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable innermostCause = findInnermostCause(t);
        formatThrowable(innermostCause, sb, false);
        return sb.toString();
    }

    // Find the innermost cause in the exception chain
    private static Throwable findInnermostCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static void formatThrowable(Throwable t, StringBuilder sb, boolean isTopLevel) {
        if (!isTopLevel) {
            sb.append("Caused by: ");
        }

        // Append the main message of the exception
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");

        // Filter and append the stack trace
        Arrays.stream(t.getStackTrace())
                .filter(element ->
                        element.getMethodName().contains("apply")
                        && !element.getFileName().contains(".java")
                )
                .forEach(element -> sb.append("\tat ")
                        .append(element.getFileName())
                        .append(" line ")
                        .append(element.getLineNumber())
                        .append("\n"));

        // Recursively handle the cause (if any)
        Throwable cause = t.getCause();
        if (cause != null) {
            formatThrowable(cause, sb, false);  // Recursive call for the cause
        }
    }
}

