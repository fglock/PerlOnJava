package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * A pipe output channel that writes data to an external program.
 *
 * <p>This class implements the Perl "|-" pipe mode, which allows writing
 * data to an external command. The command is executed in a separate
 * process, and data written to this channel is sent to the process stdin.
 *
 * <p>Key features:
 * <ul>
 *   <li>Supports both shell commands and direct program execution</li>
 *   <li>Handles character encoding properly</li>
 *   <li>Manages process lifecycle and cleanup</li>
 *   <li>Provides access to the process exit code</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Create a pipe to write to 'cat' command
 * PipeOutputChannel pipe = new PipeOutputChannel("cat -n > numbered.txt");
 *
 * // Write data to the command
 * pipe.write("Hello World\n");
 * pipe.write("Second line\n");
 *
 * // Close and get exit code
 * pipe.close();
 * int exitCode = pipe.getExitCode();
 * </pre>
 */
public class PipeOutputChannel implements IOHandle {

    /**
     * Pattern to detect shell metacharacters
     */
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[*?\\[\\]{}()<>|&;`'\"\\\\$\\s]");

    /**
     * The external process
     */
    private Process process;

    /**
     * Writer for the process stdin
     */
    private BufferedWriter writer;

    /**
     * Reader for the process stderr (for error handling)
     */
    private BufferedReader errorReader;

    /**
     * Reader for the process stdout (for debugging/monitoring)
     */
    private BufferedReader outputReader;

    /**
     * Tracks whether the pipe has been closed
     */
    private boolean isClosed;

    /**
     * The exit code of the process (-1 if not yet terminated)
     */
    private int exitCode = 0;

    /**
     * Creates a new PipeOutputChannel for the specified command.
     *
     * @param command the command to execute (can be a shell command or program with args)
     * @throws IOException if an I/O error occurs starting the process
     */
    public PipeOutputChannel(String command) throws IOException {
        this.isClosed = false;
        startProcess(command);
    }

    /**
     * Creates a new PipeOutputChannel for the specified command with arguments.
     *
     * @param commandArgs list of command and arguments
     * @throws IOException if an I/O error occurs starting the process
     */
    public PipeOutputChannel(List<String> commandArgs) throws IOException {
        this.isClosed = false;
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

        // Create writer for stdin
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        // Create readers for stdout and stderr
        outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        // Start threads to consume stdout and stderr to prevent blocking
        Thread outputThread = new Thread(() -> {
            try (BufferedReader out = outputReader) {
                String line;
                while ((line = out.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // Ignore - process might have terminated
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

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
     * Writes a string to the process stdin.
     *
     * @param string the string to write
     * @return RuntimeScalar containing the number of bytes written
     */
    @Override
    public RuntimeScalar write(String string) {
        if (isClosed) {
            return handleIOException(new IOException("Pipe is closed"), "write to closed pipe failed");
        }

        try {
            // String contains raw bytes (each char is a byte value 0-255)
            byte[] bytes = new byte[string.length()];
            for (int i = 0; i < string.length(); i++) {
                bytes[i] = (byte) string.charAt(i);
            }

            // Write raw bytes to process
            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();

            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "Write to pipe failed");
        }
    }

    /**
     * Read operation is not supported for output pipes.
     *
     * @param maxBytes the maximum number of bytes to read (ignored)
     * @param charset  the character encoding (ignored)
     * @return RuntimeScalar with empty string to signal EOF/error
     */
    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        // Set error in $! but return empty string to signal EOF
        handleIOException(new IOException("Cannot read from output pipe"), "read from output pipe failed");
        return new RuntimeScalar(""); // Return empty string to break readline loop
    }

    /**
     * Closes the pipe and terminates the process if still running.
     *
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar close() {
        if (isClosed) {
            return scalarTrue;
        }

        try {
            // Close the writer to signal EOF to the process
            if (writer != null) {
                writer.close();
            }

            // Wait for the process to complete
            if (process != null && process.isAlive()) {
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Force termination if interrupted
                    process.destroyForcibly();
                    exitCode = -1;
                }
            }
            getGlobalVariable("main::?").set(exitCode << 8);

            isClosed = true;
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "close pipe failed");
        }
    }

    /**
     * EOF for output pipes - always true since you can't read from write-only pipes.
     *
     * @return RuntimeScalar with true (always EOF for read operations on write-only pipes)
     */
    @Override
    public RuntimeScalar eof() {
        // For write-only pipes, reading should always be considered EOF
        // This ensures readline operations terminate properly
        return new RuntimeScalar(true);
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
     * @param pos    the position (ignored)
     * @param whence the whence parameter (ignored)
     * @return RuntimeScalar with false
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        return handleIOException(new IOException("Cannot seek on pipe"), "seek on pipe failed");
    }

    /**
     * Flushes any buffered data to the process.
     *
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar flush() {
        if (isClosed) {
            return scalarTrue;
        }

        try {
            if (writer != null) {
                writer.flush();
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "flush pipe failed");
        }
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
     * Checks if the process is still running.
     *
     * @return true if the process is alive, false otherwise
     */
    public boolean isProcessAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Checks if the pipe has been closed.
     *
     * @return true if the pipe is closed, false otherwise
     */
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        try {
            // Convert string to bytes
            byte[] bytes = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) {
                bytes[i] = (byte) (data.charAt(i) & 0xFF);
            }

            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();

            return new RuntimeScalar(bytes.length);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(e.getMessage());
            return new RuntimeScalar(); // undef
        }
    }
}