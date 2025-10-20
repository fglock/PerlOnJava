package org.perlonjava.parser;

import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;

import static org.perlonjava.parser.TokenUtils.peek;

/**
 * FileHandle parser for PerlOnJava.
 * <p>
 * This class is responsible for parsing Perl file handle expressions in various contexts,
 * particularly in print/printf statements and other I/O operations. Perl has several ways
 * to specify file handles:
 *
 * <ul>
 *   <li>Bareword file handles: {@code print STDOUT "hello"}</li>
 *   <li>Glob references: {@code print *STDOUT "hello"}</li>
 *   <li>Scalar variables containing file handles: {@code print $fh "hello"}</li>
 *   <li>Bracketed forms: {@code print {STDOUT} "hello"} or {@code print {$fh} "hello"}</li>
 * </ul>
 * <p>
 * The parser must distinguish between file handle expressions and regular expressions,
 * which is particularly tricky for scalar variables that could be either a file handle
 * or part of an arithmetic expression.
 *
 * @see ParsePrimary
 * @see GlobalVariable
 */
public class FileHandle {

    /**
     * Parses a file handle expression from the token stream.
     * <p>
     * This method attempts to parse various forms of Perl file handle syntax:
     *
     * <h3>Bareword File Handles</h3>
     * Traditional Perl bareword file handles like STDOUT, STDERR, STDIN, or user-defined
     * file handles. These can appear with or without curly braces:
     * <pre>
     *   print STDOUT "hello";     # bareword
     *   print {STDOUT} "hello";   # bracketed bareword
     * </pre>
     *
     * <h3>Glob References</h3>
     * File handles can be specified as glob references using the * or \* syntax:
     * <pre>
     *   print *STDOUT "hello";    # glob reference
     *   print {*STDOUT} "hello";  # bracketed glob
     *   print {\*STDOUT} "hello"; # reference to glob
     * </pre>
     *
     * <h3>Scalar Variables</h3>
     * Modern Perl often stores file handles in scalar variables:
     * <pre>
     *   open my $fh, '>', 'file.txt';
     *   print $fh "hello";        # scalar file handle
     *   print {$fh} "hello";      # bracketed scalar
     * </pre>
     *
     * <h3>Disambiguation</h3>
     * When parsing scalar variables without brackets, the parser must determine if the
     * scalar is a file handle or part of an expression. For example:
     * <pre>
     *   print $fh + 2;    # $fh is NOT a file handle (arithmetic expression)
     *   print $fh "text"; # $fh IS a file handle
     *   print $fh;        # ambiguous - depends on context
     * </pre>
     *
     * @param parser The parser instance containing the token stream and parse context
     * @return A Node representing the parsed file handle, or null if no valid file handle was found
     */
    public static Node parseFileHandle(Parser parser) {
        boolean hasBracket = false;

        // Check if the file handle is enclosed in curly braces
        // Perl allows {FILEHANDLE} syntax for disambiguation
        if (peek(parser).text.equals("{")) {
            TokenUtils.consume(parser);
            hasBracket = true;
        }

        LexerToken token = peek(parser);
        Node fileHandle = null;

        // Handle glob or string expressions when we have brackets
        if (hasBracket && token.type == LexerTokenType.OPERATOR && (token.text.equals("*") || token.text.equals("\\") || token.text.equals("\""))) {
            // Parse glob expression: {*STDOUT}, {\*STDOUT}, {"STDOUT"} etc.
            // ParsePrimary.parsePrimary() has logic to handle both * and \* cases
            // and will create the appropriate glob or reference node
            fileHandle = ParsePrimary.parsePrimary(parser);
        }
        // Handle bareword file handles (most common case)
        // Examples: STDOUT, STDERR, STDIN, or user-defined handles like LOG, FILE, etc.
        else if (token.type == LexerTokenType.IDENTIFIER) {
            // Try to parse as a bareword identifier
            // parseSubroutineIdentifier handles qualified names like Some::Package::HANDLE
            String name = IdentifierParser.parseSubroutineIdentifier(parser);
            if (name != null) {
                fileHandle = parseBarewordHandle(parser, name);
            }
        }
        // Handle scalar variable file handles
        // Modern Perl idiom: open my $fh, '<', 'filename'; print $fh "text";
        else if (token.text.equals("$")) {
            // When bracketed like {$fh} or {$obj{key}}, parse as a full expression
            // This allows complex expressions like {$_[0]{output_fh}}
            if (hasBracket) {
                // Parse the full expression inside the braces, stopping at }
                // Use precedence for comma to allow most expressions but stop at }
                fileHandle = parser.parseExpression(parser.getPrecedence(","));
            } else {
                // Parse the scalar variable without postfix operators initially
                fileHandle = ParsePrimary.parsePrimary(parser);
            }

            // When not bracketed, we need to disambiguate between:
            // - print $fh "text";  # $fh is a file handle
            // - print $fh + 2;     # $fh is part of an expression
            // - print $fh;         # ambiguous case
            if (!hasBracket) {
                // Check if the next token is an infix operator
                // If so, this is likely an expression, not a file handle
                String nextText = peek(parser).text;

                if ("<<".equals(nextText)) {
                    // `<<` is an infix, but it is also a heredoc
                } else if (ParserTables.INFIX_OP.contains(nextText) || "{[".contains(nextText) || "->".equals(nextText)) {
                    // Examples that are NOT file handles:
                    // print $fh + 2;     # arithmetic
                    // print $fh{key};    # hash access
                    // print $fh[0];      # array access
                    // print $fh->method; # method call
                    fileHandle = null;
                }

                // Check if we're at the end of the print list
                // "print $fh;" with nothing after is NOT a file handle
                // but "print $fh 'text';" IS a file handle
                if (ListParser.looksLikeEmptyList(parser)) {
                    // print $fh;  # $fh is the thing to print, not a file handle
                    fileHandle = null;
                }
            }
        }

        // If we had an opening bracket, consume the closing bracket
        if (hasBracket) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        }

        return fileHandle;
    }

    public static Node parseBarewordHandle(Parser parser, String name) {
        name = normalizeBarewordHandle(parser, name);

        // Check if this is a known file handle in the global I/O table
        // This helps distinguish between file handles and other barewords
        if (GlobalVariable.existsGlobalIO(name) || isStandardFilehandle(name)) {
            // Create a GLOB reference for the file handle, like `\*FH`
            return new OperatorNode("\\",
                    new OperatorNode("*",
                            new IdentifierNode(name, parser.tokenIndex), parser.tokenIndex), parser.tokenIndex);
        }
        return null;
    }

    /**
     * Checks if a normalized name represents a standard filehandle.
     *
     * @param normalizedName The normalized filehandle name (e.g., "main::STDOUT")
     * @return true if the name is a standard filehandle
     */
    private static boolean isStandardFilehandle(String normalizedName) {
        return "main::STDOUT".equals(normalizedName) ||
                "main::STDERR".equals(normalizedName) ||
                "main::STDIN".equals(normalizedName);
    }

    public static String normalizeBarewordHandle(Parser parser, String name) {
        // Determine the package context for the file handle
        String packageName = parser.ctx.symbolTable.getCurrentPackage();

        // Standard file handles (STDOUT, STDERR, STDIN) always belong to main::
        // regardless of the current package context
        if (name.equals("STDOUT") || name.equals("STDERR") || name.equals("STDIN")) {
            packageName = "main";
        }

        // Normalize the name to include the package qualifier
        // This converts "HANDLE" to "Package::HANDLE" format
        name = NameNormalizer.normalizeVariableName(name, packageName);
        return name;
    }
}