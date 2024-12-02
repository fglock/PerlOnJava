package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.flushFileHandles;

/**
 * The SystemOperator class provides functionality to execute system commands
 * and capture their output. It supports both Windows and Unix-like operating systems.
 */
public class SystemOperator {

    /**
     * Executes a system command and returns the output as a RuntimeDataProvider.
     *
     * @param command The command to execute as a RuntimeScalar.
     * @param ctx     The context type, determining the return type (list or scalar).
     * @return The output of the command as a RuntimeDataProvider.
     * @throws PerlCompilerException if an error occurs during command execution or stream handling.
     */
    public static RuntimeDataProvider systemCommand(RuntimeScalar command, int ctx) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;

        try {
            flushFileHandles();

            // Determine the operating system and set the shell command accordingly
            String os = System.getProperty("os.name").toLowerCase();
            String[] shellCommand;
            if (os.contains("win")) {
                // Windows
                shellCommand = new String[]{"cmd.exe", "/c", command.toString()};
            } else {
                // Unix-like (Linux, macOS)
                shellCommand = new String[]{"/bin/sh", "-c", command.toString()};
            }

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));

            process = processBuilder.start();

            // Capture standard output
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Capture and print standard error to STDERR
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                System.err.println(line);
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new PerlCompilerException("Error: " + e.getMessage());
        } finally {
            closeQuietly(reader);
            closeQuietly(errorReader);
            if (process != null) {
                process.destroy();
            }
        }

        return processOutput(output.toString(), ctx);
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
}
