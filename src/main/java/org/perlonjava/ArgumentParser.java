package org.perlonjava;

import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The ArgumentParser class is responsible for parsing command-line arguments
 * and configuring the CompilerOptions accordingly. It handles various flags
 * and options that determine the behavior of the compiler, such as enabling
 * debug mode, specifying code to execute, or setting file processing modes.
 */
public class ArgumentParser {

    /**
     * Parses the command-line arguments and returns a CompilerOptions object
     * configured based on the provided arguments.
     *
     * @param args The command-line arguments to parse.
     * @return A CompilerOptions object with settings derived from the arguments.
     */
    public static CompilerOptions parseArguments(String[] args) {
        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.code = null; // Initialize code to null

        processArgs(args, parsedArgs);

        // Modify the code based on specific flags like -p or -n
        modifyCodeBasedOnFlags(parsedArgs);
        return parsedArgs;
    }

    private static void processArgs(String[] args, CompilerOptions parsedArgs) {
        boolean readingArgv = false; // Flag to indicate if we are reading non-switch arguments

        // Iterate over each argument
        for (int i = 0; i < args.length; i++) {
            if (readingArgv || !args[i].startsWith("-")) {
                // Process non-switch arguments (e.g., file names or positional arguments)
                processNonSwitchArgument(args, parsedArgs, i);
                readingArgv = true; // Once a non-switch argument is encountered, treat all subsequent arguments as such
            } else {
                String arg = args[i];

                if (arg.equals("--")) {
                    // "--" indicates the end of switch arguments; subsequent arguments are treated as non-switch
                    readingArgv = true;
                    continue;
                }

                if (arg.startsWith("-") && !arg.startsWith("--")) {
                    // Process clustered single-character switches (e.g., -e, -i)
                    i = processClusteredSwitches(args, parsedArgs, arg, i);
                } else {
                    // Process long-form switches (e.g., --debug, --tokenize)
                    i = processLongSwitches(args, parsedArgs, arg, i);
                }
            }
        }
    }

    /**
     * Processes non-switch arguments, typically file names or positional arguments.
     * If the code has not been set, it attempts to read the file content.
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index The current index in the arguments array.
     */
    private static void processNonSwitchArgument(String[] args, CompilerOptions parsedArgs, int index) {
        if (parsedArgs.code == null) {
            // If no code has been set, treat the argument as a file name and read its content
            parsedArgs.fileName = args[index];
            try {
                String fileContent = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
                parsedArgs.code = fileContent;
                processShebangLine(args, parsedArgs, fileContent, index);
            } catch (IOException e) {
                System.err.println("Error: Unable to read file " + parsedArgs.fileName);
                System.exit(1);
            }
        } else {
            // If code is already set, treat the argument as a runtime argument
            parsedArgs.argumentList.push(new RuntimeScalar(args[index]));
        }
    }

    /**
     * Processes the shebang line if present in the file content.
     * This can modify the arguments based on the shebang line content.
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param fileContent The content of the file being processed.
     * @param index The current index in the arguments array.
     */
    private static void processShebangLine(String[] args, CompilerOptions parsedArgs, String fileContent, int index) {
        String[] lines = fileContent.split("\n", 2);
        if (lines.length > 0 && lines[0].startsWith("#!")) {
            // Extract the shebang line and process it
            String shebangLine = lines[0].substring(2).trim();
            int perlIndex = shebangLine.indexOf("perl");
            if (perlIndex != -1) {
                String relevantPart = shebangLine.substring(perlIndex + 4).trim();
                String[] shebangArgs = relevantPart.split("\\s+");
                processArgs(shebangArgs, parsedArgs);
            }
        }
    }

    /**
     * Combines the original arguments with those extracted from the shebang line.
     *
     * @param args The original command-line arguments.
     * @param shebangArgs The arguments extracted from the shebang line.
     * @param index The current index in the arguments array.
     * @return A new array of arguments combining the original and shebang arguments.
     */
    private static String[] combineArgsWithShebang(String[] args, String[] shebangArgs, int index) {
        String[] newArgs = new String[index + 1 + shebangArgs.length + (args.length - index - 1)];
        System.arraycopy(args, 0, newArgs, 0, index + 1);
        System.arraycopy(shebangArgs, 0, newArgs, index + 1, shebangArgs.length);
        System.arraycopy(args, index + 1, newArgs, index + 1 + shebangArgs.length, args.length - index - 1);
        return newArgs;
    }

    /**
     * Processes clustered single-character switches (e.g., -e, -i).
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param arg The current argument being processed.
     * @param index The current index in the arguments array.
     * @return The updated index after processing the switches.
     */
    private static int processClusteredSwitches(String[] args, CompilerOptions parsedArgs, String arg, int index) {
        for (int j = 1; j < arg.length(); j++) {
            char switchChar = arg.charAt(j);

            switch (switchChar) {
                case 'e':
                    // Handle inline code specified with -e
                    index = handleInlineCode(args, parsedArgs, index, j, arg);
                    break;
                case 'i':
                    // Handle in-place editing specified with -i
                    index = handleInPlaceEditing(args, parsedArgs, index, j, arg);
                    // Skip the rest of the characters in the current argument as they are part of the extension
                    return index;
                case 'p':
                    // Enable process and print mode
                    parsedArgs.processAndPrint = true;
                    break;
                case 'n':
                    // Enable process only mode
                    parsedArgs.processOnly = true;
                    break;
                case 'c':
                    // Enable compile-only mode
                    validateExclusiveOptions(parsedArgs, "compile");
                    parsedArgs.compileOnly = true;
                    break;
                case 'h':
                case '?':
                    // Print help message and exit
                    printHelp();
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unrecognized switch: -" + switchChar + "  (-h will show valid options)");
                    System.exit(0);
                    break;
            }
        }
        return index;
    }

