package org.perlonjava;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ArgumentParser {
    public static ParsedArguments parseArguments(String[] args) {
        ParsedArguments parsedArgs = new ParsedArguments();
        parsedArgs.code = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-e":
                    if (i + 1 < args.length) {
                        parsedArgs.code = args[i + 1];
                        parsedArgs.fileName = "-e";
                        i++;
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
                case "-h":
                case "--help":
                    printHelp();
                    System.exit(0);
                    break;
                default:
                    parsedArgs.fileName = args[i];
                    try {
                        parsedArgs.code = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
                    } catch (IOException e) {
                        System.err.println("Error: Unable to read file " + parsedArgs.fileName);
                        System.exit(1);
                    }
                    break;
            }
        }

        return parsedArgs;
    }

    private static void printHelp() {
        System.out.println("Usage: java -cp <classpath> org.perlonjava.Main [options] [file]");
        System.out.println("Options:");
        System.out.println("  -e <code>       Specifies the code to be processed.");
        System.out.println("  --debug         Enables debugging mode.");
        System.out.println("  --tokenize      Tokenizes the input code.");
        System.out.println("  --parse         Parses the input code.");
        System.out.println("  --disassemble   Disassemble the generated code.");
        System.out.println("  -c              Compiles the input code only.");
        System.out.println("  -h, --help      Displays this help message.");
    }

    public static class ParsedArguments {
        public boolean debugEnabled = false;
        public boolean disassembleEnabled = false;
        public boolean tokenizeOnly = false;
        public boolean parseOnly = false;
        public boolean compileOnly = false;
        public String code = null;
        public String fileName = null;
    }
}

