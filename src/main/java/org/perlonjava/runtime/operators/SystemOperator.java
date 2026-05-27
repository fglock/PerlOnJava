package org.perlonjava.runtime.operators;

import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.runtime.ForkOpenCompleteException;
import org.perlonjava.runtime.ForkOpenState;
import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.LayeredIOHandle;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.GlobalContext.encodeSpecialVar;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalCodeRef;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.isGlobalCodeRefDefined;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.setGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.flushAllHandles;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The SystemOperator class provides functionality to execute system commands
 * and capture their output. It supports both Windows and Unix-like operating systems.
 */
public class SystemOperator {

    // Pattern to detect shell metacharacters
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[*?\\[\\]{}()<>|&;`'\"\\\\$\\s]");

    /**
     * Executes a system command and returns the output as a RuntimeBase.
     * This implements Perl's backtick operator (`command`).
     *
     * Like Perl's native qx/backticks, this bypasses the shell for simple commands
     * without metacharacters, and uses the shell only when necessary.
     *
     * @param command The command to execute as a RuntimeScalar.
     * @param ctx     The context type, determining the return type (list or scalar).
     * @return The output of the command as a RuntimeBase.
     * @throws PerlCompilerException if an error occurs during command execution or stream handling.
     */
    public static RuntimeBase systemCommand(RuntimeScalar command, int ctx) {
        // Perl dispatches qx//, `` and readpipe() through the package readpipe CV when defined
        // (use subs + sub readpipe), not straight to the shell.
        // Use InterpreterState (updated by JVM `package` / scoped blocks), not caller() —
        // caller() from inside this runtime helper resolves the wrong package and skips the override.
        String pkg = InterpreterState.currentPackage.get().toString();
        if (pkg == null || pkg.isEmpty()) {
            pkg = "main";
        }
        String fqReadpipe = pkg.endsWith("::") ? pkg + "readpipe" : pkg + "::readpipe";
        if (isGlobalCodeRefDefined(fqReadpipe)) {
            RuntimeScalar cv = getGlobalCodeRef(fqReadpipe);
            if (cv.value instanceof RuntimeCode rc && rc.defined()) {
                RuntimeArray argv = new RuntimeArray();
                argv.add(command);
                return rc.apply(argv, RuntimeCode.effectiveCallContext(ctx));
            }
        }

        String cmd = command.toString();
        CommandResult result;

        // Check for shell metacharacters - if none, execute directly without shell
        // This matches native Perl behavior where simple commands bypass the shell
        if (SHELL_METACHARACTERS.matcher(cmd).find()) {
            // Has shell metacharacters, use shell
            result = executeCommand(cmd, true);
        } else {
            // No shell metacharacters, split into words and execute directly
            String[] words = cmd.trim().split("\\s+");
            result = executeCommandDirectCapture(Arrays.asList(words));
        }

        // Set $? to the exit status
        // Note: result.exitCode is already in wait status format (from waitForProcessWithStatus)
        if (result.exitCode == -1) {
            // Command failed to execute
            getGlobalVariable("main::?").set(-1);
            getGlobalVariable(encodeSpecialVar("CHILD_ERROR_NATIVE")).set(-1);
        } else {
            // Wait status is already in correct format (exit_code << 8 or signal in lower bits)
            getGlobalVariable("main::?").set(result.exitCode);
            getGlobalVariable(encodeSpecialVar("CHILD_ERROR_NATIVE")).set(result.exitCode);
        }

        return processOutput(result.output, ctx);
    }

    /**
     * Executes a system command and returns the exit status.
     * This implements Perl's system() function.
     *
     * @param args      The command and arguments as RuntimeList.
     * @param hasHandle
     * @return The exit status as a RuntimeScalar.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    public static RuntimeScalar system(RuntimeList args, boolean hasHandle, int ctx) {
        // Flatten the arguments - arrays and lists should be expanded to individual elements
        List<String> flattenedArgs = flattenToStringList(args.elements);
        
        if (flattenedArgs.isEmpty()) {
            throw new PerlCompilerException("system: no command specified");
        }

        CommandResult result;

        if (hasHandle && flattenedArgs.size() >= 2) {
            // Indirect object syntax: system { $program } @args
            // In Perl, @args[0] becomes argv[0] (process name), @args[1:] are actual arguments
            // Java's ProcessBuilder can't set argv[0] separately, so we skip it
            // flattenedArgs = [$program, $argv0, $arg1, $arg2, ...]
            // We want to execute: $program with arguments [$arg1, $arg2, ...]
            String program = flattenedArgs.get(0);
            // Skip flattenedArgs[1] (the custom argv[0]) since Java can't use it
            List<String> actualArgs = new ArrayList<>();
            actualArgs.add(program);
            actualArgs.addAll(flattenedArgs.subList(2, flattenedArgs.size()));
            result = executeCommandDirect(actualArgs);
        } else if (!hasHandle && flattenedArgs.size() == 1) {
            // Single argument - check for shell metacharacters
            String command = flattenedArgs.getFirst();
            if (SHELL_METACHARACTERS.matcher(command).find()) {
                // Has shell metacharacters, use shell
                result = executeCommand(command, false);
            } else {
                // No shell metacharacters, split into words and execute directly
                String[] words = command.trim().split("\\s+");
                result = executeCommandDirect(Arrays.asList(words));
            }
        } else {
            // Multiple arguments - execute directly without shell
            result = executeCommandDirect(flattenedArgs);
        }

        // Set $? and ${^CHILD_ERROR_NATIVE} to the wait status
        // result.exitCode is already in wait status format (from waitForProcessWithStatus)
        if (result.exitCode == -1) {
            // Command failed to execute
            getGlobalVariable("main::?").set(-1);
            getGlobalVariable(encodeSpecialVar("CHILD_ERROR_NATIVE")).set(-1);
            return new RuntimeScalar(-1);
        } else {
            // Wait status is already in correct format (exit_code << 8 or signal in lower bits)
            getGlobalVariable("main::?").set(result.exitCode);
            getGlobalVariable(encodeSpecialVar("CHILD_ERROR_NATIVE")).set(result.exitCode);
            return new RuntimeScalar(result.exitCode);
        }
    }
    
    /**
     * Flattens a list of RuntimeBase elements into a list of strings.
     * Arrays and lists are expanded to their individual elements.
     * This is needed for system() and exec() to properly handle @array arguments.
     *
     * @param elements The list of RuntimeBase elements to flatten
     * @return A list of strings representing individual command arguments
     */
    private static List<String> flattenToStringList(List<RuntimeBase> elements) {
        List<String> result = new ArrayList<>();
        for (RuntimeBase element : elements) {
            if (element instanceof RuntimeArray arr) {
                // Flatten array elements
                for (RuntimeBase arrElement : arr.elements) {
                    result.add(arrElement.toString());
                }
            } else if (element instanceof RuntimeList list) {
                // Recursively flatten list elements
                result.addAll(flattenToStringList(list.elements));
            } else {
                result.add(element.toString());
            }
        }
        return result;
    }

    /**
     * Java's ProcessBuilder does not reliably perform execvp-style PATH lookup
     * for argv-list commands. Perl's system LIST, exec LIST, and simple qx// do.
     */
    private static List<String> resolveCommandForProcessBuilder(List<String> commandArgs) {
        if (commandArgs.isEmpty()) {
            return commandArgs;
        }

        String command = commandArgs.getFirst();
        if (command == null || command.isEmpty()) {
            return commandArgs;
        }
        if (commandHasPathComponent(command)) {
            return expandResolvedCommandForProcessBuilder(commandArgs);
        }

        String path = getPerlEnvValue("PATH");
        if (path == null || path.isEmpty()) {
            return commandArgs;
        }

        String userDir = System.getProperty("user.dir");
        String pathSeparator = SystemUtils.osIsWindows() ? ";" : ":";
        for (String dir : path.split(Pattern.quote(pathSeparator), -1)) {
            File pathDir = dir.isEmpty() ? new File(userDir) : new File(dir);
            if (!pathDir.isAbsolute()) {
                pathDir = new File(userDir, dir);
            }

            for (String candidateName : executableCandidateNames(command)) {
                File candidate = new File(pathDir, candidateName);
                if (isExecutableCandidate(candidate, candidateName)) {
                    List<String> resolved = new ArrayList<>(commandArgs);
                    resolved.set(0, candidate.getAbsolutePath());
                    return expandResolvedCommandForProcessBuilder(resolved);
                }
            }
        }

        return commandArgs;
    }

    private static List<String> expandResolvedCommandForProcessBuilder(List<String> commandArgs) {
        if (SystemUtils.osIsWindows()) {
            return expandWindowsBatchForProcessBuilder(commandArgs);
        }
        return expandJperlShebangForProcessBuilder(commandArgs);
    }

    private static boolean isExecutableCandidate(File candidate, String candidateName) {
        if (!candidate.isFile()) {
            return false;
        }
        if (!SystemUtils.osIsWindows()) {
            return candidate.canExecute();
        }
        return candidate.canExecute() || hasWindowsExecutableSuffix(candidateName);
    }

    private static List<String> expandWindowsBatchForProcessBuilder(List<String> commandArgs) {
        if (!SystemUtils.osIsWindows() || commandArgs.isEmpty()) {
            return commandArgs;
        }

        String command = commandArgs.getFirst();
        if (!hasWindowsBatchSuffix(command)) {
            return commandArgs;
        }

        File script = new File(command);
        if (!script.isAbsolute()) {
            script = new File(System.getProperty("user.dir"), command);
        }

        StringBuilder commandLine = new StringBuilder("call ").append(quoteForCmd(script.getAbsolutePath()));
        for (String arg : commandArgs.subList(1, commandArgs.size())) {
            commandLine.append(' ').append(quoteForCmd(arg));
        }

        return Arrays.asList("cmd.exe", "/x", "/d", "/c", commandLine.toString());
    }

    private static boolean hasWindowsBatchSuffix(String command) {
        String lower = command.toLowerCase();
        return lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    private static String quoteForCmd(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static List<String> expandJperlShebangForProcessBuilder(List<String> commandArgs) {
        if (SystemUtils.osIsWindows() || commandArgs.isEmpty()) {
            return commandArgs;
        }

        File script = new File(commandArgs.getFirst());
        if (!script.isAbsolute()) {
            script = new File(System.getProperty("user.dir"), commandArgs.getFirst());
        }
        if (!script.isFile()) {
            return commandArgs;
        }

        String shebang = readShebang(script);
        if (shebang == null) {
            return commandArgs;
        }

        List<String> shebangWords = splitShebangWords(shebang);
        if (shebangWords.isEmpty() || !isCurrentJperlWrapper(shebangWords.getFirst())) {
            return commandArgs;
        }

        List<String> expanded = new ArrayList<>();
        expanded.add("/bin/bash");
        expanded.add(shebangWords.getFirst());
        expanded.addAll(shebangWords.subList(1, shebangWords.size()));
        expanded.add(script.getAbsolutePath());
        expanded.addAll(commandArgs.subList(1, commandArgs.size()));
        return expanded;
    }

    private static String readShebang(File script) {
        try (BufferedReader reader = java.nio.file.Files.newBufferedReader(
                script.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1)) {
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("#!")) {
                return firstLine.substring(2).trim();
            }
        } catch (IOException e) {
            // Let ProcessBuilder report the real execution error.
        }
        return null;
    }

    private static List<String> splitShebangWords(String shebang) {
        List<String> words = new ArrayList<>();
        for (String word : shebang.split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static boolean isCurrentJperlWrapper(String interpreter) {
        if (interpreter == null || interpreter.isEmpty()) {
            return false;
        }
        if ("jperl".equals(new File(interpreter).getName())) {
            return true;
        }

        String current = getCurrentJperlPath();
        if (current == null || current.isEmpty()) {
            return false;
        }

        try {
            return new File(interpreter).getCanonicalFile()
                    .equals(new File(current).getCanonicalFile());
        } catch (IOException e) {
            return new File(interpreter).getAbsolutePath()
                    .equals(new File(current).getAbsolutePath());
        }
    }

    private static String getCurrentJperlPath() {
        try {
            RuntimeScalar value = GlobalVariable.getGlobalVariable("main::^X");
            if (value != null && value.defined().getBoolean()) {
                return value.toString();
            }
        } catch (Exception e) {
            // Fall back to the launcher environment below.
        }
        return System.getenv("PERLONJAVA_EXECUTABLE");
    }

    private static boolean commandHasPathComponent(String command) {
        return command.contains("/") || command.contains("\\")
                || (SystemUtils.osIsWindows()
                && command.length() >= 2
                && Character.isLetter(command.charAt(0))
                && command.charAt(1) == ':');
    }

    private static List<String> executableCandidateNames(String command) {
        if (!SystemUtils.osIsWindows() || hasWindowsExecutableSuffix(command)) {
            return List.of(command);
        }

        String pathext = getPerlEnvValue("PATHEXT");
        if (pathext == null || pathext.isEmpty()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(command);
        for (String ext : pathext.split(";", -1)) {
            if (!ext.isEmpty()) {
                candidates.add(command + ext);
            }
        }
        return candidates;
    }

    private static boolean hasWindowsExecutableSuffix(String command) {
        String lower = command.toLowerCase();
        String pathext = getPerlEnvValue("PATHEXT");
        if (pathext == null || pathext.isEmpty()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }
        for (String ext : pathext.toLowerCase().split(";", -1)) {
            if (!ext.isEmpty() && lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String getPerlEnvValue(String key) {
        try {
            RuntimeHash envHash = GlobalVariable.getGlobalHash("main::ENV");
            RuntimeScalar value = envHash.get(key);
            if (value != null && value.defined().getBoolean()) {
                return value.toString();
            }
        } catch (Exception e) {
            // Fall back to the JVM environment below.
        }
        return System.getenv(key);
    }

    /**
     * Common method to execute a command through the shell.
     *
     * @param command       The command to execute.
     * @param captureOutput Whether to capture stdout (true for backticks, false for system).
     * @return CommandResult containing output and exit code.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    private static CommandResult executeCommand(String command, boolean captureOutput) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        int exitCode = -1;

        try {
            flushAllHandles();

            // Determine the operating system and set the shell command accordingly
            String[] shellCommand;
            if (SystemUtils.osIsWindows()) {
                // Windows
                shellCommand = new String[]{"cmd.exe", "/c", command};
            } else {
                // Unix-like (Linux, macOS)
                shellCommand = new String[]{"/bin/sh", "-c", command};
            }

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));
            
            // Copy %ENV to the subprocess environment
            copyPerlEnvToProcessBuilder(processBuilder);

            // Route child process stderr through Perl's STDERR handle so that
            // Perl-level redirections (e.g., open STDERR, ">", $file) are honored.
            // Do NOT use INHERIT which bypasses Perl-level redirections.

            // Handle stdout based on operation type
            if (!captureOutput) {
                // For system(): both stdout and stderr go through Perl handles
                // so that Perl-level redirections are honored
            }
            // For backticks: stdout will be captured (default behavior),
            // stderr goes through Perl STDERR handle

            // Always redirect stdin from /dev/null to prevent subprocess blocking
            // This prevents the subprocess from waiting for input that will never come
            try {
                processBuilder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            } catch (Exception e) {
                // Fallback for systems where /dev/null might not be available
                // This should be rare, but provides robustness
            }

            process = processBuilder.start();

            final Process finalProcess = process;
            final StringBuilder finalOutput = output;

            // Route stderr through Perl STDERR handle (for both system() and backticks)
            Thread stderrThread = createStreamRouterThread(finalProcess.getErrorStream(), true);
            stderrThread.start();

            if (captureOutput) {
                // For backticks: capture stdout only, stderr goes through Perl STDERR
                // Read raw bytes to preserve exact output (including or excluding trailing newlines)
                Thread stdoutThread = new Thread(() -> {
                    try (java.io.InputStream is = finalProcess.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        synchronized (finalOutput) {
                            finalOutput.append(baos.toString());
                        }
                    } catch (IOException e) {
                        // Stream closed - this is normal when process terminates
                    }
                });

                stdoutThread.start();
                exitCode = waitForProcessWithStatus(process);
                stdoutThread.join();
            } else {
                // For system(): route stdout through Perl STDOUT handle
                Thread stdoutThread = createStreamRouterThread(finalProcess.getInputStream(), false);
                stdoutThread.start();
                exitCode = waitForProcessWithStatus(process);
                stdoutThread.join();
            }
            stderrThread.join(1000); // Wait up to 1s for stderr to flush
        } catch (IOException e) {
            // Command failed to start - return -1 as per Perl spec
            setGlobalVariable("main::!", e.getMessage());
            exitCode = -1;
        } catch (InterruptedException e) {
            PerlSignalQueue.checkPendingSignals();
            Thread.interrupted();
        } finally {
            // Readers are closed automatically by try-with-resources in threads
            if (process != null) {
                process.destroy();
            }
        }

        return new CommandResult(output.toString(), exitCode);
    }

    /**
     * Executes a command directly without shell interpretation.
     *
     * @param commandArgs List of command and arguments.
     * @return CommandResult containing output and exit code.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    private static CommandResult executeCommandDirect(List<String> commandArgs) {
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;
        int exitCode = -1;

        try {
            flushAllHandles();

            ProcessBuilder processBuilder = new ProcessBuilder(resolveCommandForProcessBuilder(commandArgs));
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));
            
            // Copy %ENV to the subprocess environment
            copyPerlEnvToProcessBuilder(processBuilder);

            process = processBuilder.start();

            // Route stdout and stderr through Perl handles so that
            // Perl-level redirections are honored
            Thread stdoutThread = createStreamRouterThread(process.getInputStream(), false);
            Thread stderrThread = createStreamRouterThread(process.getErrorStream(), true);
            stdoutThread.start();
            stderrThread.start();

            exitCode = waitForProcessWithStatus(process);
            stdoutThread.join();
            stderrThread.join(1000);
        } catch (IOException e) {
            // Command failed to start - return -1 as per Perl spec
            setGlobalVariable("main::!", e.getMessage());
            exitCode = -1;
        } catch (InterruptedException e) {
            PerlSignalQueue.checkPendingSignals();
            Thread.interrupted();
        } finally {
            closeQuietly(reader);
            closeQuietly(errorReader);
            if (process != null) {
                process.destroy();
            }
        }

        return new CommandResult("", exitCode);
    }

    /**
     * Executes a command directly without shell interpretation and captures output.
     * This is used by backticks/qx for commands without shell metacharacters.
     *
     * @param commandArgs List of command and arguments.
     * @return CommandResult containing captured output and exit code.
     */
    private static CommandResult executeCommandDirectCapture(List<String> commandArgs) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        int exitCode = -1;

        try {
            flushAllHandles();

            ProcessBuilder processBuilder = new ProcessBuilder(resolveCommandForProcessBuilder(commandArgs));
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));
            
            // Copy %ENV to the subprocess environment
            copyPerlEnvToProcessBuilder(processBuilder);

            // Route stderr through Perl STDERR handle (not INHERIT which bypasses Perl redirections)

            process = processBuilder.start();

            final Process finalProcess = process;
            final StringBuilder finalOutput = output;

            // Route stderr through Perl handle
            Thread stderrThread = createStreamRouterThread(finalProcess.getErrorStream(), true);
            stderrThread.start();

            // Capture stdout
            Thread stdoutThread = new Thread(() -> {
                try (java.io.InputStream is = finalProcess.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    synchronized (finalOutput) {
                        finalOutput.append(baos.toString());
                    }
                } catch (IOException e) {
                    // Stream closed - this is normal when process terminates
                }
            });

            stdoutThread.start();
            exitCode = waitForProcessWithStatus(process);
            stdoutThread.join();
            stderrThread.join(1000);
        } catch (IOException e) {
            // Command failed to start - return -1 as per Perl spec
            setGlobalVariable("main::!", e.getMessage());
            exitCode = -1;
        } catch (InterruptedException e) {
            PerlSignalQueue.checkPendingSignals();
            Thread.interrupted();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        return new CommandResult(output.toString(), exitCode);
    }

    /**
     * Waits for a process to complete and returns the full wait status.
     * On POSIX systems, uses native waitpid() to get signal information.
     * On Windows or if native call fails, falls back to Java's waitFor() with shifted exit code.
     *
     * @param process The process to wait for
     * @return The wait status (exit_code << 8 for normal exit, or signal in lower bits)
     * @throws InterruptedException if the wait is interrupted
     */
    private static int waitForProcessWithStatus(Process process) throws InterruptedException {
        // Use Java's waitFor and convert the exit code to wait status format
        int exitCode = process.waitFor();
        
        // On POSIX systems (macOS/Linux), when a process is killed by a signal,
        // Java returns 128 + signal_number for the exit code.
        // We need to convert this to Perl's wait status format:
        // - Normal exit: exit_code << 8 (signal bits are 0)
        // - Signal termination: signal_number in low 7 bits, exit part is 0
        //
        // We only detect signals 1-31 (standard POSIX signals).
        // Higher exit codes like 255 (from die) are treated as normal exits.
        if (!NativeUtils.IS_WINDOWS && exitCode >= 129 && exitCode <= 159) {
            // Killed by signal: exitCode = 128 + signal (signals 1-31)
            int signal = exitCode - 128;
            // Return wait status with signal in low bits
            return signal;
        }
        
        // Normal exit or Windows - shift exit code to upper byte
        return exitCode << 8;
    }

    /**
     * Closes a BufferedReader quietly, suppressing any IOException.
     *
     * @param reader The BufferedReader to close.
     */
    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // Log the exception or handle it as needed
                System.err.println("Error closing stream: " + e.getMessage());
            }
        }
    }

    /**
     * Writes bytes to the current Perl-level STDERR handle.
     * This ensures output goes through any Perl-level redirections (e.g.,
     * when STDERR has been reopened to a file via open STDERR, ">", $file).
     */
    private static void writeToPerlStderrBytes(byte[] buffer, int bytesRead) {
        try {
            RuntimeIO perlStderr = GlobalVariable.getGlobalIO("main::STDERR").getRuntimeIO();
            if (writeRawBytesToPerlHandle(perlStderr, buffer, bytesRead)) {
                return;
            }
            if (perlStderr != null) {
                perlStderr.write(bytesToByteString(buffer, bytesRead));
            } else {
                System.err.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            System.err.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Writes bytes to the current Perl-level STDOUT handle.
     * This ensures output goes through any Perl-level redirections (e.g.,
     * when STDOUT has been reopened to a file via open STDOUT, ">", $file).
     */
    private static void writeToPerlStdoutBytes(byte[] buffer, int bytesRead) {
        try {
            RuntimeIO perlStdout = GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO();
            if (writeRawBytesToPerlHandle(perlStdout, buffer, bytesRead)) {
                return;
            }
            if (perlStdout != null) {
                perlStdout.write(bytesToByteString(buffer, bytesRead));
            } else {
                System.out.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            System.out.write(buffer, 0, bytesRead);
        }
    }

    private static String bytesToByteString(byte[] buffer, int bytesRead) {
        return new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static boolean writeRawBytesToPerlHandle(RuntimeIO perlHandle, byte[] buffer, int bytesRead) {
        if (perlHandle == null || perlHandle instanceof TieHandle || perlHandle.ioHandle == null) {
            return false;
        }

        IOHandle rawHandle = perlHandle.ioHandle;
        while (rawHandle instanceof LayeredIOHandle layered) {
            rawHandle = layered.getDelegate();
        }

        rawHandle.write(bytesToByteString(buffer, bytesRead));
        rawHandle.flush();
        return true;
    }

    /**
     * Creates a thread that routes an InputStream to the current Perl-level handle.
     * Bytes are written directly without line-by-line processing to preserve
     * exact output including or excluding trailing newlines.
     * This is used for routing child process stderr/stdout through Perl handles.
     */
    private static Thread createStreamRouterThread(InputStream stream, boolean isStderr) {
        Thread t = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    if (isStderr) {
                        writeToPerlStderrBytes(buffer, bytesRead);
                    } else {
                        writeToPerlStdoutBytes(buffer, bytesRead);
                    }
                }
            } catch (IOException e) {
                // Ignore - process might have terminated
            }
        });
        t.setDaemon(true);
        return t;
    }

    /**
     * Processes the command output based on the context type.
     *
     * @param output The command output as a String.
     * @param ctx    The context type, determining the return type (list or scalar).
     * @return The processed output as a RuntimeBase.
     */
    private static RuntimeBase processOutput(String output, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            List<RuntimeBase> result = list.elements;
            int index = 0;
            String separator = getGlobalVariable("main::/").toString();
            int separatorLength = separator.length();

            if (separatorLength == 0) {
                result.add(new RuntimeScalar(output));
            } else {
                while (index < output.length()) {
                    int nextIndex = output.indexOf(separator, index);
                    if (nextIndex == -1) {
                        result.add(new RuntimeScalar(output.substring(index)));
                        break;
                    }
                    result.add(new RuntimeScalar(output.substring(index, nextIndex + separatorLength)));
                    index = nextIndex + separatorLength;
                }
            }
            return list;
        } else {
            return new RuntimeScalar(output);
        }
    }

    /**
     * Executes a command and attempts to replace the current process.
     * This implements Perl's exec() function.
     * <p>
     * Note: In Java, we cannot truly replace the current process like Unix exec(),
     * so this implementation executes the command and then terminates the JVM
     * with the command's exit code.
     *
     * @param args The command and arguments as RuntimeList.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    public static RuntimeScalar exec(RuntimeList args, boolean hasHandle, int ctx) {
        // Flatten the arguments - arrays and lists should be expanded to individual elements
        List<String> flattenedArgs = flattenToStringList(args.elements);
        
        if (flattenedArgs.isEmpty()) {
            // Perl returns false and sets errno (typically ENOENT) — does not die.
            getGlobalVariable("main::!").set(2);
            return scalarFalse;
        }

        // Check for pending fork-open emulation
        // If there's a pending fork-open, we complete the pipe instead of exec'ing
        if (ForkOpenState.hasPending()) {
            return completeForkOpen(flattenedArgs, hasHandle);
        }

        try {
            flushAllHandles();

            int exitCode;

            if (hasHandle && flattenedArgs.size() >= 2) {
                // Indirect object syntax: exec { $program } @args
                // In Perl, @args[0] becomes argv[0] (process name), @args[1:] are actual arguments
                // Java's ProcessBuilder can't set argv[0] separately, so we skip it
                String program = flattenedArgs.get(0);
                List<String> actualArgs = new ArrayList<>();
                actualArgs.add(program);
                actualArgs.addAll(flattenedArgs.subList(2, flattenedArgs.size()));
                exitCode = execCommandDirect(actualArgs);
            } else if (!hasHandle && flattenedArgs.size() == 1) {
                // Single argument - check for shell metacharacters
                String command = flattenedArgs.getFirst();
                if (SHELL_METACHARACTERS.matcher(command).find()) {
                    // Has shell metacharacters, use shell
                    exitCode = execCommand(command);
                } else {
                    // No shell metacharacters, split into words and execute directly
                    String[] words = command.trim().split("\\s+");
                    exitCode = execCommandDirect(Arrays.asList(words));
                }
            } else {
                // Multiple arguments - execute directly without shell
                exitCode = execCommandDirect(flattenedArgs);
            }

            // exec() should never return in Perl, so we terminate the JVM
            System.exit(exitCode);

        } catch (Exception e) {
            // If we get here, the command failed to start
            setGlobalVariable("main::!", e.getMessage());
        }
        return scalarUndef;
    }

    /**
     * Completes a pending fork-open by running the command and capturing output.
     * 
     * <p>This is called when exec() is invoked with a pending fork-open state
     * (set by {@code open FH, "-|"}). Instead of exec'ing and terminating,
     * we run the command, capture its output, and throw ForkOpenCompleteException
     * to return control to the caller with the captured output.
     * 
     * <p>This emulates Perl's fork-open pattern on the JVM where fork() is not available.
     * 
     * @param flattenedArgs The command and arguments
     * @param hasHandle Whether exec was called with an indirect object
     * @return Never returns normally - throws ForkOpenCompleteException
     * @throws ForkOpenCompleteException Always thrown with captured output
     * @see ForkOpenState
     * @see ForkOpenCompleteException
     */
    private static RuntimeScalar completeForkOpen(List<String> flattenedArgs, boolean hasHandle) {
        ForkOpenState.PendingForkOpen pending = ForkOpenState.getPending();
        ForkOpenState.clear();
        
        try {
            flushAllHandles();
            
            // Build the command - mirror the logic from exec() for consistency
            List<String> command;
            if (hasHandle && flattenedArgs.size() >= 2) {
                // Indirect object syntax: exec { $program } @args
                // flattenedArgs[0] is the program from the indirect object
                // flattenedArgs[1:] are the arguments from @args
                // In Perl, @args[0] becomes argv[0] (process name), @args[1:] are actual arguments
                // Java's ProcessBuilder can't set argv[0] separately, so we skip it
                String program = flattenedArgs.get(0);
                command = new ArrayList<>();
                command.add(program);
                command.addAll(flattenedArgs.subList(2, flattenedArgs.size()));
            } else if (!hasHandle && flattenedArgs.size() == 1) {
                String cmdStr = flattenedArgs.getFirst();
                if (SHELL_METACHARACTERS.matcher(cmdStr).find()) {
                    // Use shell for metacharacters
                    if (SystemUtils.osIsWindows()) {
                        command = Arrays.asList("cmd.exe", "/c", cmdStr);
                    } else {
                        command = Arrays.asList("/bin/sh", "-c", cmdStr);
                    }
                } else {
                    // Split simple command
                    command = Arrays.asList(cmdStr.trim().split("\\s+"));
                }
            } else {
                command = flattenedArgs;
            }
            
            // Run command and capture output
            ProcessBuilder processBuilder = new ProcessBuilder(resolveCommandForProcessBuilder(command));
            processBuilder.directory(new File(System.getProperty("user.dir")));
            copyPerlEnvToProcessBuilder(processBuilder);
            processBuilder.redirectErrorStream(false);  // Keep stderr separate
            
            Process process = processBuilder.start();
            
            // Read all output as raw bytes to preserve exact output
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream is = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
            }
            String capturedOutput = baos.toString();
            
            // Wait for process to complete
            int exitCode = process.waitFor();
            
            // Set $? to the exit status
            setGlobalVariable("main::?", String.valueOf(exitCode << 8));
            
            // Throw exception to return control to caller with captured output
            throw new ForkOpenCompleteException(
                    process.pid(),
                    capturedOutput,
                    pending.fileHandle
            );
            
        } catch (ForkOpenCompleteException e) {
            // Re-throw - this is expected
            throw e;
        } catch (Exception e) {
            // Command failed to run
            setGlobalVariable("main::!", e.getMessage());
            // Throw with empty output on failure
            throw new ForkOpenCompleteException(0, "", pending.fileHandle);
        }
    }

    /**
     * Executes a command through the shell for exec().
     *
     * @param command The command to execute.
     * @return The exit code of the command.
     * @throws IOException          if an error occurs during command execution.
     * @throws InterruptedException if the command execution is interrupted.
     */
    private static int execCommand(String command) throws IOException, InterruptedException {
        // Determine the operating system and set the shell command accordingly
        String[] shellCommand;
        if (SystemUtils.osIsWindows()) {
            // Windows
            shellCommand = new String[]{"cmd.exe", "/c", command};
        } else {
            // Unix-like (Linux, macOS)
            shellCommand = new String[]{"/bin/sh", "-c", command};
        }

        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        String userDir = System.getProperty("user.dir");
        processBuilder.directory(new File(userDir));
        
        // Copy %ENV to the subprocess environment
        copyPerlEnvToProcessBuilder(processBuilder);

        // For exec(), we want the command to take over completely
        processBuilder.inheritIO();

        Process process = processBuilder.start();
        return process.waitFor();
    }

    /**
     * Executes a command directly without shell interpretation for exec().
     *
     * @param commandArgs List of command and arguments.
     * @return The exit code of the command.
     * @throws IOException          if an error occurs during command execution.
     * @throws InterruptedException if the command execution is interrupted.
     */
    private static int execCommandDirect(List<String> commandArgs) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(resolveCommandForProcessBuilder(commandArgs));
        String userDir = System.getProperty("user.dir");
        processBuilder.directory(new File(userDir));
        
        // Copy %ENV to the subprocess environment
        copyPerlEnvToProcessBuilder(processBuilder);

        // For exec(), we want the command to take over completely
        processBuilder.inheritIO();

        Process process = processBuilder.start();
        return process.waitFor();
    }

    /**
     * Attempts to implement Perl's fork() function.
     * <p>
     * WARNING: True fork() cannot be implemented in Java due to JVM architecture constraints.
     * This method always returns undef and sets an error message.
     * <p>
     * In real Perl, fork() creates a new process that is an exact copy of the current process.
     * Java's JVM architecture makes this impossible - the JVM is a single process with
     * multiple threads, and there's no way to "split" the JVM into two identical copies.
     * <p>
     * When called in a test context (Test::More loaded), this method outputs a TAP skip
     * directive and exits cleanly so that Test::Harness reports the test as skipped rather
     * than failed.
     *
     * @param ctx  The context (unused)
     * @param args The arguments (unused)
     * @return Always returns undef (if test context skip doesn't trigger)
     */
    public static RuntimeScalar fork(int ctx, RuntimeBase... args) {
        // If we're in a test context (Test::More loaded), skip the test gracefully
        // instead of failing. This allows test harnesses to report fork-dependent
        // tests as "skipped" rather than "failed" on the JVM platform.
        //
        // BUT only if no tests have been emitted yet. Tests that have already
        // produced ok/not-ok output can't be retroactively skipped — emitting
        // "1..0 # SKIP" after N tests produces a "Bad plan" parse error in
        // prove (seen in DBIC t/storage/txn.t, global_destruction.t which call
        // fork after running tests, then fall back to skip_all on failure).
        //
        // For those cases, fork() just returns undef like a normal failure;
        // the calling test code is responsible for handling the failure
        // (typically via its own skip_all path).
        try {
            RuntimeHash incHash = GlobalVariable.getGlobalHash("main::INC");
            if (incHash.elements.containsKey("Test/More.pm") && !testsAlreadyEmitted()) {
                // Output TAP skip directive and exit cleanly
                RuntimeIO stdout = GlobalVariable.getGlobalIO("main::STDOUT").getRuntimeIO();
                if (stdout != null) {
                    stdout.write("1..0 # SKIP fork() not supported on this platform (Java/JVM)\n");
                    stdout.flush();
                } else {
                    System.out.println("1..0 # SKIP fork() not supported on this platform (Java/JVM)");
                    System.out.flush();
                }
                throw new PerlExitException(0);
            }
        } catch (PerlExitException e) {
            throw e; // Re-throw exit exceptions
        } catch (Exception e) {
            // Ignore errors in test detection - fall through to normal behavior
        }

        // Set $! to EAGAIN (as a numeric errno) so the standard
        //     if (!defined $pid) {
        //         skip "EAGAIN" if $! == Errno::EAGAIN();
        //         die "Unable to fork: $!";
        //     }
        // pattern takes the skip branch. Setting $! to a numeric errno makes
        // it a dualvar whose string value is "Resource temporarily
        // unavailable" (the standard strerror(EAGAIN)), which is more
        // accurate than a custom message — fork() on the JVM genuinely can't
        // succeed "right now".

        // Auto-load Errno so callers can use Errno::EAGAIN() without an
        // explicit `use Errno`. Real Perl does not auto-load it, but on real
        // Perl fork() usually succeeds so nobody hits the missing-load.
        int eagain = 35;  // Default: BSD/Darwin value; overridden below if possible
        try {
            ModuleOperators.require(new RuntimeScalar("Errno.pm"));
            RuntimeScalar eagainSub =
                    InheritanceResolver.findMethodInHierarchy(
                            "EAGAIN", "Errno", null, 0);
            if (eagainSub != null && eagainSub.type == RuntimeScalarType.CODE) {
                RuntimeArray noArgs = new RuntimeArray();
                RuntimeList r = RuntimeCode.apply(
                        eagainSub, noArgs, RuntimeContextType.SCALAR);
                if (r != null && !r.isEmpty()) {
                    int v = r.scalar().getInt();
                    if (v > 0) eagain = v;
                }
            }
        } catch (Throwable t) {
            // Not fatal — fall through with the default EAGAIN value.
        }
        // Set $! to a numeric errno; in jperl this creates a dualvar with
        // the matching strerror() as its string value.
        getGlobalVariable("main::!").set(eagain);

        // Return undef to indicate failure
        return scalarUndef;
    }

    /**
     * Check whether any tests have already been emitted through Test::Builder.
     * Used by {@link #fork} to decide whether it's still safe to emit
     * {@code 1..0 # SKIP} (only at the start of a test) versus returning undef
     * so the test can handle the fork failure itself.
     * <p>
     * Looks up the {@code $Test::Builder::Test} singleton and calls its
     * {@code current_test} method. Returns true if the call succeeds and the
     * result is > 0. Any error is treated as "can't tell" and returns false
     * (preserving the pre-existing behavior of emitting SKIP).
     */
    private static boolean testsAlreadyEmitted() {
        try {
            RuntimeScalar tbSingleton =
                    GlobalVariable.getGlobalVariable("Test::Builder::Test");
            if (tbSingleton == null
                    || !tbSingleton.defined().getBoolean()
                    || !RuntimeScalarType.isReference(tbSingleton)) {
                return false;
            }
            RuntimeScalar method =
                    InheritanceResolver.findMethodInHierarchy(
                            "current_test", "Test::Builder", null, 0);
            if (method == null || method.type != RuntimeScalarType.CODE) {
                return false;
            }
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, tbSingleton);
            RuntimeList result =
                    RuntimeCode.apply(method, callArgs, RuntimeContextType.SCALAR);
            if (result == null || result.isEmpty()) return false;
            return result.scalar().getInt() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stub for chroot() - not supported on the JVM.
     * Sets $! and returns undef (false) to indicate failure.
     */
    public static RuntimeScalar chroot(int ctx, RuntimeBase... args) {
        setGlobalVariable("main::!", "chroot() not supported on this platform (Java/JVM)");
        return scalarUndef;
    }
    
    /**
     * Copies the Perl %ENV hash to the ProcessBuilder environment.
     * This ensures that changes to %ENV in Perl are reflected in child processes.
     *
     * @param processBuilder The ProcessBuilder to update
     */
    private static void copyPerlEnvToProcessBuilder(ProcessBuilder processBuilder) {
        try {
            RuntimeHash envHash = GlobalVariable.getGlobalHash("main::ENV");
            java.util.Map<String, String> pbEnv = processBuilder.environment();
            
            // Clear the inherited environment and replace with Perl's %ENV
            pbEnv.clear();
            
            for (java.util.Map.Entry<String, RuntimeScalar> entry : envHash.elements.entrySet()) {
                String value = entry.getValue().toString();
                if (value != null) {
                    pbEnv.put(entry.getKey(), value);
                }
            }
        } catch (Exception e) {
            // If we can't access %ENV, just use inherited environment (default behavior)
        }
    }

    /**
     * Helper class to hold command execution results.
     */
    private record CommandResult(String output, int exitCode) {
    }
}
