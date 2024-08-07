import java.util.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The Main class serves as the entry point for the Perl-to-Java bytecode compiler and runtime
 * evaluator. It parses Perl code, generates corresponding Java bytecode using ASM, and executes the
 * generated bytecode.
 */
public class Main {

    /**
     * The main method initializes the compilation and execution process.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        try {
            boolean debugEnabled = false; // Default to debugging off
            boolean tokenizeOnly = false;
            boolean parseOnly = false;
            boolean compileOnly = false;

            // Default Perl code to be compiled and executed
            String fileName = "test.pl";
            String code =
                    ""
                    + "my $a = 15 ;"
                    + "my $x = $a ;"
                    + "say $x ;"
                    + "$a = 12 ;"
                    + "say $a ;"
                    + " say ( sub { say @_ } ) ;"    // anon sub
                    + " ( sub { say 'HERE' } )->(88888) ;"    // anon sub
            // XXX  + " ( sub { say @_ } )->(88888) ;"    // anon sub
                    + "eval ' $a = $a + 1 '; "    // eval string
                    + "say $a ;"
                    + "do { $a; if (1) { say 123 } elsif (3) { say 345 } else { say 456 } } ;"
                    + "print \"Finished; value is $a\\n\"; "
                    + "my ($i, %j) = (1,2,3,4,5); "
                    + "say(%j); "
                    + " my $a = {a => 'hash-value'} ; say $a->{a}; my $b = [4,5]; say $b->[1]; "
                    + "return 5;";

            /*
             * Parse command-line arguments
             * This loop processes each command-line argument to configure the program's behavior.
             * Supported arguments:
             * -e <code>: Specifies the code to be processed, overriding the default code.
             * --debug: Enables debugging mode.
             * --tokenize: Sets the program to tokenize the input code. Cannot be combined with --parse or -c.
             * --parse: Sets the program to parse the input code. Cannot be combined with --tokenize or -c.
             * -c: Sets the program to compile the input code only. Cannot be combined with --tokenize or --parse.
             * -h, --help: Displays this help message.
             * <filename>: Specifies a file containing the code to be processed. If an unrecognized argument is encountered,
             *             the program will assume it is a filename and attempt to read the code from the file.
             * If an unrecognized argument is encountered and it is not a valid file, the program will print an error message and exit.
             */
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-e") && i + 1 < args.length) {
                    code = args[i + 1]; // Read the code from the command line parameter
                    fileName = "-e";
                    i++; // Skip the next argument as it is the code
                } else if (args[i].equals("--debug")) {
                    debugEnabled = true; // Enable debugging
                } else if (args[i].equals("--tokenize")) {
                    if (parseOnly || compileOnly) {
                        System.err.println("Error: --tokenize cannot be combined with --parse or -c");
                        System.exit(1);
                    }
                    tokenizeOnly = true;
                } else if (args[i].equals("--parse")) {
                    if (tokenizeOnly || compileOnly) {
                        System.err.println("Error: --parse cannot be combined with --tokenize or -c");
                        System.exit(1);
                    }
                    parseOnly = true;
                } else if (args[i].equals("-c")) {
                    if (tokenizeOnly || parseOnly) {
                        System.err.println("Error: -c cannot be combined with --tokenize or --parse");
                        System.exit(1);
                    }
                    compileOnly = true;
                } else if (args[i].equals("-h") || args[i].equals("--help")) {
                    printHelp();
                    System.exit(0);
                } else {
                    // Assume the argument is a filename
                    fileName = args[i];
                    try {
                        code = new String(Files.readAllBytes(Paths.get(fileName)));
                    } catch (IOException e) {
                        System.err.println("Can't open perl script: " + fileName);
                        System.exit(1);
                    }
                }
            }

            // Create the compiler context
            EmitterContext ctx = new EmitterContext(
                    fileName, // Source filename
                    ASMMethodCreator.generateClassName(), // internal java class name
                    new ScopedSymbolTable(), // Top-level symbol table
                    null, // Return label
                    null, // Method visitor
                    ContextType.VOID, // Call context
                    true, // Is boxed
                    null,  // errorUtil
                    debugEnabled   // debugEnabled flag
            );

            // Enter a new scope in the symbol table and add special Perl variables
            ctx.symbolTable.enterScope();
            ctx.symbolTable.addVariable("this"); // anon sub instance is local variable 0
            ctx.symbolTable.addVariable("@_"); // Argument list is local variable 1
            ctx.symbolTable.addVariable("wantarray"); // Call context is local variable 2

            Runtime.getGlobalVariable("$@");    // initialize $@ to "undef"
            Runtime.getGlobalVariable("$_");    // initialize $_ to "undef"

            ctx.logDebug("parse code: " + code);
            ctx.logDebug("  call context " + ctx.contextType);

            // Create the Token list
            Lexer lexer = new Lexer(code);
            List<Token> tokens = lexer.tokenize(); // Tokenize the Perl code
            if (tokenizeOnly) {
                // Printing the tokens
                for (Token token : tokens) {
                  System.out.println(token);
                }
                System.exit(0); // success
            }

            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
            Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
            Node ast = parser.parse(); // Generate the abstract syntax tree (AST)
            if (parseOnly) {
                // Printing the ast
                System.out.println(ast);
                System.exit(0); // success
            }
            ctx.logDebug("-- AST:\n" + ast + "--\n");

            // Create the Java class from the AST
            ctx.logDebug("createClassWithMethod");
            // Create a new instance of ErrorMessageUtil, resetting the line counter
            ctx.errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
            Class<?> generatedClass = ASMMethodCreator.createClassWithMethod(
                    ctx,
                    new String[] {}, // Closure variables
                    ast,
                    false   // no try-catch
            );
            if (compileOnly) {
                System.exit(0); // success
            }

            // Find the constructor
            Constructor<?> constructor = generatedClass.getConstructor();

            // Instantiate the class
            Object instance = constructor.newInstance();

            // Find the apply method
            Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, ContextType.class);

            // Invoke the method
            RuntimeList result = (RuntimeList) applyMethod.invoke(instance, new RuntimeArray(), ContextType.SCALAR);

            // Print the result of the execution
            ctx.logDebug("Result of generatedMethod: " + result);
        } catch (Exception e) {
            e.printStackTrace(); // Print any exceptions that occur during the process
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java Main [options] [filename]");
        System.out.println("Options:");
        System.out.println("  -e <code>       Specifies the code to be processed, overriding the default code.");
        System.out.println("  --debug         Enables debugging mode.");
        System.out.println("  --tokenize      Sets the program to tokenize the input code. Cannot be combined with --parse or -c.");
        System.out.println("  --parse         Sets the program to parse the input code. Cannot be combined with --tokenize or -c.");
        System.out.println("  -c              Sets the program to compile the input code only. Cannot be combined with --tokenize or --parse.");
        System.out.println("  -h, --help      Displays this help message.");
        System.out.println("  <filename>      Specifies a file containing the code to be processed.");
    }
}

