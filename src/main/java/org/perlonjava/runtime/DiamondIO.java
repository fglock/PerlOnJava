package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class DiamondIO {

    static RuntimeIO currentReader;
    static boolean eofReached = false;

    static RuntimeScalar readline() {
        if (eofReached) {
            return scalarUndef;
        }

        while (currentReader == null || !currentReader.eof().getBoolean()) {
            if (!openNextFile()) {
                eofReached = true;
                return scalarUndef;
            }
        }

        RuntimeScalar line = currentReader.readline();
        if (line.type == RuntimeScalarType.UNDEF) {
            return readline();
        }
        return line;
    }

    private static boolean openNextFile() {
        if (currentReader != null) {
            currentReader.close();
            currentReader = null;
        }
        RuntimeScalar fileName = getGlobalArray("main::ARGV").shift();

        if (fileName.type == RuntimeScalarType.UNDEF) {
            return false;
        }

        // Set the current filename in the global $main::ARGV variable
        getGlobalVariable("main::ARGV").set(fileName);

        currentReader = RuntimeIO.open(fileName.toString());

        // Set the current handle in the global main::ARGV handle
        getGlobalIO("main::ARGV").set(currentReader);

        return currentReader != null;
    }
}