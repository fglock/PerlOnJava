package org.perlonjava;

import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ArgumentParser {
    public static CompilerOptions parseArguments(String[] args) {
        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.code = null;
        boolean readingArgv = false;

        for (int i = 0; i < args.length; i++) {
            if (readingArgv) {
                parsedArgs.argumentList.push(new RuntimeScalar(args[i]));
            } else {
                switch (args[i]) {
                    case "-e":
                        if (i + 1 < args.length) {
                            parsedArgs.code = args[i + 1];
                            parsedArgs.fileName = "-e";
                            i++;
                            readingArgv = true;
                        } else {
                            System.err.println("Error: -e requires an argument");
                            System.exit(1);
                        }
                        break;
                    case "--debug":
                        parsedArgs.debugEnabled = true;
                        break;
                    case "--tokenize":
                        if (parsedArgs.parseOnly || parsedArgs.compileOnly) {
                            System.err.println("Error: --tokenize cannot be combined with --parse or -c");
                            System.exit(1);
                        }
                        parsedArgs.tokenizeOnly = true;
                        break;
                    case "--parse":
                        if (parsedArgs.tokenizeOnly || parsedArgs.compileOnly) {
                            System.err.println("Error: --parse cannot be combined with --tokenize or -c");
                            System.exit(1);
                        }
                        parsedArgs.parseOnly = true;
                        break;
                    case "--disassemble":
                        if (parsedArgs.tokenizeOnly || parsedArgs.parseOnly) {
                            System.err.println("Error: --disassemble cannot be combined with --tokenize or --parse");
                            System.exit(1);
                        }
                        parsedArgs.disassembleEnabled = true;
                        break;
                    case "-c":
                        if (parsedArgs.tokenizeOnly || parsedArgs.parseOnly) {
                            System.err.println("Error: -c cannot be combined with --tokenize or --parse");
                            System.exit(1);
                        }
                        parsedArgs.compileOnly = true;
                        break;
                    case "-n":
                        parsedArgs.processOnly = true;
                        break;
                    case "-p":
                        parsedArgs.processAndPrint = true;
                        break;
                    case "-i":
                        // Handle the -i switch for in-place editing
                        parsedArgs.inPlaceEdit = true; // Set inPlaceEdit to true
                        if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                            parsedArgs.inPlaceExtension = args[i + 1];
                            i++;
                        } else {
                            parsedArgs.inPlaceExtension = ".bak"; // Default extension
                        }
                        break;
                    case "-h":
                    case "-?":
                    case "--help":
                        printHelp();
                        System.exit(0);
                        break;
                    default:
                        if (args[i].startsWith("-I")) {
                            String path = args[i].substring("-I".length());
                            if (!path.isEmpty()) {
                                parsedArgs.inc.push(new RuntimeScalar(path));
                            }
                        } else {
                            parsedArgs.fileName = args[i];
                            try {
                                parsedArgs.code = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
                                readingArgv = true;
                            } catch (IOException e) {
                                System.err.println("Error: Unable to read file " + parsedArgs.fileName);
                                System.exit(1);
                            }
                        }
                        break;
                }
            }
        }

        // Wrap the Perl code with the loop structure for -n and -p
        if (parsedArgs.processAndPrint) {
            parsedArgs.code = "while (<>) { " + parsedArgs.code + " } continue { print or die \"-p destination: $!\\n\"; }";
        } else if (parsedArgs.processOnly) {
            parsedArgs.code = "while (<>) { " + parsedArgs.code + " }";
        }

        return parsedArgs;
    }

    private static void printHelp() {
        System.out.println("Usage: java -cp <classpath> org.perlonjava.Main [options] [file] [args]");
        System.out.println("Options:");
        System.out.println("  -e <code>       Specifies the code to be processed.");
        System.out.println("  --debug         Enables debugging mode.");
        System.out.println("  --tokenize      Tokenizes the input code.");
        System.out.println("  --parse         Parses the input code.");
        System.out.println("  --disassemble   Disassemble the generated code.");
        System.out.println("  -c              Compiles the input code only.");
        System.out.println("  -n              Process input files without printing lines.");
        System.out.println("  -p              Process input files and print each line.");
        System.out.println("  -i[extension]   Edit files in-place (makes backup if extension supplied).");
        System.out.println("  -Idirectory     Specify @INC/#include directory (several -I's allowed)");
        System.out.println("  -h, --help      Displays this help message.");
    }

    /**
     * CompilerOptions is a configuration class that holds various settings and flags
     * used by the compiler during the compilation process. These settings determine
     * how the compiler behaves, including whether to enable debugging, disassembly,
     * or whether to stop the process at specific stages like tokenization, parsing,
     * or compiling. It also stores the source code and the filename, if provided.
     * <p>
     * Fields:
     * - debugEnabled: Enables debug mode, providing detailed logging during compilation.
     * - disassembleEnabled: If true, the compiler will disassemble the generated bytecode.
     * - tokenizeOnly: If true, the compiler will only tokenize the input and stop.
     * - parseOnly: If true, the compiler will only parse the input and stop.
     * - compileOnly: If true, the compiler will compile the input but won't execute it.
     * - code: The source code to be compiled.
     * - fileName: The name of the file containing the source code, if any.
     * - inPlaceExtension: The extension used for in-place editing backups.
     * - inPlaceEdit: Indicates if in-place editing is enabled.
     */
    public static class CompilerOptions implements Cloneable {
        public boolean debugEnabled = false;
        public boolean disassembleEnabled = false;
        public boolean tokenizeOnly = false;
        public boolean parseOnly = false;
        public boolean compileOnly = false;
        public boolean processOnly = false; // For -n
        public boolean processAndPrint = false; // For -p
        public boolean inPlaceEdit = false; // New field for in-place editing
        public String code = null;
        public String fileName = null;
        public String inPlaceExtension = null; // For -i

        // Initialize @ARGV
        public RuntimeArray argumentList = GlobalContext.getGlobalArray("main::ARGV");

        public RuntimeArray inc = new RuntimeArray();

        @Override
        public CompilerOptions clone() {
            try {
                // Use super.clone() to create a shallow copy
                return (CompilerOptions) super.clone();
            } catch (CloneNotSupportedException e) {
                // This shouldn't happen, since we're implementing Cloneable
                throw new AssertionError();
            }
        }

        @Override
        public String toString() {
            return "CompilerOptions{\n" +
                    "    debugEnabled=" + debugEnabled + ",\n" +
                    "    disassembleEnabled=" + disassembleEnabled + ",\n" +
                    "    tokenizeOnly=" + tokenizeOnly + ",\n" +
                    "    parseOnly=" + parseOnly + ",\n" +
                    "    compileOnly=" + compileOnly + ",\n" +
                    "    code='" + (code != null ? code : "null") + "',\n" +
                    "    fileName='" + (fileName != null ? fileName : "null") + "',\n" +
                    "    inPlaceExtension='" + (inPlaceExtension != null ? inPlaceExtension : "null") + "',\n" +
                    "    inPlaceEdit=" + inPlaceEdit + ",\n" +
                    "    argumentList=" + argumentList + ",\n" +
                    "    inc=" + inc + "\n" +
                    "}";
        }
    }
}
