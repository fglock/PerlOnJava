import java.util.*;

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
            // Default Perl code to be compiled and executed
            String fileName = "test.pl";
            String code =
                    ""
                    + "my $a = 15 ;"
                    + "my $x = $a ;"
                    + "print $x ;"
                    + "$a = 12 ;"
                    + "print $a ;"
                    + " ( sub { print @_ } )->(88888) ;"
                    + "print $a ;"
                    + "do { $a; if (1) { print 123 } elsif (3) { print 345 } else { print 456 } } ;"
                    + "print \"Finished; value is $a\\n\"; "
                    + "return 5;";

            // If code is provided as a command-line argument, use it instead of the default code
            if (args.length >= 2 && args[0].equals("-e")) {
                code = args[1]; // Read the code from the command line parameter
                fileName = "-e";
            }

            // Create the compiler context
            EmitterContext ctx = new EmitterContext(
                    fileName, // Source filename
                    null, // Java class name
                    new ScopedSymbolTable(), // Top-level symbol table
                    null, // Return label
                    null, // Method visitor
                    null, // Call context
                    false, // Is boxed
                    null  // errorUtil
            );

            // Enter a new scope in the symbol table and add special Perl variables
            ctx.symbolTable.enterScope();
            ctx.symbolTable.addVariable("@_"); // Argument list is local variable 0
            ctx.symbolTable.addVariable("wantarray"); // Call context is local variable 1

            System.out.println("parse code: " + code);
            System.out.println("  call context " + ctx.contextType);

            // Create the Token list
            Lexer lexer = new Lexer(code);
            List<Token> tokens = lexer.tokenize(); // Tokenize the Perl code

            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
            Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
            Node ast = parser.parse(); // Generate the abstract syntax tree (AST)
            System.out.println("-- AST:\n" + ast + "--\n");

            // Create the Java class from the AST
            System.out.println("createClassWithMethod");
            // Create a new instance of ErrorMessageUtil, resetting the line counter
            ctx.errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
            Class<?> generatedClass = ASMMethodCreator.createClassWithMethod(
                    ctx,
                    new String[] {}, // Closure variables
                    ast
            );

            // Convert the generated class into a Runtime object
            String newClassName = generatedClass.getName();
            Runtime.anonSubs.put(newClassName, generatedClass); // Store the class in the runtime map
            Runtime anonSub = Runtime.make_sub(newClassName); // Create a Runtime instance for the generated class
            Runtime result = anonSub.apply(new Runtime(999), ContextType.SCALAR); // Execute the generated method

            // Print the result of the execution
            System.out.println("Result of generatedMethod: " + result);
        } catch (Exception e) {
            e.printStackTrace(); // Print any exceptions that occur during the process
        }
    }
}

/*
 * TODO:
 * - easy wins
 *       - loops
 *       - $_ $@
 *       - ternary operator
 *
 * - harder to implement
 *       - BEGIN block
 *       - eval string
 *       - eval block, catch error
 *       - test suite
 *
 * - easy, but low impact
 *      - wantarray()
 *      - warn()
 *      - die()
 *      - other builtins
 *
 * - more difficult, and low impact
 *      - caller()
 *      - goto()
 *      - thread
 *      - optimizations
 *
 * - Parser: low-precedence operators not, or, and
 *
 * - cleanup the closure code to only add the lexical variables mentioned in the AST
 *
 * - format error messages and warnings
 *       - compile time: get file position from lexer
 *       - run-time: add annotations to the bytecode
 *
 * - test different Perl data types
 *       - array, hash, string, double, references
 *       - experiment with Perlito runtime
 *
 * - global variables and namespaces
 *       - named subroutine declaration
 *       - Perl classes
 *
 * - local variables
 *     set up the cleanup before RETURN
 *     set up exception handling
 *
 * - add debug information (line numbers)
 *     Label thisLabel = new Label();
 *     ctx.mv.visitLabel(thisLabel);
 *     ctx.mv.visitLineNumber(10, thisLabel); // Associate line number 10 with thisLabel
 *
 * - tests
 *
 * - implement thread-safety - it may need locking when calling ASM
 *
 * - create multiple classes; ensure GC works for these classes
 *
 * - goto, macros - control structures
 *       - test FOR, WHILE
 *
 * - eval string
 *     freeze the ctx.symbolTable at eval string, we will need it to compile the string later
 *
 * - BEGIN-block
 *
 * - read code from STDIN
 *
 *       // Read input from STDIN
 *       Scanner scanner = new Scanner(System.in);
 *       System.out.println("Enter code:");
 *       String code = scanner.nextLine();
 *       scanner.close();
 *
 */

