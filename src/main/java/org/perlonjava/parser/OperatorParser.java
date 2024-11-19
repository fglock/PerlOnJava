package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

import static org.perlonjava.lexer.LexerTokenType.NEWLINE;
import static org.perlonjava.lexer.LexerTokenType.WHITESPACE;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * This class provides methods for parsing various Perl operators and constructs.
 */
public class OperatorParser {

    /**
     * Parses map, grep, and sort operators.
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A BinaryOperatorNode representing the parsed operator.
     */
    static BinaryOperatorNode parseMapGrepSort(Parser parser, LexerToken token) {
        ListNode operand;
        // Handle 'sort' keyword as a Binary operator with a Code and List operands
        operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null && token.text.equals("sort")) {
            // create default block for `sort`: { $a cmp $b }
            block = new BlockNode(List.of(new BinaryOperatorNode("cmp", new OperatorNode("$", new IdentifierNode("main::a", parser.tokenIndex), parser.tokenIndex), new OperatorNode("$", new IdentifierNode("main::b", parser.tokenIndex), parser.tokenIndex), parser.tokenIndex)), parser.tokenIndex);
        }
        if (block == null) {
            // use the first argument as block: 'map ord, 1,2,3'
            if (!operand.elements.isEmpty()) {
                block = new BlockNode(
                        List.of(operand.elements.removeFirst()),
                        parser.tokenIndex);
            }
        }
        if (block instanceof BlockNode) {
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }

    /**
     * Parses the 'require' operator.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed 'require' operator.
     * @throws PerlCompilerException If there's a syntax error.
     */
    static Node parseRequire(Parser parser) {
        LexerToken token;
        // Handle 'require' keyword which can be followed by a version, bareword or filename
        token = TokenUtils.peek(parser);
        Node operand;

        // `require` version
        if (token.type == LexerTokenType.NUMBER || token.text.matches("^v\\d+$")) {
            TokenUtils.consume(parser);
            operand = StringParser.parseVstring(parser, token.text, parser.tokenIndex);
        } else if (token.type == LexerTokenType.IDENTIFIER) {

            // `require` module
            String moduleName = IdentifierParser.parseSubroutineIdentifier(parser);
            parser.ctx.logDebug("name `" + moduleName + "`");
            if (moduleName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            String fileName = NameNormalizer.moduleToFilename(moduleName);
            operand = ListNode.makeList(new StringNode(fileName, parser.tokenIndex));
        } else {
            // `require` file
            operand = ListParser.parseZeroOrOneList(parser, 1);
        }
        return new OperatorNode("require", operand, parser.tokenIndex);
    }

    /**
     * Parses the 'do' operator.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed 'do' operator.
     */
    static Node parseDoOperator(Parser parser) {
        LexerToken token;
        Node block;
        // Handle 'do' keyword which can be followed by a block or filename
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            block = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            return block;
        }
        // `do` file
        Node operand = ListParser.parseZeroOrOneList(parser, 1);
        return new OperatorNode("doFile", operand, parser.tokenIndex);
    }

