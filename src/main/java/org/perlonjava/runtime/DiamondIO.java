package org.perlonjava.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class DiamondIO extends RuntimeIO {

    private BufferedReader currentReader;
    private boolean eofReached = false;

    /**
     * Constructor for DiamondIO.
     */
    public DiamondIO() {
        super();
    }

    public RuntimeScalar readline() {
        if (eofReached) {
            return scalarUndef;
        }

        try {
            while (currentReader == null || isEOF()) {
                if (!openNextFile()) {
                    eofReached = true;
                    return scalarUndef;
                }
            }

            String line = currentReader.readLine();
            if (line == null) {
                return readline();
            }
            return new RuntimeScalar(line);
        } catch (IOException e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    private boolean openNextFile() {
        closeCurrentReader();
        RuntimeScalar fileName = getGlobalArray("main::ARGV").shift();

        if (fileName.type == RuntimeScalarType.UNDEF) {
            return false;
        }

        // Set the current filename in the global $main::ARGV variable
        getGlobalVariable("main::ARGV").set(fileName);

        try {
            if ("-".equals(fileName.toString())) {
                currentReader = new BufferedReader(new InputStreamReader(System.in));
            } else {
                currentReader = Files.newBufferedReader(Paths.get(fileName.toString()));
            }
        } catch (IOException e) {
            getGlobalVariable("main::!").set("Failed to open file: " + fileName + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    private void closeCurrentReader() {
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (IOException e) {
                getGlobalVariable("main::!").set("Failed to close file: " + e.getMessage());
            }
            currentReader = null;
        }
    }

    private boolean isEOF() {
        try {
            return currentReader != null && !currentReader.ready();
        } catch (IOException e) {
            return true;
        }
    }
}