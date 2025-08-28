package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.util.Map;
import java.util.Properties;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Java::System - Perl module for accessing IO::Handle internals
 */
public class IOHandle extends PerlModuleBase {

    public IOHandle() {
        super("IO::Handle", false);
    }

    public static void initialize() {
        IOHandle ioHandle = new IOHandle();

        try {
            // Register all methods
            ioHandle.registerMethod("ungetc", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IOHandle method: " + e.getMessage());
        }
    }

    /**
     * Call internal ungetc
     */
    public static RuntimeList ungetc(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("ungetc requires 2 arguments");
        }
        RuntimeIO fh = args.get(0).getRuntimeIO();
        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("can't ungetc on tied handle");
        }
        int c = args.get(1).toString().codePointAt(0);
        fh.ioHandle.ungetc(c);
        return scalarTrue.getList();
    }
}
