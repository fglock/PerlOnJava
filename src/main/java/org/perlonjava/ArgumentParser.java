package org.perlonjava;

import org.perlonjava.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.perlonjava.Configuration.getPerlVersionBundle;

/**
 * The ArgumentParser class is responsible for parsing command-line arguments
 * and configuring the CompilerOptions accordingly. It handles various flags
 * and options that determine the behavior of the compiler, such as enabling
 * debug mode, specifying code to execute, or setting file processing modes.
 */
public class ArgumentParser {

    /**
     * /**
     * Parses the command-line arguments and returns a CompilerOptions object
     * configured based on the provided arguments.
     *
     * @param args The command-line arguments to parse.
     * @return A CompilerOptions object with settings derived from the arguments.
     */
    public static CompilerOptions parseArguments(String[] args) {
        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.code = null; // Initialize code to null

        // Process PERL5OPT environment variable first
        processPerl5Opt(parsedArgs);

        processArgs(args, parsedArgs);

        // If no code was provided and no filename, try reading from stdin
        if (parsedArgs.code == null) {
            try {
                // Try to read from stdin - this will work for pipes, redirections, and interactive input
                StringBuilder stdinContent = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

                // Check if we're reading from a pipe/redirection vs interactive terminal
                boolean isInteractive = System.console() != null;

                if (isInteractive) {
                    // Interactive mode - prompt the user and read until EOF (Ctrl+D)
                    System.err.println("Enter Perl code (press Ctrl+D when done):");
                }

                // Read from stdin regardless of whether it's interactive or not
                String line;
                while ((line = reader.readLine()) != null) {
                    stdinContent.append(line).append("\n");
                }

                if (stdinContent.length() > 0) {
                    parsedArgs.code = stdinContent.toString();
                    parsedArgs.fileName = "-"; // Indicate that code came from stdin
                }
            } catch (IOException e) {
                // If we can't read from stdin, continue with normal error handling
            }
        }

        // Modify the code based on specific flags like -p or -n
        modifyCodeBasedOnFlags(parsedArgs);
        return parsedArgs;
    }

    /**
     * Processes the PERL5OPT environment variable to preset command-line options.
     * Supported options: -D, -I, -M, -T, -U, -W, -d, -m, -t, and -w.
     *
     * @param parsedArgs The CompilerOptions object to configure.
     */
    private static void processPerl5Opt(CompilerOptions parsedArgs) {
        String perl5opt = System.getenv("PERL5OPT");
        if (perl5opt == null || perl5opt.trim().isEmpty()) {
            return;
        }

        // Split the PERL5OPT string into individual arguments
        // Handle quoted arguments and respect shell-like parsing
        List<String> optionsList = parsePerl5OptString(perl5opt.trim());
        String[] options = optionsList.toArray(new String[0]);

        // Process the PERL5OPT arguments using existing argument processing logic
        processArgs(options, parsedArgs);
    }

    /**
     * Parses the PERL5OPT string into individual arguments, handling quotes and escapes.
     *
     * @param perl5opt The PERL5OPT environment variable value.
     * @return A list of individual arguments.
     */
    private static List<String> parsePerl5OptString(String perl5opt) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inQuotes = false;
        boolean inSingleQuotes = false;
        boolean escapeNext = false;

        for (int i = 0; i < perl5opt.length(); i++) {
            char c = perl5opt.charAt(i);

            if (escapeNext) {
                currentArg.append(c);
                escapeNext = false;
            } else if (c == '\\') {
                escapeNext = true;
            } else if (c == '"' && !inSingleQuotes) {
                inQuotes = !inQuotes;
            } else if (c == '\'' && !inQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes && !inSingleQuotes) {
                if (!currentArg.isEmpty()) {
                    String arg = currentArg.toString();
                    // Only process supported PERL5OPT options
                    if (isSupportedPerl5OptOption(arg)) {
                        args.add(arg);
                    }
                    currentArg.setLength(0);
                }
            } else {
                currentArg.append(c);
            }
        }

        // Add the last argument if there is one
        if (!currentArg.isEmpty()) {
            String arg = currentArg.toString();
            if (isSupportedPerl5OptOption(arg)) {
                args.add(arg);
            }
        }

