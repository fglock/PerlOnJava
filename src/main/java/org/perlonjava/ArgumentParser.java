package org.perlonjava;

import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.ScalarUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Processes the command-line arguments, distinguishing between switch and non-switch arguments.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     */
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
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
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
     * @param args        The command-line arguments.
     * @param parsedArgs  The CompilerOptions object to configure.
     * @param fileContent The content of the file being processed.
     * @param index       The current index in the arguments array.
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
     * Processes clustered single-character switches (e.g., -e, -i).
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param arg        The current argument being processed.
     * @param index      The current index in the arguments array.
     * @return The updated index after processing the switches.
     */
    private static int processClusteredSwitches(String[] args, CompilerOptions parsedArgs, String arg, int index) {
        for (int j = 1; j < arg.length(); j++) {
            char switchChar = arg.charAt(j);

            switch (switchChar) {
                case 'm':
                case 'M':
                    index = handleModuleSwitch(args, parsedArgs, index, j, arg, switchChar);
                    return index;
                case 'a':
                    // Enable autosplit mode
                    parsedArgs.autoSplit = true;
                    parsedArgs.processOnly = true; // -a implicitly sets -n
                    break;
                case 'F':
                    // Handle the split pattern for -a
                    index = handleSplitPattern(args, parsedArgs, index, j, arg);
                    parsedArgs.autoSplit = true; // -F implicitly sets -a
                    parsedArgs.processOnly = true; // -F implicitly sets -n
                    return index;
                case '0':
                    // Handle input record separator specified with -0
                    index = handleInputRecordSeparator(args, parsedArgs, index, j, arg);
                    break;
                case 'g':
                    parsedArgs.inputRecordSeparator = null;
                    break;
                case 'l':
                    // Handle automatic line-ending processing
                    index = handleLineEndingProcessing(args, parsedArgs, index, j, arg);
                    break;
                case 'e':
                    // Handle inline code specified with -e
                    index = handleInlineCode(args, parsedArgs, index, j, arg);
                    break;
                case 'E':
                    // Handle inline code specified with -E
                    parsedArgs.useVersion = true;
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
                case 'I':
                    // Handle include directory specified with -I
                    index = handleIncludeDirectory(args, parsedArgs, index, j, arg);
                    return index;
                case 'x':
                    parsedArgs.discardLeadingGarbage = true;
                    index = handleEmbeddedProgram(args, parsedArgs, index, j, arg);
                    return index;
                default:
                    System.err.println("Unrecognized switch: -" + switchChar + "  (-h will show valid options)");
                    System.exit(0);
                    break;
            }
        }
        return index;
    }

    // Handle -x
    private static int handleEmbeddedProgram(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        String directory = null;
        if (j < arg.length() - 1) {
            directory = arg.substring(j + 1);
        }

        if (directory != null) {
            try {
                // Change to the specified directory
                System.setProperty("user.dir", directory);
            } catch (SecurityException e) {
                System.err.println("Error: Unable to change directory to " + directory);
                System.exit(1);
            }
        }
        return index;
    }

    // Handle the -l switch
    private static int handleLineEndingProcessing(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        parsedArgs.lineEndingProcessing = true; // Mark that -l is used
        String octnum = arg.substring(j + 1);

        // Check if the octnum is empty or if the next argument is another switch
        if (octnum.isEmpty()) {
            // If the current argument doesn't provide an octal number, check the next argument
            if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
                octnum = args[++index];
            }
        }

        if (octnum.isEmpty() || !octnum.matches("[0-7]*")) {
            // No valid octal number provided, use the input record separator
            parsedArgs.outputRecordSeparator = parsedArgs.inputRecordSeparator;
        } else {
            try {
                int separatorInt = Integer.parseInt(octnum, 8);
                parsedArgs.outputRecordSeparator = Character.toString((char) separatorInt);
            } catch (NumberFormatException e) {
                System.err.println("Invalid output record separator: " + octnum);
                System.exit(1);
            }
        }
        return index;
    }


    // handle the -F split pattern
    private static int handleSplitPattern(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        if (j < arg.length() - 1) {
            // If there's a pattern specified immediately after -F, use it
            parsedArgs.splitPattern = extractPattern(arg.substring(j + 1));
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            // If the next argument is not a switch, treat it as the pattern
            parsedArgs.splitPattern = extractPattern(args[++index]);
        } else {
            System.err.println("No pattern specified for -F.");
            System.exit(1);
        }
        return index;
    }

    // helper method to extract the -F pattern
    private static String extractPattern(String pattern) {
        if ((pattern.startsWith("/") && pattern.endsWith("/"))
                || (pattern.startsWith("\"") && pattern.endsWith("\""))
                || (pattern.startsWith("'") && pattern.endsWith("'"))) {
            return pattern;
        } else {
            return "'" + pattern + "'";
        }
    }

    /**
     * Handles the module switch specified with the -m or -M options.
     * This method processes the module name and any associated arguments,
     * and adds a new ModuleUseStatement to the parsed arguments.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @param switchChar The character representing the switch ('m' or 'M').
     * @return The updated index after processing the module switch.
     */
    private static int handleModuleSwitch(String[] args, CompilerOptions parsedArgs, int index, int j, String arg, char switchChar) {
        String moduleName = null;
        String moduleArgs = null;
        boolean useNo = false;

        if (j < arg.length() - 1) {
            moduleName = arg.substring(j + 1);
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            moduleName = args[++index];
        } else {
            System.err.println("No module specified for -" + switchChar + ".");
            System.exit(1);
        }

        // Check if the first character of the module name is a dash
        if (moduleName.startsWith("-")) {
            useNo = true;
            moduleName = moduleName.substring(1); // Remove the dash
        }

        // Check for '=' to handle arguments
        int equalsIndex = moduleName.indexOf('=');
        if (equalsIndex != -1) {
            moduleArgs = moduleName.substring(equalsIndex + 1);
            moduleName = moduleName.substring(0, equalsIndex);
        }

        parsedArgs.moduleUseStatements.add(new ModuleUseStatement(switchChar, moduleName, moduleArgs, useNo));
        return index;
    }

    /**
     * Handles include directory specified with the -I switch.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the include directory.
     */
    private static int handleIncludeDirectory(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        if (j < arg.length() - 1) {
            // If there's a directory specified immediately after -I, use it
            String path = arg.substring(j + 1);
            parsedArgs.inc.push(new RuntimeScalar(path));
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            // If the next argument is not a switch, treat it as the directory
            parsedArgs.inc.push(new RuntimeScalar(args[++index]));
        } else {
            System.err.println("No directory specified for -I.");
            System.exit(1);
        }
        return index;
    }

    /**
     * Handles the input record separator specified with the -0 switch.
     * This switch allows specifying a custom input record separator for processing files.
     * <p>
     * The -0 switch can be followed by an optional octal or hexadecimal value that specifies
     * the character to be used as the input record separator. If no value is provided, the
     * null character is used by default. Special cases include:
     * - 0000: Paragraph mode, where the separator is set to two newlines.
     * - Values >= 0400: Slurp mode, where the entire file is read as a single record.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the input record separator.
     */
    private static int handleInputRecordSeparator(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        String separatorValue = arg.substring(j + 1);
        if (separatorValue.isEmpty() && index + 1 < args.length && !args[index + 1].startsWith("-")) {
            separatorValue = args[++index];
        }

        if (separatorValue.isEmpty()) {
            parsedArgs.inputRecordSeparator = "\0"; // Null character
        } else {
            try {
                int separatorInt;
                if (separatorValue.startsWith("0x") || separatorValue.startsWith("0X")) {
                    separatorInt = Integer.parseInt(separatorValue.substring(2), 16);
                } else {
                    separatorInt = Integer.parseInt(separatorValue, 8);
                }

                if (separatorInt == 0) {
                    parsedArgs.inputRecordSeparator = "\n\n"; // Paragraph mode
                } else if (separatorInt >= 0400) {
                    parsedArgs.inputRecordSeparator = null; // Slurp whole file
                } else {
                    parsedArgs.inputRecordSeparator = Character.toString((char) separatorInt);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid input record separator: " + separatorValue);
                System.exit(1);
            }
        }
        return index;
    }

    /**
     * Handles the inline code specified with the -e switch.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
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
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
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
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param arg        The current argument being processed.
     * @param index      The current index in the arguments array.
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
     * @param option     The option being validated.
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

        if (parsedArgs.discardLeadingGarbage) {
            // '-x' extract Perl code after discarding leading garbage
            String fileContent = parsedArgs.code;
            String[] lines = fileContent.split("\n");
            boolean perlCodeStarted = false;
            StringBuilder perlCode = new StringBuilder();

            for (String line : lines) {
                if (perlCodeStarted) {
                    perlCode.append(line).append("\n");
                } else if (line.trim().equals("#!perl")) {
                    perlCodeStarted = true;
                }
            }

            if (!perlCodeStarted) {
                System.err.println("Error: No Perl code found after discarding leading garbage.");
                System.exit(1);
            }
            parsedArgs.code = perlCode.toString();
        }

        String versionString = "v5.36.0";
        String autoSplit = "";
        if (parsedArgs.autoSplit) {
            autoSplit = " our @F = split(" + parsedArgs.splitPattern + "); ";
        }
        String chompCode = parsedArgs.lineEndingProcessing ? "chomp; " : "";
        if (parsedArgs.processAndPrint) {
            // Wrap the code in a loop that processes and prints each line
            parsedArgs.code = "while (<>) { " + chompCode + autoSplit + parsedArgs.code + " } continue { print or die \"-p destination: $!\\n\"; }";
        } else if (parsedArgs.processOnly) {
            // Wrap the code in a loop that processes each line without printing
            parsedArgs.code = "while (<>) { " + chompCode + autoSplit + parsedArgs.code + " }";
        }

        StringBuilder useStatements = new StringBuilder();
        if (parsedArgs.useVersion) {
            useStatements.append("use ").append(versionString).append(";\n");
        }
        for (ModuleUseStatement moduleStatement : parsedArgs.moduleUseStatements) {
            useStatements.append(moduleStatement.toString()).append("\n");
        }
        // Prepend the use statements to the code
        if (!useStatements.isEmpty()) {
            parsedArgs.code = useStatements + parsedArgs.code;
        }
    }

    /**
     * Prints the help message detailing the usage of the program and its options.
     */
    private static void printHelp() {
        System.out.println("Usage: java -jar target/perlonjava-1.0-SNAPSHOT.jar [options] [file] [args]");
        System.out.println();
        System.out.println("  -0[octal/hexadecimal] specify record separator (\\0, if no argument)");
        System.out.println("  -a                    autosplit mode with -n or -p (splits $_ into @F)");
        System.out.println("  -c                    check syntax only (runs BEGIN and CHECK blocks)");
        System.out.println("  -e commandline        one line of program (several -e's allowed, omit programfile)");
        System.out.println("  -E commandline        like -e, but enables all optional features");
        System.out.println("  -F/pattern/           split() pattern for -a switch (//'s are optional)");
        System.out.println("  -g                    read all input in one go (slurp), rather than line-by-line");
        System.out.println("  -i[extension]         edit <> files in place (makes backup if extension supplied)");
        System.out.println("  -Idirectory           specify @INC/#include directory (several -I's allowed)");
        System.out.println("  -l[octnum]            enable line ending processing, specifies line terminator");
        System.out.println("  -[mM][-]module        execute \"use/no module...\" before executing program");
        System.out.println("  -n                    assume \"while (<>) { ... }\" loop around program");
        System.out.println("  -p                    assume loop like -n but print line also, like sed");
        System.out.println("  -x[directory]         ignore text before #!perl line (optionally cd to directory)");
        System.out.println("  --debug               enable debugging mode");
        System.out.println("  --tokenize            tokenize the input code");
        System.out.println("  --parse               parse the input code");
        System.out.println("  --disassemble         disassemble the generated code");
        System.out.println("  -h, --help            displays this help message");
        System.out.println();
        System.out.println("Run 'perldoc perl' for more help with Perl.");
    }

    // Define a structured data type for module use statements
    private static class ModuleUseStatement {
        char type; // 'm' or 'M'
        String moduleName;
        String args;
        boolean useNo; // New field to indicate 'use' or 'no'

        ModuleUseStatement(char type, String moduleName, String args, boolean useNo) {
            this.type = type;
            this.moduleName = moduleName;
            this.args = args;
            this.useNo = useNo;
        }

        @Override
        public String toString() {
            String useOrNo = useNo ? "no" : "use";
            if (args != null) {
                // Split the arguments by comma and wrap each in quotes
                String[] splitArgs = args.split(",");
                StringBuilder formattedArgs = new StringBuilder();
                for (int i = 0; i < splitArgs.length; i++) {
                    formattedArgs.append("\"").append(splitArgs[i].trim()).append("\"");
                    if (i < splitArgs.length - 1) {
                        formattedArgs.append(", ");
                    }
                }
                return useOrNo + " " + moduleName + " (" + formattedArgs + ");";
            }
            return useOrNo + " " + moduleName + (type == 'm' ? " ();" : ";");
        }
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
        public String inputRecordSeparator = "\n";
        public String outputRecordSeparator = null;
        public boolean autoSplit = false; // For -a
        public boolean useVersion = false; // For -E
        // Initialize @ARGV
        public RuntimeArray argumentList = GlobalContext.getGlobalArray("main::ARGV");
        public RuntimeArray inc = new RuntimeArray();
        public String splitPattern = "' '"; // Default split pattern for -a
        public boolean lineEndingProcessing = false; // For -l
        public boolean discardLeadingGarbage = false; // For -x
        List<ModuleUseStatement> moduleUseStatements = new ArrayList<>(); // For -m -M

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
                    "    processOnly=" + processOnly + ",\n" +
                    "    processAndPrint=" + processAndPrint + ",\n" +
                    "    inPlaceEdit=" + inPlaceEdit + ",\n" +
                    "    code='" + (code != null ? code : "null") + "',\n" +
                    "    fileName='" + ScalarUtils.printable(fileName) + "',\n" +
                    "    inPlaceExtension='" + ScalarUtils.printable(inPlaceExtension) + "',\n" +
                    "    inputRecordSeparator=" + ScalarUtils.printable(inputRecordSeparator) + ",\n" +
                    "    outputRecordSeparator=" + ScalarUtils.printable(outputRecordSeparator) + ",\n" +
                    "    autoSplit=" + autoSplit + ",\n" +
                    "    useVersion=" + useVersion + ",\n" +
                    "    lineEndingProcessing=" + lineEndingProcessing + ",\n" +
                    "    discardLeadingGarbage=" + discardLeadingGarbage + ",\n" +
                    "    splitPattern=" + ScalarUtils.printable(splitPattern) + ",\n" +
                    "    argumentList=" + argumentList + ",\n" +
                    "    inc=" + inc + ",\n" +
                    "    moduleUseStatements=" + moduleUseStatements + "\n" +
                    "}";
        }
    }
}
