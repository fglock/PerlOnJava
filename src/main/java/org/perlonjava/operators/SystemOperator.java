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

/**
 * The SystemOperator class provides functionality to execute system commands
 * and capture their output. It supports both Windows and Unix-like operating systems.
 */
public class SystemOperator {

    // Pattern to detect shell metacharacters
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[*?\\[\\]{}()<>|&;`'\"\\\\$\\s]");

    /**
     * Executes a system command and returns the output as a RuntimeDataProvider.
     * This implements Perl's backtick operator (`command`).
     *
     * @param command The command to execute as a RuntimeScalar.
     * @param ctx     The context type, determining the return type (list or scalar).
     * @return The output of the command as a RuntimeDataProvider.
     * @throws PerlCompilerException if an error occurs during command execution or stream handling.
     */
    public static RuntimeDataProvider systemCommand(RuntimeScalar command, int ctx) {
        CommandResult result = executeCommand(command.toString(), true);

        // Set $? to the exit status
        getGlobalVariable("main::?").set(result.exitCode);

        return processOutput(result.output, ctx);
    }

    /**
     * Executes a system command and returns the exit status.
     * This implements Perl's system() function.
     *
     * @param args The command and arguments as RuntimeList.
     * @return The exit status as a RuntimeScalar.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    public static RuntimeScalar system(RuntimeList args) {
        List<RuntimeBaseEntity> elements = args.elements;
        if (elements.isEmpty()) {
            throw new PerlCompilerException("system: no command specified");
        }

        CommandResult result;

        if (elements.size() == 1) {
            // Single argument - check for shell metacharacters
            String command = elements.get(0).toString();
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
            for (RuntimeBaseEntity element : elements) {
                commandArgs.add(element.toString());
            }
            result = executeCommandDirect(commandArgs);
        }

        // Set $? to the exit status
        getGlobalVariable("main::?").set(result.exitCode);

        return new RuntimeScalar(result.exitCode);
    }

    /**
     * Executes a system command and returns the exit status.
     * This implements Perl's system() function with a single scalar argument.
     *
     * @param command The command to execute as a RuntimeScalar.
     * @return The exit status as a RuntimeScalar.
     * @throws PerlCompilerException if an error occurs during command execution.
     */
    public static RuntimeScalar system(RuntimeScalar command) {
        RuntimeList args = new RuntimeList();
        args.elements.add(command);
        return system(args);
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

            // Capture standard output only if requested (backticks)
            if (captureOutput) {
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // Capture and print standard error to STDERR
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
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
        BufferedReader errorReader = null;
        int exitCode = -1;

        try {
            flushAllHandles();

            ProcessBuilder processBuilder = new ProcessBuilder(commandArgs);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));

            process = processBuilder.start();

            // For system(), we don't capture stdout - it goes to the terminal
            // Capture and print standard error to STDERR
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
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
     * @return The processed output as a RuntimeDataProvider.
     */
    private static RuntimeDataProvider processOutput(String output, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            List<RuntimeBaseEntity> result = list.elements;
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
}