    /**
     * Handles the inline code specified with the -e switch.
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index The current index in the arguments array.
     * @param j The current position in the clustered switch string.
     * @param arg The current argument being processed.
     * @return The updated index after processing the inline code.
     */
    private static int handleInlineCode(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        if (j == arg.length() - 1 && index + 1 < args.length) {
            // If -e is the last character in the switch and there's a subsequent argument, treat it as code
            String newCode = args[++index];
            if (parsedArgs.code == null) {
                parsedArgs.code = newCode;
            } else {
                parsedArgs.code += "\n" + newCode;
            }
            parsedArgs.fileName = "-e"; // Indicate that the code was provided inline
        } else {
            System.err.println("No code specified for -e.");
            System.exit(1);
        }
        return index;
    }

    /**
     * Handles in-place editing specified with the -i switch.
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index The current index in the arguments array.
     * @param j The current position in the clustered switch string.
     * @param arg The current argument being processed.
     * @return The updated index after processing the in-place editing switch.
     */
    private static int handleInPlaceEditing(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        parsedArgs.inPlaceEdit = true; // Enable in-place editing

        if (j < arg.length() - 1) {
            // If there's an extension specified immediately after -i, use it
            parsedArgs.inPlaceExtension = arg.substring(j + 1);
            return index; // Return the current index as we've processed the extension
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            // If the next argument is not a switch, treat it as the extension
            parsedArgs.inPlaceExtension = args[++index];
        } else {
            // No extension specified
            parsedArgs.inPlaceExtension = null;
        }

        return index;
    }

    /**
     * Processes long-form switches (e.g., --debug, --tokenize).
     *
     * @param args The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param arg The current argument being processed.
     * @param index The current index in the arguments array.
     * @return The updated index after processing the long-form switch.
     */
    private static int processLongSwitches(String[] args, CompilerOptions parsedArgs, String arg, int index) {
        switch (arg) {
            case "--debug":
                // Enable debugging mode
                parsedArgs.debugEnabled = true;
                break;
            case "--tokenize":
                // Enable tokenize-only mode
                validateExclusiveOptions(parsedArgs, "tokenize");
                parsedArgs.tokenizeOnly = true;
                break;
            case "--parse":
                // Enable parse-only mode
                validateExclusiveOptions(parsedArgs, "parse");
                parsedArgs.parseOnly = true;
                break;
            case "--disassemble":
                // Enable disassemble mode
                validateExclusiveOptions(parsedArgs, "disassemble");
                parsedArgs.disassembleEnabled = true;
                break;
            case "--help":
                // Print help message and exit
                printHelp();
                System.exit(0);
                break;
            default:
                System.err.println("Unrecognized switch: " + arg + "  (-h will show valid options)");
                System.exit(0);
                break;
        }
        return index;
    }

    /**
     * Validates that exclusive options are not combined.
     *
     * @param parsedArgs The CompilerOptions object to check.
     * @param option The option being validated.
     */
    private static void validateExclusiveOptions(CompilerOptions parsedArgs, String option) {
        if (parsedArgs.tokenizeOnly || parsedArgs.parseOnly || parsedArgs.compileOnly) {
            System.err.println("Error: --" + option + " cannot be combined with other exclusive options");
            System.exit(1);
        }
    }

    /**
     * Modifies the code based on specific flags like -p or -n.
     *
     * @param parsedArgs The CompilerOptions object to modify.
     */
    private static void modifyCodeBasedOnFlags(CompilerOptions parsedArgs) {
        if (parsedArgs.processAndPrint) {
            // Wrap the code in a loop that processes and prints each line
            parsedArgs.code = "while (<>) { " + parsedArgs.code + " } continue { print or die \"-p destination: $!\\n\"; }";
        } else if (parsedArgs.processOnly) {
            // Wrap the code in a loop that processes each line without printing
            parsedArgs.code = "while (<>) { " + parsedArgs.code + " }";
        }
    }

    /**
     * Prints the help message detailing the usage of the program and its options.
     */
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
     * - processOnly: If true, the compiler will process input files without printing lines.
     * - processAndPrint: If true, the compiler will process input files and print each line.
     * - inPlaceEdit: Indicates if in-place editing is enabled.
     * - code: The source code to be compiled.
     * - fileName: The name of the file containing the source code, if any.
     * - inPlaceExtension: The extension used for in-place editing backups.
     * - argumentList: A list of arguments to be passed to the program.
     * - inc: A list of include directories for the compiler.
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
