package org.perlonjava;

import org.perlonjava.runtime.ErrorMessageUtil;
import org.perlonjava.scriptengine.PerlLanguageProvider;

/**
 * The Main class serves as the entry point for the Perl-to-Java bytecode compiler and runtime
 * evaluator. It accepts the command-line arguments, parses Perl code, generates corresponding Java bytecode using ASM, and executes the
 * generated bytecode.
 */
public class Main {

    /**
     * The main method initializes the compilation and execution process.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        ArgumentParser.CompilerOptions parsedArgs = ArgumentParser.parseArguments(args);

        if (parsedArgs.code == null) {
            System.err.println("No code provided. Use -e <code> or specify a filename.");
            System.exit(1);
        }

        try {
            PerlLanguageProvider.executePerlCode(parsedArgs, true);

            if (parsedArgs.compileOnly) {
                System.out.println(parsedArgs.fileName + " syntax OK");
            }
        } catch (Throwable t) {
            if (parsedArgs.debugEnabled) {
                // Print full JVM stack
                t.printStackTrace();
                System.out.println();
            }

            String errorMessage = ErrorMessageUtil.stringifyException(t);
            System.out.println(errorMessage);

            System.exit(1);
        }
    }

}

