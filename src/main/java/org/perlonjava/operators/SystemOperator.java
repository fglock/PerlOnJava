package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.flushFileHandles;

public class SystemOperator {
    /**
     * Executes a shell command and captures only standard output.
     * Standard error is printed to the standard error stream.
     *
     * @param command The command to execute.
     * @return The output of the command as a string, including only stdout.
     */
    public static RuntimeDataProvider systemCommand(RuntimeScalar command, int ctx) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;

        try {
            flushFileHandles();

            // Use ProcessBuilder to execute the command
            ProcessBuilder processBuilder = new ProcessBuilder(command.toString().split(" "));

            // Set the working directory to the user.dir system property
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

            // Wait for the process to finish
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new PerlCompilerException("Error: " + e.getMessage());
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (errorReader != null) {
                    errorReader.close();
                }
            } catch (IOException e) {
                throw new PerlCompilerException("Error closing stream: " + e.getMessage());
            }
            if (process != null) {
                process.destroy();
            }
        }

        String out = output.toString();
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            List<RuntimeBaseEntity> result = list.elements;
            int index = 0;
            String separator = getGlobalVariable("main::/").toString();
            int separatorLength = separator.length();

            if (separatorLength == 0) {
                result.add(new RuntimeScalar(out));
            } else {
                while (index < out.length()) {
                    int nextIndex = out.indexOf(separator, index);
                    if (nextIndex == -1) {
                        // Add the remaining part of the string
                        result.add(new RuntimeScalar(out.substring(index)));
                        break;
                    }
                    // Add the part including the separator
                    result.add(new RuntimeScalar(out.substring(index, nextIndex + separatorLength)));
                    index = nextIndex + separatorLength;
                }
            }
            return list;
        } else {
            return new RuntimeScalar(out);
        }
    }
}