        return args;
    }

    /**
     * Checks if an option is supported in PERL5OPT.
     * Supported options: -C, -D, -I, -M, -T, -U, -W, -d, -m, -t, and -w.
     *
     * @param arg The argument to check.
     * @return true if the option is supported in PERL5OPT, false otherwise.
     */
    private static boolean isSupportedPerl5OptOption(String arg) {
        if (!arg.startsWith("-")) {
            return false;
        }

        // Check for single character options that might be clustered
        if (arg.length() >= 2 && !arg.startsWith("--")) {
            char firstChar = arg.charAt(1);
            return firstChar == 'C' || firstChar == 'D' || firstChar == 'I' || firstChar == 'M' ||
                    firstChar == 'T' || firstChar == 'U' || firstChar == 'W' ||
                    firstChar == 'd' || firstChar == 'm' || firstChar == 't' ||
                    firstChar == 'w';
        }

        return false;
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
            // If no code has been set, treat the argument as a file name
            parsedArgs.fileName = args[index];
            try {
                String filePath = parsedArgs.fileName;
                if (parsedArgs.usePathEnv && !filePath.contains("/") && !filePath.contains("\\")) {
                    // Search in PATH when -S is used and filename has no path separators
                    String pathEnv = System.getenv("PATH");
                    if (pathEnv != null) {
                        for (String path : pathEnv.split(File.pathSeparator)) {
                            File file = new File(path, filePath);
                            if (file.exists() && file.canRead()) {
                                filePath = file.getAbsolutePath();
                                break;
                            }
                        }
                    }
                }
                String fileContent = FileUtils.readFileWithEncodingDetection(Paths.get(filePath), parsedArgs);
                parsedArgs.code = fileContent;
                processShebangLine(args, parsedArgs, fileContent, index);

                // After processing shebang and setting code, handle -s switches if enabled
                if (parsedArgs.rudimentarySwitchParsing) {
                    // Process remaining arguments as potential switches
                    for (int i = index + 1; i < args.length; i++) {
                        String arg = args[i];

                        // Stop processing on "--" or first non-switch argument
                        if (arg.equals("--") || !arg.startsWith("-")) {
                            // This is where we start adding to @ARGV
                            for (int j = i; j < args.length; j++) {
                                RuntimeArray.push(parsedArgs.argumentList, new RuntimeScalar(args[j]));
                            }
                            break;
                        }

                        // Process the rudimentary switch
                        processRudimentarySwitch(arg, parsedArgs);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error: Unable to read file " + parsedArgs.fileName);
                System.exit(1);
            }
        } else {
            // If code is already set, treat the argument as a runtime argument
            RuntimeArray.push(parsedArgs.argumentList, new RuntimeScalar(args[index]));
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
                // Filter out empty args from shebang processing
                String[] nonEmptyArgs = Arrays.stream(shebangArgs)
                        .filter(arg -> !arg.isEmpty())
                        .toArray(String[]::new);
                processArgs(nonEmptyArgs, parsedArgs);
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
                case 'C':
                    // Handle Unicode/character encoding switches
                    index = handleUnicodeSwitch(args, parsedArgs, index, j, arg);
                    return index;

                case 'm':
                case 'M':
                    index = handleModuleSwitch(args, parsedArgs, index, j, arg, switchChar);
                    return index;

                case 'w':
                    // enable many useful warnings
                    parsedArgs.moduleUseStatements.add(new ModuleUseStatement(switchChar, "warnings", null, false));
                    break;
                case 'W':
                    // enable all warnings
                    parsedArgs.moduleUseStatements.add(new ModuleUseStatement(switchChar, "warnings", "all", false));
                    break;
                case 'X':
                    // disable all warnings
                    parsedArgs.moduleUseStatements.add(new ModuleUseStatement(switchChar, "warnings", null, true));
                    break;
                case 'D':
                    // Enable debugging flags (currently a no-op for compatibility)
                    index = handleDebugFlags(args, parsedArgs, index, j, arg);
                    return index;
                case 'T':
                    // Enable taint mode
                    parsedArgs.taintMode = true;
                    break;
                case 'U':
                    // Allow unsafe operations
                    parsedArgs.allowUnsafeOperations = true;
                    break;
                case 'd':
                    // Run under debugger (currently a no-op for compatibility)
                    parsedArgs.runUnderDebugger = true;
                    break;
                case 't':
                    // Enable taint warnings
                    parsedArgs.taintWarnings = true;
                    break;
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
                case 'f':
                    // No-op: don't do $sitelib/sitecustomize.pl at startup
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
                case 'v':
                    printVersionInfo();
                    System.exit(0);
                    break;
                case 'V':
                    // Handle -V[:configvar] switch
                    String configVar = null;
                    if (j < arg.length() - 1 && arg.charAt(j + 1) == ':') {
                        configVar = arg.substring(j + 2);
                    }
                    printConfigurationInfo(configVar, parsedArgs);
                    System.exit(0);
                    break;
                case 'S':
                    // Enable PATH environment search for program
                    parsedArgs.usePathEnv = true;
                    break;
                case 's':
                    // Enable rudimentary switch parsing
                    parsedArgs.rudimentarySwitchParsing = true;
                    break;
                case 'x':
                    parsedArgs.discardLeadingGarbage = true;
                    index = handleEmbeddedProgram(args, parsedArgs, index, j, arg);
                    return index;
                default:
                    System.err.println("Unrecognized switch: -" + switchChar + "  (-h will show valid options)");
                    // System.exit(0);
                    break;
            }
        }
        return index;
    }

    /**
     * Handles Unicode/character encoding switches specified with the -C option.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the Unicode switch.
     */
    private static int handleUnicodeSwitch(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        String unicodeFlags = "";

        if (j < arg.length() - 1) {
            // Flags specified immediately after -C
            unicodeFlags = arg.substring(j + 1);
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            // Flags specified as next argument
            unicodeFlags = args[++index];
        }

        // If no flags specified, default to "D" (equivalent to SOLA)
        if (unicodeFlags.isEmpty()) {
            unicodeFlags = "D";
        }

        // Parse the Unicode flags
        parseUnicodeFlags(unicodeFlags, parsedArgs);

        return index;
    }

    /**
     * Parses Unicode flags for the -C switch.
     *
     * @param flags      The Unicode flags string.
     * @param parsedArgs The CompilerOptions object to configure.
     */
    private static void parseUnicodeFlags(String flags, CompilerOptions parsedArgs) {
        // Handle numeric flags (like -C0)
        if (flags.matches("\\d+")) {
            int numericFlag = Integer.parseInt(flags);
            if (numericFlag == 0) {
                // -C0: Disable all Unicode I/O features
                parsedArgs.unicodeStdin = false;
                parsedArgs.unicodeStdout = false;
                parsedArgs.unicodeStderr = false;
                parsedArgs.unicodeInput = false;
                parsedArgs.unicodeOutput = false;
                parsedArgs.unicodeArgs = false;
                parsedArgs.unicodeLocale = false;
            } else {
                // Other numeric values can be implemented as needed
                // For now, treat them as enabling all Unicode features
                parsedArgs.unicodeStdin = true;
                parsedArgs.unicodeStdout = true;
                parsedArgs.unicodeStderr = true;
                parsedArgs.unicodeInput = true;
                parsedArgs.unicodeOutput = true;
                parsedArgs.unicodeArgs = true;
                parsedArgs.unicodeLocale = true;
            }
            return;
        }

        // Handle letter flags
        for (char flag : flags.toCharArray()) {
            switch (flag) {
                case 'S':
                    // STDIN
                    parsedArgs.unicodeStdin = true;
                    break;
                case 'O':
                    // STDOUT
                    parsedArgs.unicodeStdout = true;
                    break;
                case 'E':
                    // STDERR
                    parsedArgs.unicodeStderr = true;
                    break;
                case 'I':
                    // Input (same as S)
                    parsedArgs.unicodeInput = true;
                    parsedArgs.unicodeStdin = true;
                    break;
                case 'A':
                    // All (STDIN, STDOUT, STDERR)
                    parsedArgs.unicodeStdin = true;
                    parsedArgs.unicodeStdout = true;
                    parsedArgs.unicodeStderr = true;
                    parsedArgs.unicodeInput = true;
                    parsedArgs.unicodeOutput = true;
                    parsedArgs.unicodeArgs = true;
                    break;
                case 'L':
                    // Locale
                    parsedArgs.unicodeLocale = true;
                    break;
                case 'D':
                    // Default (equivalent to SOLA)
                    parsedArgs.unicodeStdin = true;
                    parsedArgs.unicodeStdout = true;
                    parsedArgs.unicodeStderr = false; // Note: D doesn't include E
                    parsedArgs.unicodeInput = true;
                    parsedArgs.unicodeOutput = true;
                    parsedArgs.unicodeArgs = true;
                    parsedArgs.unicodeLocale = true;
                    break;
                default:
                    System.err.println("Unrecognized Unicode flag: " + flag);
                    break;
            }
        }
    }

    private static void printVersionInfo() {
        String version = Configuration.getPerlVersionNoV();
        System.out.println();
        System.out.println("This is perl 5, version " + version + " built for JVM");
        System.out.println();
        System.out.println("Copyright 1987-2025, Larry Wall");
        System.out.println();
        System.out.println("Perl may be copied only under the terms of either the Artistic License or the");
        System.out.println("GNU General Public License, which may be found in the Perl 5 source kit.");
        System.out.println();
        System.out.println("Complete documentation for Perl, including FAQ lists, should be found on");
        System.out.println("this system using \"man perl\" or \"perldoc perl\". If you have access to the");
        System.out.println("Internet, point your browser at https://www.perl.org/, the Perl Home Page");
        System.out.println();
    }

    /**
     * Prints configuration information. If a specific configVar is provided,
     * it prints only that configuration. Otherwise, it prints all configurations.
     * If the configVar is not found, it prints 'UNKNOWN'.
     *
     * @param configVar The specific configuration variable to display, or null to display all.
     */
    private static void printConfigurationInfo(String configVar, CompilerOptions parsedArgs) {
        GlobalContext.initializeGlobals(parsedArgs);

        if (configVar != null) {
            // Print specific configuration variable or 'UNKNOWN' if not found
            String value = System.getProperty(configVar, "UNKNOWN");
            System.out.println(configVar + "='" + value + "';");
        } else {
            // Print all configuration information
            String version = Configuration.getPerlVersionNoV();
            System.out.println("Summary of my perl5 (" + version + ") configuration:");
            System.out.println();

            System.out.println("  JVM properties:");
            System.getProperties().forEach((key, value) ->
                    System.out.println("    " + key + ": " + value));

            // Print environment variables
            System.out.println("  %ENV:");
            GlobalVariable.getGlobalHash("main::ENV").elements.forEach((key, value) ->
                    System.out.println("    " + key + "=\"" + value + "\""));

            // Print include paths
            System.out.println("  @INC:");
            GlobalVariable.getGlobalArray("main::INC").elements.forEach(path ->
                    System.out.println("    " + path));
        }
    }

    /**
     * Handles the -x switch for embedded programs, optionally changing the directory.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the embedded program.
     */
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

    /**
     * Handles automatic line-ending processing specified with the -l switch.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the line-ending switch.
     */
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

    /**
     * Handles the split pattern specified with the -F switch.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the split pattern.
     */
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

    /**
     * Extracts the pattern for the -F switch, ensuring it is properly formatted.
     *
     * @param pattern The pattern string to extract.
     * @return The formatted pattern string.
     */
    private static String extractPattern(String pattern) {
        if ((pattern.startsWith("/") && pattern.endsWith("/"))
                || (pattern.startsWith("\"") && pattern.endsWith("\""))
                || (pattern.startsWith("'") && pattern.endsWith("'"))) {
            return pattern;
        } else {
            // For -F switch, patterns should be treated as regex, so wrap in /pattern/
            return "/" + pattern + "/";
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
     * @return The updated index after processing the module switch
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
            RuntimeArray.push(parsedArgs.inc, new RuntimeScalar(path));
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            // If the next argument is not a switch, treat it as the directory
            RuntimeArray.push(parsedArgs.inc, new RuntimeScalar(args[++index]));
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
            case "--interpreter":
                // Use bytecode interpreter instead of JVM compiler
                parsedArgs.useInterpreter = true;
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
                System.err.println("No Perl script found in input");
                System.exit(1);
            }
            parsedArgs.code = perlCode.toString();
        }

        // Force line number to start at 1
        parsedArgs.code = "# line 1\n" + parsedArgs.code;

        String autoSplit = "";
        if (parsedArgs.autoSplit) {
            autoSplit = " our @F = split(" + parsedArgs.splitPattern + "); ";
        }
        String chompCode = parsedArgs.lineEndingProcessing ? "chomp; " : "";

        // Handle -n and -p switches by wrapping code in while loop
        if (parsedArgs.processAndPrint || parsedArgs.processOnly) {
            // Separate __DATA__ or __END__ section from executable code
            String executableCode = parsedArgs.code;
            String dataSection = "";

            // Find __DATA__ or __END__ marker
            int dataIndex = parsedArgs.code.indexOf("__DATA__");
            int endIndex = parsedArgs.code.indexOf("__END__");

            // Use whichever marker appears first, or -1 if neither exists
            int markerIndex = -1;
            if (dataIndex != -1 && endIndex != -1) {
                markerIndex = Math.min(dataIndex, endIndex);
            } else if (dataIndex != -1) {
                markerIndex = dataIndex;
            } else if (endIndex != -1) {
                markerIndex = endIndex;
            }

            if (markerIndex != -1) {
                // Split code at the marker
                executableCode = parsedArgs.code.substring(0, markerIndex).trim();
                dataSection = "\n" + parsedArgs.code.substring(markerIndex);
            }

            if (parsedArgs.processAndPrint) {
                // Wrap the executable code in a loop that processes and prints each line
                parsedArgs.code = "while (<>) { " + chompCode + autoSplit + executableCode + " } continue { print or die \"-p destination: $!\\n\"; }" + dataSection;
            } else if (parsedArgs.processOnly) {
                // Wrap the executable code in a loop that processes each line without printing
                parsedArgs.code = "while (<>) { " + chompCode + autoSplit + executableCode + " }" + dataSection;
            }
        }

        StringBuilder useStatements = new StringBuilder();
        if (parsedArgs.useVersion) {
            useStatements.append("use feature '").append(getPerlVersionBundle()).append("';\n");
        }
        for (ModuleUseStatement moduleStatement : parsedArgs.moduleUseStatements) {
            useStatements.append(moduleStatement.toString()).append("\n");
        }
        // Prepend the use statements to the code
        if (!useStatements.isEmpty()) {
            parsedArgs.code = useStatements + parsedArgs.code;
        }

        // Prepend rudimentary switch assignments if any
        if (parsedArgs.rudimentarySwitchAssignments != null) {
            parsedArgs.code = parsedArgs.rudimentarySwitchAssignments + parsedArgs.code;
        }
    }

    /**
     * Prints the help message detailing the usage of the program and its options.
     */
    private static void printHelp() {
        System.out.println("Usage: java -jar target/perlonjava-3.0.0.jar [options] [file] [args]");
        System.out.println();
        System.out.println("  -0[octal/hexadecimal] specify record separator (\\0, if no argument)");
        System.out.println("  -a                    autosplit mode with -n or -p (splits $_ into @F)");
        System.out.println("  -c                    check syntax only (runs BEGIN and CHECK blocks)");
        System.out.println("  -C[flags]             control Unicode/encoding features");
        System.out.println("  -e commandline        one line of program (several -e's allowed, omit programfile)");
        System.out.println("  -E commandline        like -e, but enables all optional features");
        System.out.println("  -f                    don't do $sitelib/sitecustomize.pl at startup");
        System.out.println("  -F/pattern/           split() pattern for -a switch (//'s are optional)");
        System.out.println("  -g                    read all input in one go (slurp), rather than line-by-line");
        System.out.println("  -i[extension]         edit <> files in place (makes backup if extension supplied)");
        System.out.println("  -Idirectory           specify @INC/#include directory (several -I's allowed)");
        System.out.println("  -l[octnum]            enable line ending processing, specifies line terminator");
        System.out.println("  -[mM][-]module        execute \"use/no module...\" before executing program");
        System.out.println("  -n                    assume \"while (<>) { ... }\" loop around program");
        System.out.println("  -p                    assume loop like -n but print line also, like sed");
        System.out.println("  -s                    enable rudimentary parsing for switches after program name");
        System.out.println("  -S                    look for programfile using PATH environment variable");
        System.out.println("  -w                    enable many useful warnings");
        System.out.println("  -W                    enable all warnings");
        System.out.println("  -x[directory]         ignore text before #!perl line (optionally cd to directory)");
        System.out.println("  -X                    disable all warnings");
        System.out.println("  --debug               enable debugging mode");
        System.out.println("  --tokenize            tokenize the input code");
        System.out.println("  --parse               parse the input code");
        System.out.println("  --disassemble         disassemble the generated code");
        System.out.println("  --interpreter         use bytecode interpreter instead of JVM compiler");
        System.out.println("  -h, --help            displays this help message");
        System.out.println();
//        System.out.println("Unicode/encoding flags for -C:");
//        System.out.println("  S  - enable UTF-8 for STDIN");
//        System.out.println("  O  - enable UTF-8 for STDOUT");
//        System.out.println("  E  - enable UTF-8 for STDERR");
//        System.out.println("  I  - enable UTF-8 for input (same as S)");
//        System.out.println("  A  - enable UTF-8 for all (STDIN, STDOUT, STDERR)");
//        System.out.println("  L  - enable UTF-8 based on locale");
//        System.out.println("  D  - default (equivalent to SOLA)");
//        System.out.println("  0  - disable all Unicode features");
//        System.out.println();
        System.out.println("Run 'perldoc perl' for more help with Perl.");
    }

    /**
     * Processes a single rudimentary switch and adds the corresponding variable assignment.
     *
     * @param arg        The switch argument to process.
     * @param parsedArgs The CompilerOptions object to configure.
     */
    private static void processRudimentarySwitch(String arg, CompilerOptions parsedArgs) {
        String varName;
        String varValue = "1"; // Default value

        if (arg.startsWith("--")) {
            // Handle --switch or --switch=value
            String switchPart = arg.substring(2);
            int equalsIndex = switchPart.indexOf('=');
            if (equalsIndex != -1) {
                varValue = switchPart.substring(equalsIndex + 1);
                varName = "${-" + switchPart.substring(0, equalsIndex) + "}";
            } else {
                varName = "${-" + switchPart + "}";
            }
        } else {
            // Handle -switch or -switch=value
            String switchPart = arg.substring(1);
            int equalsIndex = switchPart.indexOf('=');
            if (equalsIndex != -1) {
                varValue = switchPart.substring(equalsIndex + 1);
                varName = switchPart.substring(0, equalsIndex);
            } else {
                varName = switchPart;
            }
        }

        // Add the variable assignment to be prepended to the code
        if (parsedArgs.rudimentarySwitchAssignments == null) {
            parsedArgs.rudimentarySwitchAssignments = new StringBuilder();
        }
        parsedArgs.rudimentarySwitchAssignments
                .append("$main::")
                .append(varName)
                .append(" = '")
                .append(varValue.replace("'", "\\'"))
                .append("';\n");
    }

    /**
     * Handles debug flags specified with the -D switch.
     *
     * @param args       The command-line arguments.
     * @param parsedArgs The CompilerOptions object to configure.
     * @param index      The current index in the arguments array.
     * @param j          The current position in the clustered switch string.
     * @param arg        The current argument being processed.
     * @return The updated index after processing the debug flags.
     */
    private static int handleDebugFlags(String[] args, CompilerOptions parsedArgs, int index, int j, String arg) {
        String debugFlags = "";
        if (j < arg.length() - 1) {
            debugFlags = arg.substring(j + 1);
        } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
            debugFlags = args[++index];
        }

        // Store debug flags for potential future use
        parsedArgs.debugFlags = debugFlags;
        parsedArgs.debugEnabled = true;

        return index;
    }

    // Define a structured data type for module use statements
    static class ModuleUseStatement {
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
}
