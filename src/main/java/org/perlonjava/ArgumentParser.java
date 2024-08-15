package org.perlonjava;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ArgumentParser {
    public static class ParsedArguments {
        public boolean debugEnabled = false;
        public boolean tokenizeOnly = false;
        public boolean parseOnly = false;
        public boolean compileOnly = false;
        public String code = null;
        public String fileName = null;
    }

    public static ParsedArguments parseArguments(String[] args) {
        ParsedArguments parsedArgs = new ParsedArguments();
        String defaultCode = 
                ""
                + "my $a = 15 ; \n"
                + "my $x = $a ; \n"
                + "say $x ; \n"
                + "$a = 12 ; \n"
                + "say $a ; \n"
                + " say ( sub { say @_ } ) ; \n"    // anon sub
                + " ( sub { say 'HERE' } )->(88888) ; \n"    // anon sub
                + " ( sub { say \"<@_>\" } )->(88,89,90) ; \n"    // anon sub
                + "eval ' $a = $a + 1 '; "    // eval string
                + "say $a ; \n"
                + "do { $a; if (1) { say 123 } elsif (3) { say 345 } else { say 456 } } ; \n"
                + "print \"Finished; value is $a\\n\"; "
                + "my ($i, %j) = (1,2,3,4,5); "
                + "say(%j); "
                + "$a = {a => 'hash-value'} ; say $a->{a}; my $b = [4,5]; say $b->[1]; "
                + "return 5; \n";

        parsedArgs.code = defaultCode;

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
        System.out.println("  -c              Compiles the input code only.");
        System.out.println("  -h, --help      Displays this help message.");
    }
}

