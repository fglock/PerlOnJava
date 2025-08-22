package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.GlobalVariable.setGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.flushAllHandles;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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
     * @param command The command to execute as a RuntimeScalar.
     * @param ctx     The context type, determining the return type (list or scalar).
     * @return The output of the command as a RuntimeBase.
     * @throws PerlCompilerException if an error occurs during command execution or stream handling.
     */
    public static RuntimeBase systemCommand(RuntimeScalar command, int ctx) {
        CommandResult result = executeCommand(command.toString(), true);

        // Set $? to the exit status
        if (result.exitCode == -1) {
            // Command failed to execute
            getGlobalVariable("main::?").set(-1);
        } else {
            // Normal exit - put exit code in upper byte
            getGlobalVariable("main::?").set(result.exitCode << 8);
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
        List<RuntimeBase> elements = args.elements;
        if (elements.isEmpty()) {
            throw new PerlCompilerException("system: no command specified");
        }

        CommandResult result;

        if (!hasHandle && elements.size() == 1) {
            // Single argument - check for shell metacharacters
            String command = elements.getFirst().toString();
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
            List<String> commandArgs = new ArrayList<>();
            for (RuntimeBase element : elements) {
                commandArgs.add(element.toString());
            }
            result = executeCommandDirect(commandArgs);
        }

        // Set $? to the exit status
        if (result.exitCode == -1) {
            // Command failed to execute
            getGlobalVariable("main::?").set(-1);
        } else {
            // Normal exit - put exit code in upper byte
            getGlobalVariable("main::?").set(result.exitCode << 8);
        }

        return new RuntimeScalar(result.exitCode);
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
        BufferedReader reader = null;
        BufferedReader errorReader = null;
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

            process = processBuilder.start();
            String line;
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            if (captureOutput) {
                // For backticks: capture stdout, stderr goes to terminal
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } else {
                // For system(): pipe stdout and stderr to terminal
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            // pipe stderr to terminal
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                System.err.println(line);
            }

            exitCode = process.waitFor();
        } catch (IOException e) {
            // Command failed to start - return -1 as per Perl spec
            setGlobalVariable("main::!", e.getMessage());
            exitCode = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PerlCompilerException("Command execution interrupted: " + e.getMessage());
        } finally {
            closeQuietly(reader);
            closeQuietly(errorReader);
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

            ProcessBuilder processBuilder = new ProcessBuilder(commandArgs);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));

            process = processBuilder.start();

            // For system(), pipe stdout and stderr to terminal
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                System.err.println(line);
            }

            exitCode = process.waitFor();
        } catch (IOException e) {
            // Command failed to start - return -1 as per Perl spec
            setGlobalVariable("main::!", e.getMessage());
            exitCode = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PerlCompilerException("Command execution interrupted: " + e.getMessage());
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
     * Helper class to hold command execution results.
     */
    private static class CommandResult {
        final String output;
        final int exitCode;

        CommandResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
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
        List<RuntimeBase> elements = args.elements;
        if (elements.isEmpty()) {
            throw new PerlCompilerException("exec: no command specified");
        }

        try {
            flushAllHandles();

            int exitCode;

            if (!hasHandle && elements.size() == 1) {
                // Single argument - check for shell metacharacters
                String command = elements.getFirst().toString();
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
                List<String> commandArgs = new ArrayList<>();
                for (RuntimeBase element : elements) {
                    commandArgs.add(element.toString());
                }
                exitCode = execCommandDirect(commandArgs);
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
     * Executes a command through the shell for exec().
     *
     * @param command The command to execute.
     * @return The exit code of the command.
     * @throws IOException if an error occurs during command execution.
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
     * @throws IOException if an error occurs during command execution.
     * @throws InterruptedException if the command execution is interrupted.
     */
    private static int execCommandDirect(List<String> commandArgs) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(commandArgs);
        String userDir = System.getProperty("user.dir");
        processBuilder.directory(new File(userDir));

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
     *
     * @return Always returns undef
     */
    public static RuntimeScalar fork(RuntimeList args, int ctx) {
        // Set $! to indicate why fork failed
        setGlobalVariable("main::!", "fork() not supported on this platform (Java/JVM)");

        // Return undef to indicate failure
        return scalarUndef;
    }
}