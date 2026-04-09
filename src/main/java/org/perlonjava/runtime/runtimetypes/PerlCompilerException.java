package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.ByteCodeSourceMapper;
import org.perlonjava.runtime.operators.WarnDie;

import java.io.Serial;
import java.util.HashMap;

/**
 * PerlCompilerException is a custom exception class used in the Perl compiler.
 * It extends RuntimeException and provides detailed error messages
 * that include the file name, line number, and a snippet of code.
 */
public class PerlCompilerException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    // Detailed error message that includes additional context about the error
    private final String errorMessage;

    /**
     * Constructs a new PerlCompilerException using the error message utility.
     * This constructor is useful when you have a specific token index and want
     * to format the error message using a utility class.
     *
     * @param tokenIndex       the index of the token where the error occurred
     * @param message          the detail message describing the error
     * @param errorMessageUtil the utility for formatting error messages
     */
    public PerlCompilerException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil) {
        super(message);
        // Use the utility to format the error message with the token index
        this.errorMessage = errorMessageUtil.errorMessage(tokenIndex, message);
    }

    public PerlCompilerException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil, Throwable cause) {
        super(message, cause);
        // Use the utility to format the error message with the token index
        this.errorMessage = errorMessageUtil.errorMessage(tokenIndex, message);
    }

    /**
     * Constructs a new PerlCompilerException using runtime information.
     * This constructor attempts to gather caller information such as package name,
     * file name, and line number to provide more context in the error message.
     *
     * @param message the detail message describing the error
     */
    public PerlCompilerException(String message) {
        super(message);

        if (message.endsWith("\n")) {
            // Return the message as-is if it already ends with a newline
            this.errorMessage = message;
            return;
        }

        // Retrieve caller information: package name, file name, line number.
        // Guard against exceptions from caller() when interpreter state is mid-exception
        // (e.g. PerlCompilerException thrown during interpreter eval STRING execution).
        this.errorMessage = buildErrorMessage(message);
    }

    /**
     * Formats the error message with location and optional filehandle context.
     * Format: "MESSAGE at FILE line N[, <FH> chunk/line N].\n"
     */
    private static String formatWithLocation(String message, String fileName, int lineNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append(message).append(" at ").append(fileName).append(" line ").append(lineNumber);
        // Append filehandle context if available (e.g., ", <DATA> chunk 1")
        String fhContext = WarnDie.getFilehandleContext();
        if (fhContext != null) {
            sb.append(fhContext);
        }
        sb.append(".\n");
        return sb.toString();
    }

    /**
     * Builds the error message with " at FILE line N.\n" suffix by finding the
     * current Perl execution location. Checks JVM stack first for compiled Perl
     * frames (most specific), then falls back to interpreter state.
     *
     * <p>This is different from caller(0) which returns where the current sub
     * was CALLED FROM (the call site). For error messages, we need where the
     * code is currently EXECUTING — like Perl 5's COP (current op).</p>
     */
    private static String buildErrorMessage(String message) {
        try {
            // Scan the JVM stack to find the innermost Perl execution context.
            // We need to determine whether the error originated in JVM-compiled code
            // (anon class) or interpreter-executed code. The key insight:
            // - If an interpreter execution frame (bytecode.* package) appears on the
            //   stack BEFORE any compiled Perl class, the interpreter is the innermost
            //   context and has the most accurate location via ErrorMessageUtil.
            // - If a compiled Perl class appears first, use JVM stack resolution
            //   via ByteCodeSourceMapper.
            var t = new Throwable();
            var locationToClassName = new HashMap<ByteCodeSourceMapper.SourceLocation, String>();
            for (StackTraceElement element : t.getStackTrace()) {
                String className = element.getClassName();

                // Interpreter execution frame — use interpreter state for location
                if (className.startsWith("org.perlonjava.backend.bytecode.")) {
                    var frame = InterpreterState.current();
                    if (frame != null && frame.code() != null) {
                        var pcs = InterpreterState.getPcStack();
                        if (!pcs.isEmpty()) {
                            int currentPc = pcs.getLast();
                            if (frame.code().pcToTokenIndex != null && !frame.code().pcToTokenIndex.isEmpty()) {
                                var pcEntry = frame.code().pcToTokenIndex.floorEntry(currentPc);
                                if (pcEntry != null && frame.code().errorUtil != null) {
                                    var loc = frame.code().errorUtil.getSourceLocationAccurate(pcEntry.getValue());
                                    if (loc.fileName() != null && !loc.fileName().isEmpty()) {
                                        return formatWithLocation(message, loc.fileName(), loc.lineNumber());
                                    }
                                }
                            }
                        }
                    }
                    break; // Interpreter found but couldn't resolve — fall through
                }

                // JVM-compiled Perl frame — resolve via ByteCodeSourceMapper
                // Note: we intentionally skip org.perlonjava.runtime.perlmodule frames
                // because those are Java-implemented Perl builtins (Encode, POSIX, etc.).
                // Errors from those should report the Perl caller's location, not the
                // Java implementation file — matching Perl 5 behavior for XS modules.
                if (className.contains("org.perlonjava.anon")) {
                    var loc = ByteCodeSourceMapper.parseStackTraceElement(element, locationToClassName);
                    if (loc != null && loc.sourceFileName() != null && !loc.sourceFileName().isEmpty()) {
                        return formatWithLocation(message, loc.sourceFileName(), loc.lineNumber());
                    }
                }
            }

            // Last resort: try interpreter state even if no interpreter frame was
            // found on the stack (edge case guard)
            var frame = InterpreterState.current();
            if (frame != null && frame.code() != null) {
                var pcs = InterpreterState.getPcStack();
                if (!pcs.isEmpty()) {
                    int currentPc = pcs.getLast();
                    if (frame.code().pcToTokenIndex != null && !frame.code().pcToTokenIndex.isEmpty()) {
                        var pcEntry = frame.code().pcToTokenIndex.floorEntry(currentPc);
                        if (pcEntry != null && frame.code().errorUtil != null) {
                            var loc = frame.code().errorUtil.getSourceLocationAccurate(pcEntry.getValue());
                            if (loc.fileName() != null && !loc.fileName().isEmpty()) {
                                return formatWithLocation(message, loc.fileName(), loc.lineNumber());
                            }
                        }
                    }
                }
            }

            return message + "\n";
        } catch (Throwable ex) {
            // Guard against exceptions during location resolution
            return message + "\n";
        }
    }

    /**
     * Returns the detailed error message.
     *
     * @return the detailed error message
     */
    @Override
    public String getMessage() {
        return errorMessage;
    }
}
