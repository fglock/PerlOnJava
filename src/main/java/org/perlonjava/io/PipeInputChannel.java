package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * A pipe input channel that reads raw octets from an external program.
 *
 * <p>This class implements the Perl "-|" pipe mode, which allows reading
 * output from an external command. The command is executed in a separate
 * process, and its stdout is available for reading through this channel.
 *
 * <p>This implementation reads raw octets (bytes) rather than lines,
 * which is more faithful to how Perl's I/O system actually works.
 */
public class PipeInputChannel implements IOHandle {

    /** Pattern to detect shell metacharacters */
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[*?\\[\\]{}()<>|&;`'\"\\\\$\\s]");

    /** The external process */
    private Process process;

    /** Input stream from the process stdout */
    private InputStream inputStream;

    /** Reader for the process stderr (for error handling) */
    private BufferedReader errorReader;

    /** Tracks whether end-of-file has been reached */
    private boolean isEOF;

    /** The exit code of the process (-1 if not yet terminated) */
    private int exitCode = -1;

    // ... (constructor and setup methods remain the same, but remove BufferedReader setup)

    /**
     * Common setup for the process builder.
     *
     * @param processBuilder the process builder to configure
     * @throws IOException if an I/O error occurs starting the process
     */
    private void setupProcess(ProcessBuilder processBuilder) throws IOException {
        // Set working directory to current directory
        String userDir = System.getProperty("user.dir");
        processBuilder.directory(new File(userDir));

        // Start the process
        process = processBuilder.start();

        // Keep raw input stream for octet reading
        inputStream = process.getInputStream();

        // Create reader for stderr only
        errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        // Start a thread to consume stderr to prevent blocking
        Thread errorThread = new Thread(() -> {
            try (BufferedReader err = errorReader) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                // Ignore - process might have terminated
            }
        });
        errorThread.setDaemon(true);
        errorThread.start();
    }

    /**
     * Reads raw octets from the process stdout.
     * This reads exactly the requested number of bytes (or less if EOF/error).
     *
     * @param maxBytes the maximum number of bytes to read
     * @param charset the character encoding to use for converting bytes to string
     * @return RuntimeScalar containing the read data
     */
    @Override
    public RuntimeScalar read(int maxBytes, Charset charset) {
        if (isEOF) {
            return new RuntimeScalar("");
        }

        try {
            // Read raw bytes from the process input stream
            byte[] buffer = new byte[maxBytes];
            int bytesRead = inputStream.read(buffer, 0, maxBytes);

            if (bytesRead == -1) {
                // End of stream reached
                isEOF = true;
                checkProcessExit();
                return new RuntimeScalar("");
            }

            if (bytesRead == 0) {
                // No data available right now, but stream is not closed
                return new RuntimeScalar("");
            }

            // Convert the bytes to string using the specified charset
            String result = new String(buffer, 0, bytesRead, charset);
            return new RuntimeScalar(result);

        } catch (IOException e) {
            isEOF = true;
            checkProcessExit();
            return handleIOException(e, "Read from pipe failed");
        }
    }

    /**
     * Closes the pipe and terminates the process if still running.
     *
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }

            if (process != null && process.isAlive()) {
                // Give the process a moment to terminate naturally
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Force termination if interrupted
                    process.destroyForcibly();
                    exitCode = -1;
                }
            }

            isEOF = true;
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "close pipe failed");
        }
    }

    /**
     * Creates a new PipeInputChannel for the specified command.
     *
     * @param command the command to execute (can be a shell command or program with args)
     * @throws IOException if an I/O error occurs starting the process
     */
    public PipeInputChannel(String command) throws IOException {
        this.isEOF = false;
        startProcess(command);
    }

    /**
     * Creates a new PipeInputChannel for the specified command with arguments.
     *
     * @param commandArgs list of command and arguments
     * @throws IOException if an I/O error occurs starting the process
     */
    public PipeInputChannel(List<String> commandArgs) throws IOException {
        this.isEOF = false;
        startProcessDirect(commandArgs);
    }

    /**
     * Starts the external process using shell interpretation.
     *
     * @param command the command to execute
     * @throws IOException if an I/O error occurs starting the process
     */
    private void startProcess(String command) throws IOException {
        // Determine if we need shell interpretation
        if (SHELL_METACHARACTERS.matcher(command).find()) {
            // Use shell
            String[] shellCommand;
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                shellCommand = new String[]{"cmd.exe", "/c", command};
            } else {
                shellCommand = new String[]{"/bin/sh", "-c", command};
            }

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
            setupProcess(processBuilder);
        } else {
            // No shell metacharacters, execute directly
            String[] words = command.trim().split("\\s+");
            startProcessDirect(Arrays.asList(words));
        }
    }

    /**
     * Starts the external process directly without shell interpretation.
     *
     * @param commandArgs list of command and arguments
     * @throws IOException if an I/O error occurs starting the process
     */
    private void startProcessDirect(List<String> commandArgs) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(commandArgs);
        setupProcess(processBuilder);
    }

    /**
     * Checks if the process has exited and captures the exit code.
     */
    private void checkProcessExit() {
        if (process != null && !process.isAlive()) {
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitCode = -1;
            }
        }
    }

    /**
     * Write operation is not supported for input pipes.
     *
     * @param string the string to write (ignored)
     * @return RuntimeScalar with error
     */
    @Override
    public RuntimeScalar write(String string) {
        return handleIOException(new IOException("Cannot write to input pipe"), "write to input pipe failed");
    }

    /**
     * Checks if end-of-file has been reached.
     *
     * @return RuntimeScalar with true if EOF, false otherwise
     */
    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    /**
     * Tell operation is not supported for pipes.
     *
     * @return RuntimeScalar with -1
     */
    @Override
    public RuntimeScalar tell() {
        return getScalarInt(-1);
    }

    /**
     * Seek operation is not supported for pipes.
     *
     * @param pos the position (ignored)
     * @param whence the whence parameter (ignored)
     * @return RuntimeScalar with false
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        return handleIOException(new IOException("Cannot seek on pipe"), "seek on pipe failed");
    }

    /**
     * Flush operation is not applicable for input pipes.
     *
     * @return RuntimeScalar with true
     */
    @Override
    public RuntimeScalar flush() {
        return scalarTrue;
    }

    /**
     * File descriptor is not available for pipes.
     *
     * @return RuntimeScalar with undef
     */
    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * Truncate operation is not supported for pipes.
     *
     * @param length the length (ignored)
     * @return RuntimeScalar with error
     */
    @Override
    public RuntimeScalar truncate(long length) {
        return handleIOException(new IOException("Cannot truncate pipe"), "truncate pipe failed");
    }

    /**
     * Gets the exit code of the process.
     *
     * @return the exit code, or -1 if the process hasn't exited yet
     */
    public int getExitCode() {
        if (process != null && !process.isAlive() && exitCode == -1) {
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitCode = -1;
            }
        }
        return exitCode;
    }

    /**
     * Checks if the process is still running.
     *
     * @return true if the process is alive, false otherwise
     */
    public boolean isProcessAlive() {
        return process != null && process.isAlive();
    }
}