    /**
     * Parses the 'eval' operator.
     *
     * @param parser The Parser instance.
     * @return An AbstractNode representing the parsed 'eval' operator.
     */
    static AbstractNode parseEval(Parser parser) {
        Node block;
        Node operand;
        LexerToken token;
        // Handle 'eval' keyword which can be followed by a block or an expression
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
            block = parser.parseBlock();
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new SubroutineNode(null, null, null, block, true, parser.tokenIndex), new ListNode(parser.tokenIndex), parser.tokenIndex);
        } else {
            // Otherwise, parse an expression, and default to $_
            operand = ListParser.parseZeroOrOneList(parser, 0);
            if (((ListNode) operand).elements.isEmpty()) {
                // create `$_` variable
                operand = new OperatorNode(
                        "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
            }
        }
        return new EvalOperatorNode(
                "eval",
                operand,
                parser.ctx.symbolTable.snapShot(), // Freeze the scoped symbol table for the eval context
                parser.tokenIndex
        );
    }

    /**
     * Parses the diamond operator (<>).
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A Node representing the parsed diamond operator.
     */
    static Node parseDiamondOperator(Parser parser, LexerToken token) {
        // Save the current token index to restore later if needed
        int currentTokenIndex = parser.tokenIndex;
        if (token.text.equals("<")) {
            String tokenText = parser.tokens.get(parser.tokenIndex).text;

            // Check if the token is a dollar sign, indicating a variable
            if (tokenText.equals("$")) {
                // Handle the case for <$fh>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;
                Node var = parser.parseVariable("$"); // Parse the variable following the dollar sign
                parser.ctx.logDebug("diamond operator var " + var);

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            var,
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                }
            }

            // Restore the token index
            parser.tokenIndex = currentTokenIndex;

            // Check if the token is one of the standard input sources
            if (tokenText.equals("STDIN") || tokenText.equals("DATA") || tokenText.equals("ARGV")) {
                // Handle the case for <STDIN>, <DATA>, or <ARGV>
                parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
                parser.tokenIndex++;

                // Check if the next token is a closing angle bracket
                if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                    TokenUtils.consume(parser); // Consume the '>' token
                    // Return a BinaryOperatorNode representing a readline operation
                    return new BinaryOperatorNode("readline",
                            new IdentifierNode("main::" + tokenText, currentTokenIndex),
                            new ListNode(parser.tokenIndex), parser.tokenIndex);
                }
            }
        }
        // Restore the token index
        parser.tokenIndex = currentTokenIndex;

        // Handle other cases like <>, <<>>, or <*.*> by parsing as a raw string
        return StringParser.parseRawString(parser, token.text);
    }

    public static Node parseCoreOperator(Parser parser, LexerToken token, int startIndex) {
        Node operand;
        int currentIndex = parser.tokenIndex;

        switch (token.text) {
            case "__LINE__":
                return new NumberNode(Integer.toString(parser.ctx.errorUtil.getLineNumber(parser.tokenIndex)), parser.tokenIndex);
            case "__FILE__":
                return new StringNode(parser.ctx.compilerOptions.fileName, parser.tokenIndex);
            case "__PACKAGE__":
                return new StringNode(parser.ctx.symbolTable.getCurrentPackage(), parser.tokenIndex);
            case "__SUB__":
            case "time":
            case "times":
            case "fork":
            case "wait":
            case "wantarray":
                // Handle operators with zero arguments
                return new OperatorNode(token.text, null, currentIndex);
            case "not":
                // Handle 'not' keyword as a unary operator with an operand
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(token.text, operand, currentIndex);
            case "abs":
            case "log":
            case "sqrt":
            case "cos":
            case "sin":
            case "exp":
            case "rand":
            case "srand":
            case "study":
            case "undef":
            case "exit":
            case "quotemeta":
            case "ref":
            case "oct":
            case "hex":
            case "pop":
            case "shift":
            case "sleep":
            case "int":
            case "chr":
            case "ord":
            case "fc":
            case "lc":
            case "lcfirst":
            case "uc":
            case "ucfirst":
            case "chop":
            case "chomp":
            case "length":
            case "localtime":
            case "gmtime":
            case "rmdir":
            case "glob":
            case "caller":
            case "reset":
            case "pos":
            case "select":
            case "prototype":
                return parseOperatorWithOneOptionalArgument(parser, token);
            case "readpipe":
                // one optional argument
                operand = ListParser.parseZeroOrOneList(parser, 0);
                if (((ListNode) operand).elements.isEmpty()) {
                    // create `$_` variable
                    operand = new OperatorNode(
                            "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
                }
                return new OperatorNode("qx", operand, parser.tokenIndex);
            case "unpack":
                // Handle operators with one mandatory, one optional argument
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                if (((ListNode) operand).elements.size() == 1) {
                    // create `$_` variable
                    ((ListNode) operand).elements.add(
                            new OperatorNode(
                                    "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex)
                    );
                }
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "readdir":
            case "closedir":
            case "telldir":
            case "rewinddir":
                // Handle operators with one mandatory argument
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "rindex":
            case "index":
            case "atan2":
            case "crypt":
            case "opendir":
            case "seekdir":
                // Handle operators with two mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 2, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "vec":
                // Handle operators with 3 mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "socket":
                // Handle operators with 4 mandatory arguments
                operand = ListParser.parseZeroOrMoreList(parser, 4, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "bless":
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                Node ref = ((ListNode) operand).elements.get(0);
                Node className = ((ListNode) operand).elements.get(1);
                if (className == null) {
                    className = new StringNode("main", currentIndex);
                }
                return new BinaryOperatorNode("bless", ref, className, currentIndex);
            case "split":
                // RuntimeList split(RuntimeScalar quotedRegex, RuntimeScalar string, RuntimeScalar limitArg)
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, true);
                Node separator =
                        ((ListNode) operand).elements.isEmpty()
                                ? new StringNode(" ", currentIndex)
                                : ((ListNode) operand).elements.removeFirst();
                if (separator instanceof OperatorNode) {
                    if (((OperatorNode) separator).operator.equals("matchRegex")) {
                        ((OperatorNode) separator).operator = "quoteRegex";
                    }
                }
                return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
            case "push":
            case "unshift":
            case "join":
            case "substr":
            case "sprintf":
                // Handle 'join' keyword as a Binary operator with a RuntimeList operand
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                separator = ((ListNode) operand).elements.removeFirst();
                return new BinaryOperatorNode(token.text, separator, operand, currentIndex);
            case "sort":
            case "map":
            case "grep":
                return parseMapGrepSort(parser, token);
            case "pack":
                // Handle operators with one or more arguments
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "reverse":
            case "splice":
            case "unlink":
            case "mkdir":
            case "die":
            case "warn":
                // Handle operators with any number of arguments
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "readline":
            case "eof":
            case "tell":
            case "getc":
            case "open":
            case "close":
            case "fileno":
            case "truncate":
                // Handle 'open' keyword as a Binary operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                Node handle = null;
                if (((ListNode) operand).elements.isEmpty()) {
                    String defaultHandle = switch (token.text) {
                        case "readline":
                            yield "main::ARGV";
                        case "eof":
                            yield "main::STDIN";
                        case "tell":
                            yield "main::^LAST_FH";
                        case "getc":
                            yield "main::STDIN";
                        case "open":
                        case "fileno":
                            throw new PerlCompilerException(parser.tokenIndex, "Not enough arguments for " + token.text, parser.ctx.errorUtil);
                        case "close":
                            yield "main::STDIN";    // XXX TODO use currently selected file handle
                        default:
                            throw new IllegalStateException("Unexpected value: " + token.text);
                    };
                    handle = new IdentifierNode(defaultHandle, currentIndex);
                } else {
                    handle = ((ListNode) operand).elements.removeFirst();
                }
                return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
            case "seek":
                // Handle 'seek' keyword as a Binary operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(parser, 3, false, true, false, false);
                handle = ((ListNode) operand).elements.removeFirst();
                return new BinaryOperatorNode(token.text, handle, operand, currentIndex);
            case "printf":
            case "print":
            case "say":
                // Handle 'print' keyword as a Binary operator with a FileHandle and List operands
                ListNode printOperand = ListParser.parseZeroOrMoreList(parser, 0, false, true, true, false);
                handle = printOperand.handle;
                printOperand.handle = null;
                if (handle == null) {
                    // `print` without arguments means `print to last selected filehandle`
                    handle = new OperatorNode("select", new ListNode(currentIndex), currentIndex);
                }
                if (printOperand.elements.isEmpty()) {
                    // `print` without arguments means `print $_`
                    printOperand.elements.add(
                            new OperatorNode(
                                    "$",
                                    new IdentifierNode("_", parser.tokenIndex),
                                    parser.tokenIndex)
                    );
                }
                return new BinaryOperatorNode(token.text, handle, printOperand, currentIndex);
            case "delete":
            case "exists":
            case "defined":
                parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                operand = ListParser.parseZeroOrOneList(parser, 1);
                parser.parsingTakeReference = false;
                return new OperatorNode(token.text, operand, currentIndex);
            case "scalar":
            case "values":
            case "keys":
            case "each":
                operand = parser.parsePrimary();
                return new OperatorNode(token.text, operand, currentIndex);
            case "our":
            case "state":
            case "my":
                return parseVariableDeclaration(parser, token.text, currentIndex);
            case "local":
                // Handle 'local' keyword as a unary operator with an operand
                if (peek(parser).text.equals("(")) {
                    operand = parser.parsePrimary();
                } else {
                    operand = parser.parseExpression(parser.getPrecedence("++"));
                }
                return new OperatorNode(token.text, operand, currentIndex);
            case "last":
            case "next":
            case "redo":
                // Handle 'next'
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                return new OperatorNode(token.text, operand, currentIndex);
            case "goto":
                // The 'goto' keyword is treated as a unary operator with an operand.
                // In this context, parentheses around the operand are ignored.
                // Since Java does not support direct tail call optimization or a 'goto' statement,
                // we simulate the behavior by transforming 'goto &x' into 'return &x'.
                // This effectively means that the control flow will return the result of the operand,
                // mimicking the tail call by returning the result of another method call.
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
                return new OperatorNode("return", operand, currentIndex);
            case "return":
                // Handle 'return' keyword as a unary operator with an operand;
                // Parentheses are ignored.
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                return new OperatorNode("return", operand, currentIndex);
            case "eval":
                return parseEval(parser);
            case "do":
                return parseDoOperator(parser);
            case "require":
                return parseRequire(parser);
            case "sub":
                // Handle 'sub' keyword to parse an anonymous subroutine
                return SubroutineParser.parseSubroutineDefinition(parser, false, null);
            case "q":
            case "qq":
            case "qx":
            case "qw":
            case "qr":
            case "tr":
            case "y":
            case "s":
            case "m":
                // Handle special-quoted domain-specific arguments
                String operator = token.text;
                // Skip whitespace, but not `#`
                parser.tokenIndex = startIndex;
                consume(parser);
                while (parser.tokenIndex < parser.tokens.size()) {
                    LexerToken token1 = parser.tokens.get(parser.tokenIndex);
                    if (token1.type == WHITESPACE || token1.type == NEWLINE) {
                        parser.tokenIndex++;
                    } else {
                        break;
                    }
                }
                return StringParser.parseRawString(parser, token.text);
        }
        return null;
    }

    private static void addVariableToScope(EmitterContext ctx, String operator, OperatorNode node) {
        String sigil = node.operator;
        if ("$@%".contains(sigil)) {
            // not "undef"
            Node identifierNode = node.operand;
            if (identifierNode instanceof IdentifierNode) { // my $a
                String name = ((IdentifierNode) identifierNode).name;
                String var = sigil + name;
                if (ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                    System.err.println(
                            ctx.errorUtil.errorMessage(node.getIndex(),
                                    "Warning: \"" + operator + "\" variable "
                                            + var
                                            + " masks earlier declaration in same ctx.symbolTable"));
                }
                int varIndex = ctx.symbolTable.addVariable(var, operator, node);
            }
        }
    }

    private static OperatorNode parseVariableDeclaration(Parser parser, String operator, int currentIndex) {

        // Create OperatorNode ($, @, %), ListNode (includes undef), SubroutineNode
        Node operand = parser.parsePrimary();
        parser.ctx.logDebug("parseVariableDeclaration " + operator + ": " + operand);

        // Add variables to the scope
        if (operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode operandNode) {
                    addVariableToScope(parser.ctx, operator, operandNode);
                }
            }
        } else if (operand instanceof OperatorNode operandNode) {

            if (operator.equals("state")) {
                // Give the variable a persistent id (See: PersistentVariable.java)
                if (operandNode.id == 0) {
                    operandNode.id = EmitterMethodCreator.classCounter++;
                }
            }

            addVariableToScope(parser.ctx, operator, operandNode);
        }

        return new OperatorNode(operator, operand, currentIndex);
    }

    private static OperatorNode parseOperatorWithOneOptionalArgument(Parser parser, LexerToken token) {
        Node operand;
        // Handle operators with one optional argument
        String text = token.text;
        operand = ListParser.parseZeroOrOneList(parser, 0);
        if (((ListNode) operand).elements.isEmpty()) {
            switch (text) {
                case "sleep":
                    operand = new NumberNode(Long.toString(Long.MAX_VALUE), parser.tokenIndex);
                    break;
                case "pop":
                case "shift":
                    // create `@_` variable
                    // XXX in main program, use `@ARGV`
                    operand = new OperatorNode(
                            "@", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
                    break;
                case "localtime":
                case "gmtime":
                case "caller":
                case "reset":
                case "select":
                    // default to empty list
                    break;
                case "srand":
                    operand = new OperatorNode("undef", null, parser.tokenIndex);
                    break;
                case "exit":
                    // create "0"
                    operand = new NumberNode("0", parser.tokenIndex);
                    break;
                case "undef":
                    operand = null;
                    break;  // leave it empty
                case "rand":
                    // create "1"
                    operand = new NumberNode("1", parser.tokenIndex);
                    break;
                default:
                    // create `$_` variable
                    operand = new OperatorNode(
                            "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
                    break;
            }
        }
        return new OperatorNode(text, operand, parser.tokenIndex);
    }
}